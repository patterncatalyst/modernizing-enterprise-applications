---
title: "Transaction Log Tailing, CDC & Extraction 3: Inventory"
order: 19
part: "Data Across the Seam"
description: "Log-tailing CDC with Debezium bridges the monolith's tables during the transition; Inventory leaves behind a gRPC contract with a decomposed database and an explicit compensating Release."
---

## Learning objectives

By the end of this chapter you can:

- Explain why a synchronous, mutating collaborator is harder to extract than a read-only leaf or an asynchronous consumer.
- Design a gRPC contract whose wire vocabulary diverges from the monolith's as a structural anti-corruption layer.
- Configure Postgres logical replication for Debezium (`wal_level=logical`, `pgoutput`, `REPLICA IDENTITY FULL`) and say why each setting is required.
- Build an idempotent CDC consumer that upserts on the source's primary key and absorbs at-least-once redelivery.
- Replace a pessimistic-lock reservation with an atomic conditional `UPDATE` (`Reserve`) and its mirror (`Release`).
- Replace the free rollback of a shared ACID transaction with an explicit compensating action, and verify it across two databases.
- Run a negative check across a cross-database seam and publish a hybrid-state finding instead of hiding it.

## Why this matters

Review left the monolith as a REST leaf with no synchronous collaborator and nothing to protect. Notification left as an asynchronous consumer that checkout never waited on. Inventory is neither. `OrderService#placeOrder` calls into inventory inside the checkout transaction, synchronously, and mutates stock when it does: a reservation checkout depends on before payment is attempted. Extracting it turns an in-JVM method call guarded by a pessimistic lock into a request-response call over a network, against a database that checkout's transaction no longer controls. The payment-declined scenario depends on a write being undone, and it must stay green.

The code is in `examples/04-inventory-service/` and `examples/00-monolith/` (`RemoteInventoryClient`, `OrderService`, `OrderItem`). The Debezium connector lives in `infra/debezium/`, the proxy route in `examples/01-strangler-proxy/`, and the recorded evidence in `examples/04-inventory-service/MIGRATION.md` and `examples/01-strangler-proxy/CUTOVER.md`. The order extraction later removed the monolith's `order` and `inventory` packages from the current tree; the monolith code quoted below is at the `stage/03-inventory-extracted` tag (`git checkout stage/03-inventory-extracted`), and the pre-extraction `InventoryService` is on the `reference/monolith-before` branch.

{% include excalidraw.html file="inventory-extraction-topology" alt="The inventory extraction's steady-state topology: a client through the Camel strangler proxy to either the Spring monolith's checkout or the Quarkus inventory service's own schema; the monolith's OrderService reserves and releases stock over a synchronous gRPC seam against the inventory service's own database; a retired Debezium CDC path at the bottom shows the one-time backfill that seeded that database during the transition window." caption="Figure 19.1: The inventory extraction's steady-state seam (synchronous gRPC Reserve/Release) and the now-retired CDC backfill path that got the owned database there" %}

## Why inventory is the hardest cut yet

In the monolith, `placeOrder` called `inventoryService.findBySkuOrThrow(sku)` and then `inventoryService.reserve(sku, qty)` as ordinary method calls inside the same `@Transactional` that persisted the order and charged a card. The first call returned the actual JPA entity, the producer's persistence representation leaking across a bounded-context boundary. The second took a pessimistic write lock (`findWithLockBySku`, `PESSIMISTIC_WRITE`) and decremented stock in the same row, database and transaction as everything else. Three properties make the extraction harder than the two before it:

- **The collaborator is synchronous.** Checkout can't return `201 Created` until the reservation succeeds, so a slow network, a hung process or a missing service becomes a checkout failure mode.
- **The call writes.** Once inventory owns its database, the monolith's `@Transactional` can't roll the decrement back, because Postgres can't undo a commit in a different database.
- **The contexts share a foreign key.** `order_items.inventory_item_id` points at `inventory_items.id` through a JPA `@ManyToOne`, so `OrderItem` can't be persisted without inventory's schema beside it.

Fixing one without the others leaves the extraction incomplete.

## Pattern: Anti-Corruption Layer (the gRPC seam)

The read surface, `GET /api/inventory` and `GET /api/inventory/{sku}`, stays REST behind the same Camel strangler proxy. The hot path checkout depends on is a low-latency, binary, service-to-service call, which is what `quarkus-grpc` is for. The proto adapts the read-only `CheckStock` from the sibling `datamesh-reference-arch-quarkus` project and adds the two mutating RPCs, `Reserve` and its compensating `Release`, plus `GetStock` for the proxy's content-enricher fetch.

The proto's vocabulary is not the monolith's. `StockDto` and `InventoryItem` speak `sku`, `quantityOnHand`, `priceCents`; `inventory.proto` speaks `stock_keeping_unit`, `on_hand_qty`, `unit_price_cents`. A translator that maps identical names proves nothing about whether a translation step exists. One that renames every field is doing translation work, and both sides of the seam do: `RemoteInventoryClient` on the call side and `InventoryGrpcServiceImpl` on the serve side, with the generated types never crossing past either class. This also cures the raw-entity leak: nothing in the monolith references `InventoryItem` any more, because the entity and the in-JVM `InventoryService` no longer exist there.

{% include codetabs.html langs="Before: raw entity leaks across the seam (SMELL #5, removed)|After: RemoteInventoryClient's typed gRPC translation" %}

```java
// inventory/InventoryService.java (now deleted entirely)
/**
 * SMELL[ch.16]: returns the raw JPA entity, not a DTO/contract, to a caller
 * (order.OrderService) outside this context: there is no anti-corruption
 * layer at this seam.
 */
public InventoryItem findBySkuOrThrow(String sku) {
    return repository.findBySku(sku)
            .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + sku));
}

// order/OrderService.java#placeOrder (rewritten)
InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
inventoryService.reserve(line.sku(), line.quantity());
order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
```

```java
// examples/00-monolith/.../inventory/RemoteInventoryClient.java (current)
public ReserveResult reserve(String sku, int quantity) {
    ReserveReply reply = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
            .reserve(ReserveRequest.newBuilder()
                    .setStockKeepingUnit(sku)
                    .setRequestedQty(quantity)
                    .build());
    return new ReserveResult(reply.getReservationOk(), reply.getOnHandQty());
}

// Clean translation: the raw proto type never leaks past this client.
public record ReserveResult(boolean ok, int onHandQty) {}
```

`RemoteInventoryClient` carries a per-call deadline (`inventory.grpc.timeout-ms`, default 5000 ms). If inventory is slow or unreachable, the blocking stub throws an unchecked `StatusRuntimeException` that `reserve` leaves uncaught. It is not a logical "insufficient stock" outcome, so it must never map to `InsufficientStockException`'s `409`. Spring turns it into a `500`, which is correct: checkout fails cleanly instead of confirming an order whose reservation status nobody knows.

## Pattern: Change Data Capture

Instead of a big-bang cutover (stop the monolith, export inventory's rows, stand up a database, import, restart), the schema split runs as a backfill underneath a system that keeps running. A **Debezium Postgres connector** on Kafka Connect reads the monolith database's write-ahead log directly, with no polling query and no table scan, and turns every insert, update and delete on `public.inventory_items` into an ordered change stream.

Three Postgres settings make that possible, and none is a default:

| Setting | Why it is required |
|---|---|
| `wal_level=logical` | The WAL must retain enough information for logical decoding, not just crash recovery. |
| `pgoutput` plugin | Postgres's built-in logical-decoding format; no separately installed decoder. |
| `REPLICA IDENTITY FULL` on `inventory_items` | The default identity puts only primary-key columns in the `before` image of an update or delete; `FULL` carries the whole prior row. |

The connector registers a replication slot (`mea_inventory_slot`) and a publication (`mea_inventory_publication`) scoped to one table, then runs with `snapshot.mode=initial`: it emits one `op=r` event per existing row (the backfill) before streaming `op=c`, `u` and `d` events as the monolith keeps writing.

```json
{
  "name": "mea-inventory-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres",
    "topic.prefix": "mea",
    "schema.include.list": "public",
    "table.include.list": "public.inventory_items",
    "plugin.name": "pgoutput",
    "slot.name": "mea_inventory_slot",
    "publication.name": "mea_inventory_publication",
    "snapshot.mode": "initial",
    "tombstones.on.delete": "false",
    "key.converter.schemas.enable": "false",
    "value.converter.schemas.enable": "false"
  }
}
```

`InventoryCdcConsumer` is new code with no Spring original; the monolith never consumed its own change stream. Every operation funnels through one idempotent write path. The excerpt is simplified: the real method also guards against a null or blank payload (a Debezium tombstone), wraps the JSON parse in a try/catch and checks for a missing `op`.

```java
// examples/04-inventory-service/.../InventoryCdcConsumer.java (simplified)
@Incoming("inventory-cdc")
@Transactional
public void consume(String json) {
    JsonNode envelope = unwrapPayload(objectMapper.readTree(json));
    String op = envelope.path("op").asText(null);
    switch (op) {
        case "r", "c", "u" -> upsert(envelope.get("after"));
        case "d" -> delete(envelope.get("before"));
        default -> LOG.warnf("inventory-cdc: unhandled Debezium op '%s', skipping", op);
    }
}
```

With `schemas.enable=false` the envelope arrives flat, without the outer `{"schema":...,"payload":{...}}` wrapper. That is why the consumer binds a plain `String` channel and parses the envelope with Jackson instead of auto-deserializing a typed record. `upsert` writes through an `ON CONFLICT` keyed by the source row's `id`, so Kafka's at-least-once redelivery never double-applies a change. A captured streamed update decrementing stock from 10 to 7:

```
{"before":{"id":402,"sku":"SKU-CDC-UPDATE","name":"Update Widget","price_cents":1500,
"quantity_on_hand":10,"updated_at":1767600000000},
"after":{"id":402,"sku":"SKU-CDC-UPDATE","name":"Update Widget","price_cents":1500,
"quantity_on_hand":7,"updated_at":1767600002000},
"source":{"table":"inventory_items"},"op":"u","ts_ms":1767600003000}
```

Delivered twice through the real `@Incoming("inventory-cdc")` pipeline, `quantity_on_hand` settles at 7, not 4.

{% include excalidraw.html file="cdc-debezium-backfill-pipeline" alt="The CDC backfill pipeline's mechanism, left to right: the monolith's Postgres WAL (wal_level=logical, pgoutput, REPLICA IDENTITY FULL), the mea_inventory_slot replication slot and mea_inventory_publication publication registered on it, the mea-inventory-connector Debezium connector reading that feed (snapshot.mode=initial, emitting op=r backfill events once then streaming op=c/u/d), the mea.public.inventory_items Kafka topic, and InventoryCdcConsumer's idempotent ON CONFLICT upsert into the inventory service's own schema. A second band shows the slot and publication's retirement: scripts/retire-debezium.sh, run once at cutover, deletes the connector and drops both the slot and the publication." caption="Figure 19.3: The CDC backfill pipeline's mechanism (WAL through the replication slot, the Debezium connector, and the Kafka topic, to InventoryCdcConsumer's idempotent upsert), and the slot/publication's one-time retirement at cutover" %}

### Polling relay or CDC

The notification extraction used a polling outbox relay, the right default: confirmation email tolerates seconds of delay, and a two-line `@Scheduled` method carries none of a replication slot's operational weight. Inventory's calculus differs. Stock is read on nearly every product page and checkout attempt, so multi-second lag between a reservation committing and that fact becoming visible downstream costs far more than a delayed email. That cost is what justifies the connector, the slot and the Kafka Connect process.

## Owned data, then own-seed

Inventory owns its schema from day one, for the same reason notification did: a CDC-fed replica sharing the upstream table with the monolith's live write path would have two writers racing on the same rows. `examples/04-inventory-service` runs its own `inventory` Postgres schema with its own Flyway migrations, seeded by the connector's initial snapshot until cutover. Once `RemoteInventoryClient`'s `Reserve` and `Release` make the service the sole writer, the connector has nothing left to replicate, and `scripts/retire-debezium.sh` deletes it and drops the slot and publication. An undrained slot retains WAL on the monolith's Postgres indefinitely, a disk-fill risk with no offsetting benefit.

Retirement leaves a gap: a fresh environment or clean CI run has no upstream writer whose log a connector could tail, so `inventory.inventory_items` would boot empty. A Flyway migration, `V2__seed_inventory.sql`, seeds the same three demo SKUs the monolith's own seed always used. It is idempotent (`ON CONFLICT DO NOTHING`, no conflict target), so it is a no-op against a long-lived stack that CDC already backfilled and never clobbers live stock.

## Two phases, and a gRPC server with no Spring original

Every extraction follows two phases. Phase A moves the Spring surface onto Quarkus largely unchanged through the Quarkiverse Spring-compatibility extensions, to get the equivalence gate green fast. Phase B removes the shim for idiomatic Quarkus and captures before/after numbers. Inventory's read surface follows that template. The gRPC server and the mutating `Reserve` and `Release` do not, because the monolith's `reserve` lived in-JVM against a pessimistic-lock derived query, a different mechanism. Those pieces are idiomatic from day one.

{% include codetabs.html langs="Phase A: Spring Data JpaRepository (compat lift, read-only)|Phase B: Panache repository (idiomatic; net-new Reserve/Release)" %}

```java
// Phase A (per MIGRATION.md's per-component table), via quarkus-spring-data-jpa
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {
    Optional<InventoryItem> findBySku(String sku);
    // No mutating methods yet: the gRPC server didn't exist in Phase A;
    // quarkus-grpc was present only to compile the proto's generated stubs.
}
```

```java
// examples/04-inventory-service/.../InventoryRepository.java (Phase B, current)
@ApplicationScoped
public class InventoryRepository implements PanacheRepository<InventoryItem> {

    public Optional<InventoryItem> findBySku(String sku) {
        return find("sku", sku).firstResultOptional();
    }

    /** Atomic check-and-decrement. Returns 1 if reserved, 0 if insufficient stock. */
    public int reserve(String sku, int qty) {
        return update("quantityOnHand = quantityOnHand - ?1 where sku = ?2 and quantityOnHand >= ?1", qty, sku);
    }

    /** Compensating re-increment. Returns 1 if the sku existed and was restored, 0 otherwise. */
    public int release(String sku, int qty) {
        return update("quantityOnHand = quantityOnHand + ?1 where sku = ?2", qty, sku);
    }
}
```

`MIGRATION.md` records the before/after:

| Build | Startup time | RSS | Installed features |
|---|---|---|---|
| Phase A: JVM, Spring-compat (no gRPC port bound) | 1.673 s | ~313 MB | 19 features incl. `spring-data-jpa`, `spring-di`, `spring-web` |
| Phase B: JVM, idiomatic + gRPC server live | 1.677 s | ~300 MB | 16 features; `spring-*` gone, `grpc-server` added |

Startup is a wash within run-to-run noise. RSS drops roughly 4% even though Phase B adds a live gRPC server, because the three Spring-compat translation extensions cost more at boot than the gRPC server does.

## Reserve is an atomic conditional decrement

`Reserve` does not read the quantity, decide in application code and then write, the classic check-then-act race. It issues one conditional `UPDATE`:

```sql
UPDATE inventory.inventory_items
   SET quantity_on_hand = quantity_on_hand - :qty
 WHERE sku = :sku AND quantity_on_hand >= :qty
```

and reports the rows-affected count. One row means the check and decrement happened atomically, in the database's transaction under its row-level write lock: no `SELECT ... FOR UPDATE`, no `@Version` column, no application lock. Two concurrent calls racing a SKU's last unit serialize on that lock. Whichever commits first leaves `quantity_on_hand` below the other's `>= :qty` predicate, so the second `UPDATE` affects zero rows and reports insufficient stock, with no decrement made on its behalf. Racing two simultaneous reservations for one remaining unit, exactly one reports `reservationOk: true`; sixteen tests back the Phase A to Phase B transition, that concurrency case among them.

`Release` is the unconditional mirror (`quantity_on_hand + :qty WHERE sku = :sku`). Unlike `Reserve`, it carries no idempotency key: a retried call whose effect already landed would over-restore stock. The gap is documented in its javadoc and left for a full saga, since this extraction needs no speculative compensation infrastructure yet.

## Pattern: Saga (the compensating Release)

In the monolith, a single in-process ACID transaction spanned order, inventory, payment and shipping. When `PaymentService#charge` threw a decline, Postgres rolled back everything, including the inventory decrement executed lines earlier. No application code made that happen; it was a side effect of one database's log.

{% include excalidraw.html file="inventory-reserve-compensation-sequence" alt="Two stacked sequence diagrams: the old flow where a single in-process ACID transaction rolls back a payment decline's inventory decrement automatically; the new flow where Reserve commits in the inventory service's own database outside the monolith's transaction, so a payment decline must trigger an explicit compensating Release instead." caption="Figure 19.2: Reserve/Release, the cross-seam saga-lite that replaces the in-process free rollback a shared transaction used to provide" %}

Once the decrement commits in its own database over its own gRPC call, that rollback is gone: no single log spans both writes. `OrderService#placeOrder` tracks every SKU it reserved remotely during this checkout, and its `catch` block issues a compensating `Release` for each one on any later failure: insufficient stock on a later line, a payment decline, a shipping failure, any exception before the order confirms.

```java
// examples/00-monolith/.../order/OrderService.java
try {
    // ... reserve each line via RemoteInventoryClient, track remoteReservations ...
    paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
    // ...
} catch (RuntimeException ex) {
    compensateRemoteReservations(remoteReservations);
    throw ex;
}
```

This is a minimal first taste of saga compensation: explicit, best-effort, logged loudly on failure instead of swallowed. The difference between "the database undid it for me" and "I wrote the undo myself" is the central lesson. `CUTOVER.md` records it holding under real traffic:

```
CUTOVER.md, Scenario 3 (payment-declined), both flags in the cutover state:

| Scenario                        | Before (service) | Checkout            | After (service) |
|----------------------------------|-------------------|----------------------|-------------------|
| 3: Payment-declined (qty 1)      | 29                | 402 PAYMENT_DECLINED | 29 (net zero)     |

gRPC Reserve succeeded (stock decremented to 28 momentarily); payment then
declined; OrderService's catch block fired the compensating gRPC Release.
The net effect across two independent databases matches the monolith's old
same-transaction rollback: 29 in, 29 out.

GET :8888/api/inventory/SKU-WIDGET-001 (through the proxy) and
GET :8084/api/inventory/SKU-WIDGET-001 (the service directly) both read 29,
while GET :8080/api/inventory/SKU-WIDGET-001 (the monolith's frozen local
copy) read a different value, 34: the two backends own separate data.
```

Out-of-stock needs no compensation. When the `WHERE` clause fails, zero rows are touched and `reservation_ok=false` returns before any decrement, so `OrderService` maps it to `409` with nothing to undo.

## Decomposing the foreign key

`order_items.inventory_item_id` was an enforced Postgres foreign key and a JPA `@ManyToOne`, coupling enforced by the database rather than declared in application code. A foreign key can't point across two Postgres instances, so `OrderItem` now holds a denormalized snapshot, `sku`, `productName` and `unitPriceCents`, captured once at checkout from the `GetStock` reply. `sku` stays as a soft string reference with no constraint and no association:

```java
// examples/00-monolith/.../order/OrderItem.java
/** Soft reference only: a plain string, no DB FK and no JPA association onto inventory.InventoryItem. */
@Column(nullable = false)
private String sku;
```

Capturing the price at order time is correct behavior beyond decomposition: a later price change in inventory never rewrites a historical order's lines. `OrderDto.Item` already projected sku, quantity and unit price from these fields, so `GET /api/orders/{id}` is unchanged byte for byte.

## Cutover, the negative check, and the hybrid-state finding

Reversibility uses the same two-flag shape as every prior extraction: a monolith-side flag (`inventory.mode=local|remote`) for the write path and a proxy-side flag (`strangler.inventory.enabled`) for the read path, flipped together.

With both flags in the cutover state, the inventory process was killed outright:

```
Result: RED, newman exit code 1, 34 of 56 assertions failed.
- GET /api/inventory -> 500 (proxy's inventory target unreachable)
- POST /api/orders (Scenario 1)  -> 500, not the expected 201
- Scenario 2/3 checkouts -> 500, not their expected 409/402
  (the first step of placeOrder in remote mode is the gRPC Reserve call;
  with no service to answer it, checkout never reaches the out-of-stock
  or decline branches)
Review and Notification folders were unaffected: the failure was scoped
to the inventory seam, not a proxy-wide outage.

After restart: GREEN, 75/75 assertions, newman exit code 0.
```

Unlike Notification's negative check, there was no backlog to drain: `Reserve` and `Release` are synchronous RPCs with no queue behind them, so recovery was immediate. The checks fail hard and precisely scoped when the extracted service is down, which closes the false-equivalence gap a shared-table coincidence opened for Review.

The second finding was published rather than avoided. An operator could flip only the proxy's read flag and leave the monolith's write flag at its default (`local`). `CUTOVER.md` records that combination:

```
Proxy committed default (true) + monolith default (inventory.mode=local):
checkout writes land in the monolith's shared inventory_items table; reads
are served by the inventory service's CDC-replicated copy. Run three times,
it failed exactly one assertion per run: "Scenario 1 / 1d. Stock decremented
by exactly the ordered quantity", off by exactly 1 ("expected 32 to deeply
equal 31"). The immediate post-checkout read outran the connector's
replication of that same checkout's write.
```

That is the eventual-consistency risk inherent to CDC. Replication is asynchronous by construction, and the dedicated Inventory Context Contract read folder uses a bounded wait for that reason; a checkout scenario's immediate read-after-write does not. No code was changed to paper over it, the Newman collection stayed unedited, and the `local` default stayed. The resolution belongs to decommission: remove the monolith's local inventory write path so `remote` is the only path, which removes one of the hybrid state's two writers. After that `GET :8080/api/inventory` returns `404` on the monolith, `scripts/retire-debezium.sh` retires the connector, and `SMELLS.md` marks the raw-entity leak and the foreign-key portion of the shared-schema smell cured.

> **ADLC in Action.** The extraction ran the Frame, Map, Plan, Generate, Verify, Operate, Reconcile loop at its hardest rung yet. Frame fixed eight decisions up front: gRPC for the synchronous hot path, Debezium on Kafka Connect over Embedded, the sync-reserve/async-replication split, Reserve plus a compensating Release as a first taste of saga, the foreign-key decomposition, two phases plus idiomatic gRPC, two-flag reversibility, and the extended negative check. Plan laid out sixteen steps across three parallel lanes (CDC and Connect infrastructure, the gRPC contract, the service scaffold) with a reasoning-model validation gate named before any gRPC or CDC code existed, most pointedly at the cutover step where out-of-stock and decline rollback had to be proven across the real boundary. Generate produced the proto, server, CDC consumer and compensating client under quarkus-agent, following the `migrate-spring-to-quarkus` process for the lifted read surface. Verify was the negative check and the hybrid-state finding, including the one that didn't go as planned. Operate was the two-flag cutover. Reconcile was `SMELLS.md` marking the smells cured, with replication-slot teardown as the closing housekeeping.

## CI

The `inventory-equivalence-gate` job in `.github/workflows/code-ci.yml` brings up a plain disposable `postgres:16-alpine` (no logical replication, since CDC is retired) plus Kafka, started only so the monolith's outbox producer boots cleanly. It builds and starts the inventory service (REST `:8084`, gRPC `:9004`), self-seeded by `V2__seed_inventory.sql`, and the monolith as an unconditional gRPC client. With the strangler proxy up, it runs the Smoke, Scenario 1, 2, 3 and Inventory Context Contract folders from the unedited collection, failing on any non-zero `newman` exit. The job was proven red-then-green: with the compensating `Release` disabled, a forced decline left stock decremented and turned Scenario 3's "3c" stock assertion red; restoring the call turned it green.

> **Decision**
>
> Extract a synchronous, mutating collaborator behind a gRPC contract with its own vocabulary and its own database, keep the write synchronous because overselling can't be repaired afterward, and replicate only the read copy asynchronously. Write an explicit compensating call for every failure after the reservation. Choose CDC over a polling relay only when downstream read lag is a direct cost, and retire the slot when the owner becomes the sole writer.

> **🤖 Explore with your agent**
>
> "Check out the stage/03-inventory-extracted tag. In `examples/00-monolith`, find every failure path in `OrderService#placeOrder` that can occur after `RemoteInventoryClient.reserve` succeeds, and check that each one reaches `compensateRemoteReservations`. Add a path that throws after payment and show the stock for `SKU-WIDGET-001` through `:8888/api/inventory/SKU-WIDGET-001` returns to its starting value."
>
> Check: read the stock before and after a forced failing checkout; the two values are equal.

## Build, run, observe

```bash
demos/demo-inventory-cutover.sh
```

The script runs `scripts/stack-up.sh` if the compose stack isn't up, starts the inventory service and the proxy, runs the Inventory Context Contract through the edge router on `:8888`, then stops the inventory service and confirms the checks go red. The proxy on `main` is a flagless edge router, so the flag-flip cutover and its reversibility window need the snapshot `git checkout stage/03-inventory-extracted`. The inventory service listens on `8084` (REST) and `9004` (gRPC).

Experiments:

- At `stage/03-inventory-extracted`, with both flags in the cutover state, stop `examples/04-inventory-service` and run the full collection through `:8888`: 34 of 56 assertions fail, the checkout scenarios return `500` instead of `201`, `409` or `402`, and the Review and Notification folders stay green. Restart it and the run is 75/75 green.
- At the same tag, disable the compensating `Release` in `OrderService` and run Scenario 3 (payment declined): stock stays decremented and the "3c" stock assertion goes red.

## For deeper coverage

- [Debezium: Connector documentation](https://debezium.io/documentation/), the canonical reference for the Postgres connector, snapshot modes and WAL requirements.
- [PostgreSQL: Logical Replication](https://www.postgresql.org/docs/current/logical-replication.html), the canonical reference for `wal_level=logical`, the `pgoutput` plugin, and `REPLICA IDENTITY FULL`, the three settings the CDC pipeline depends on.
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017), change data capture and log-based replication as a structural alternative to dual writes, the mechanism underlying the backfill.
- Chris Richardson, *Microservices Patterns* (Manning, 2018), saga-style compensation, the pattern the Reserve/Release pair is a first, minimal taste of.
- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003), the anti-corruption layer the divergent gRPC wire vocabulary implements.
- Cloud Native Design Patterns, [Stream Processing](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/06-stream-processing.md), the companion book's treatment of log-based stream processing underlying the Debezium pipeline.
