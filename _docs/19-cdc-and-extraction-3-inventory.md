---
title: "Transaction Log Tailing, CDC & Extraction 3 — Inventory"
order: 19
part: "Data Across the Seam"
description: "Debezium-style CDC/log-tailing to bridge the monolith's tables during transition; gRPC-first Inventory with a decomposed DB and CDC backfill."
---

Review left the monolith as a REST leaf with no synchronous collaborator and
no mutation to protect. Notification left as an asynchronous consumer the
checkout transaction never waited on, so turning it into an eventual-consistency
problem only ever *shrank* the blast radius of a failure. Inventory is neither
of those things. `OrderService#placeOrder` has always called into inventory
*inside* the checkout transaction, synchronously, and has always *mutated*
stock when it did — a reservation the rest of checkout depends on completing
before payment is even attempted. Extracting it means turning an in-JVM method
call guarded by a pessimistic lock into a request-response call across a real
network boundary, against a database checkout's own transaction no longer
controls, while keeping every one of this project's equivalence-gate scenarios
green — including the one, payment-declined, that depends on a write being
undone. Everything described below is running code already committed in
this repository: `examples/04-inventory-service/` (the extracted Quarkus
service, its gRPC server, its CDC consumer, and its `MIGRATION.md`'s measured
record), `examples/00-monolith/` (`RemoteInventoryClient`, `OrderService`'s
reserve-then-compensate checkout, and `OrderItem`'s denormalized snapshot),
`infra/debezium/` (the now-retired Debezium connector and its README's
lifecycle record), `examples/01-strangler-proxy/` (the `/api/inventory` route
and `CUTOVER.md`'s full inventory-cutover timeline), and
`tooling/newman/mea.postman_collection.json` (the Inventory Context Contract
folder alongside the unedited checkout scenarios).

The code is in `examples/04-inventory-service/` and `examples/00-monolith/`.
The run script in each directory builds/sets up and runs it; each `README.md`
covers what it does and how to drive it.

{% include excalidraw.html file="inventory-extraction-topology" alt="The inventory extraction's steady-state topology: a client through the Camel strangler proxy to either the Spring monolith's checkout or the Quarkus inventory service's own schema; the monolith's OrderService reserves and releases stock over a synchronous gRPC seam against the inventory service's own database; a retired Debezium CDC path at the bottom shows the one-time backfill that seeded that database during the transition window." caption="Figure 19.1 — The inventory extraction's steady-state seam (synchronous gRPC Reserve/Release) and the now-retired CDC backfill path that got the owned database there" %}

## Why inventory leaves third, and why it's the hardest cut yet

Chapter 9 named this concern as Smell 5 the moment the monolith existed:
`OrderService#placeOrder` called `inventoryService.findBySkuOrThrow(sku)` and
then `inventoryService.reserve(sku, qty)`, both as ordinary Java method calls
on the same call stack, inside the same `@Transactional` that also persisted
the order and charged a card. The first call handed back the *actual* JPA
entity — no DTO, no contract, the producer's own persistence representation
leaking straight across a bounded-context boundary — and the second took a
pessimistic write lock (`findWithLockBySku`, `@Lock(LockModeType.PESSIMISTIC_WRITE)`)
and decremented stock in the same row, in the same database, in the same
transaction as everything else checkout does. Three separate properties of
that design make this extraction harder than either chapter that came before
it.

First, it is a synchronous collaborator sitting in the critical path, not a
side effect checkout can forget about. Checkout cannot return `201 Created`
until the reservation succeeds, so moving it across a service boundary
introduces real failure modes — a slow network, a hung process, a service that
simply isn't there — that an in-JVM method call never had to consider. Second,
unlike the datamesh reference architecture's read-only `CheckStock` RPC, this
call *writes*. The moment inventory owns its own database, the monolith's
`@Transactional` can no longer roll that decrement back, because Postgres
cannot undo a write that committed in a different database entirely. Third,
the two contexts share a literal foreign key: `order_items.inventory_item_id`
points straight at `inventory_items.id`, a JPA `@ManyToOne` that makes
`OrderItem` an entity order cannot persist without inventory's schema sitting
right next to it. Fixing any one of these three problems without the other two
would be an incomplete extraction; fixing all three together, while every
scenario in the behavior-equivalence suite stays green, is what the rest of
this chapter does.

## The gRPC seam: the proto as a typed anti-corruption layer

The extraction's client-facing read surface — `GET /api/inventory`,
`GET /api/inventory/{sku}` — stays REST, routed by the same Camel strangler
proxy every prior extraction used. The hot path checkout actually depends on
is different in kind: a low-latency, binary, service-to-service call, which is
exactly what `quarkus-grpc` is built for, and exactly the pattern the sibling
`datamesh-reference-arch-quarkus` project already demonstrates for a read-only
`CheckStock`. This project adapts that proto and extends it with the two
mutating RPCs checkout actually needs — `Reserve` and its compensating
`Release` — plus `GetStock` for the strangler proxy's own content-enricher
fetch.

The proto's wire vocabulary is deliberately *not* the monolith's internal
shape. `StockDto`/`InventoryItem` speak `sku`/`quantityOnHand`/`priceCents`;
`inventory.proto` speaks `stock_keeping_unit`/`on_hand_qty`/`unit_price_cents`.
That divergence is not an accident of two teams picking different words for
the same thing — it is the chapter's anti-corruption layer made structural.
A translator that happens to map identical field names to each other proves
nothing about whether a real translation step exists; a translator that has
to rename every field is doing real translation work, and both sides of this seam — the
monolith's `RemoteInventoryClient` on the call side, `InventoryGrpcServiceImpl`
on the serve side — are exactly that translator, with the generated proto
types never crossing past either class's boundary. This is also the chapter
where Smell 5 actually gets cured, not just diagnosed: no code anywhere in the
monolith holds a reference to the raw `InventoryItem` entity any longer,
because that entity — and the in-JVM `InventoryService` that produced it —
no longer exists in this module at all.

{% include codetabs.html langs="Before — raw entity leaks across the seam (SMELL #5, removed)|After — RemoteInventoryClient's typed gRPC translation" %}

```java
// inventory/InventoryService.java — quoted in Chapter 9, now deleted entirely
/**
 * SMELL[ch.16]: returns the raw JPA entity, not a DTO/contract, to a caller
 * (order.OrderService) outside this context — there is no anti-corruption
 * layer at this seam.
 */
public InventoryItem findBySkuOrThrow(String sku) {
    return repository.findBySku(sku)
            .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + sku));
}

// order/OrderService.java#placeOrder — quoted in Chapter 9, now rewritten
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

// Clean translation — the raw proto type never leaks past this client.
public record ReserveResult(boolean ok, int onHandQty) {}
```

`RemoteInventoryClient` also carries an explicit per-call deadline
(`inventory.grpc.timeout-ms`, default 5000ms). If the inventory service is
slow or unreachable, the blocking stub throws an unchecked
`StatusRuntimeException`, and that exception is left
*uncaught* by `reserve` — it is not a logical "insufficient stock" outcome, so
it must never be mapped to `InsufficientStockException`'s `409`. Spring's
default handling turns the unmapped exception into a `500`, the correct
outcome: checkout fails cleanly rather than silently confirming an
order whose reservation status nobody actually knows.

## Transaction log tailing: Debezium reads the write-ahead log

Chapter 18 previewed the mechanism this chapter actually runs: rather than a
big-bang cutover — stop the monolith, export inventory's rows, stand up a new
database, import, restart everything — the schema split happens as a
*backfill*, underneath a system that keeps running the entire time. That
backfill is **change data capture**, and this project's version of it is as
close to the textbook mechanism as the stack allows: a **Debezium Postgres
connector**, running on **Kafka Connect** (added to the podman stack for this
chapter), reads the monolith Postgres instance's write-ahead log directly —
not a polling query, not a table scan, the actual WAL — and turns every insert,
update, and delete against `public.inventory_items` into an ordered stream of
change events.

Three pieces of Postgres configuration make this possible, and all three are
real, applied settings rather than defaults: `wal_level=logical` (the WAL must
retain enough information for *logical* decoding, not just crash recovery),
the `pgoutput` plugin (Postgres's own built-in logical-decoding output format,
needing no separately installed decoder), and `REPLICA IDENTITY FULL` on
`inventory_items` (Postgres's default replica identity only includes
primary-key columns in a row's "before" image on update/delete, which would
leave Debezium's `before` field mostly empty; `FULL` carries the complete prior
row). The connector registers a replication slot
(`mea_inventory_slot`) and a publication (`mea_inventory_publication`) scoped
to exactly one table, then runs with `snapshot.mode=initial`: its very first
act is to emit one `op=r` ("read") event per existing row — the backfill —
before switching to streaming `op=c`/`u`/`d` events as the monolith keeps
writing.

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

On the receiving end, `InventoryCdcConsumer` is net-new code with no Spring
original to lift — the monolith never consumed its own change stream — and it
funnels every operation through one idempotent write path. The excerpt below
is simplified for exposition; the real method additionally guards against a
null/blank payload (a Debezium tombstone), wraps the JSON parse in a
try/catch, and checks for a missing `op` before the switch:

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

Because `schemas.enable=false` strips the field-schema metadata Kafka Connect
would otherwise wrap each message in, the envelope arrives flat on the wire —
no outer `{"schema":...,"payload":{...}}` wrapper — which is why the consumer
binds a plain `String` channel and parses the envelope by hand with Jackson
rather than letting Quarkus auto-deserialize a typed record. `upsert` writes
through an `ON CONFLICT` keyed by the CDC-assigned `id` — the same primary key
the monolith's row carries — so Kafka's at-least-once redelivery semantics
never double-apply a change. The following is a captured envelope, taken
verbatim from `InventoryCdcConsumerTest`'s real Reactive Messaging pipeline
test (not hand-written for this chapter), showing a streamed update decrementing
stock from 10 to 7:

```
CAPTURED EVIDENCE — a streamed Debezium change event (op=u), flat envelope
shape, exactly as the connector emits it with schemas.enable=false:

{"before":{"id":402,"sku":"SKU-CDC-UPDATE","name":"Update Widget","price_cents":1500,
"quantity_on_hand":10,"updated_at":1767600000000},
"after":{"id":402,"sku":"SKU-CDC-UPDATE","name":"Update Widget","price_cents":1500,
"quantity_on_hand":7,"updated_at":1767600002000},
"source":{"table":"inventory_items"},"op":"u","ts_ms":1767600003000}

Sent twice through the real @Incoming("inventory-cdc") pipeline
(InventoryCdcConsumerTest#updateEvent_isIdempotentUnderRedelivery) to prove
redelivery doesn't double-decrement: quantity_on_hand settles at exactly 7,
not 4, confirming the ON CONFLICT upsert is idempotent under at-least-once
delivery.
```

This is also where the deferred decision Chapter 17 named actually gets
spent. Chapter 17 reached for a polling relay, not CDC, for
notification's outbox — the right default, because notification's latency
tolerance doesn't demand sub-second delivery and a two-line `@Scheduled`
method carries none of a replication slot's operational weight — and it
explicitly homed Debezium/CDC here, at the inventory extraction, instead.
Inventory's calculus is different: stock levels are read on nearly every
product page and every checkout attempt across the system, so a multi-second
lag between a reservation committing and that fact becoming visible
downstream is a far more direct cost than a slightly delayed confirmation
email. This chapter is where that cost finally justifies the connector, the
replication slot, and the Kafka Connect process Chapter 17
avoided paying for one extraction early. Chapter 20 later generalizes this
same outbox-vs-CDC tradeoff beyond this one pair of extractions; it is a
later reference point, not the origin of the deferral.

## Owned data, then own-seed: what happens once the tailed log is gone

Inventory owns its schema from day one, for the identical reason notification
did in Chapter 18: a CDC-fed replica that shared the upstream table with the
monolith's still-live write path would have two writers racing on the same
rows with no way to reconcile them. `examples/04-inventory-service` runs its
own `inventory` Postgres schema, its own Flyway migrations, and is seeded
entirely by the connector's initial snapshot — until cutover, when the roles
reverse. Once `RemoteInventoryClient`'s gRPC `Reserve`/`Release` makes the
inventory service the sole writer of its own data (the decommission step
below), the connector has nothing left to replicate, and `scripts/retire-debezium.sh`
deletes it and drops its replication slot and publication — an un-drained
slot would otherwise retain WAL on the monolith's Postgres indefinitely, a
disk-fill risk with no offsetting benefit once CDC's job is done.

That retirement creates a problem a CDC-only story would paper over: a
fresh environment — a clean CI run, a new developer's first `podman compose up`
— has no upstream writer left whose log a connector could tail, so without
something else, `inventory.inventory_items` would boot empty. The fix is a
Flyway migration, `V2__seed_inventory.sql`, that seeds the same three demo
SKUs the monolith's own `V2__seed_data.sql` always used, written
idempotently (`ON CONFLICT DO NOTHING`, no conflict target, so it is a no-op
against a long-lived stack CDC already backfilled and never clobbers live
stock a running demo has since moved away from the seed values). CDC backfilled
this service into existence; once it retires, the service has to be able to
seed itself the same way every other service in this book already does.

## Two phases, and a gRPC server with no Spring original to lift

Every extraction in this book follows the same two-phase migration: Phase A
lifts the existing Spring surface onto Quarkus largely unchanged, via the
Quarkiverse spring-compatibility extensions, to get the equivalence gate green
fast; Phase B removes the compatibility shim for idiomatic Quarkus and
captures the before/after numbers that make the "why Quarkus" argument
concrete. Inventory's read surface (`InventoryController`/`InventoryService#listAll,getBySku`)
follows that template exactly. Its gRPC server and its mutating `Reserve`/`Release`
RPCs do not, because there is no Spring original to lift for an RPC that never
existed in the monolith — the monolith's `reserve` lived in-JVM against a
pessimistic-lock derived query, a fundamentally different mechanism. Those
pieces are authored idiomatic-from-day-one.

{% include codetabs.html langs="Phase A — Spring Data JpaRepository (compat lift, read-only)|Phase B — Panache repository (idiomatic; net-new Reserve/Release)" %}

```java
// Phase A (per MIGRATION.md's per-component table) — via quarkus-spring-data-jpa
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {
    Optional<InventoryItem> findBySku(String sku);
    // No mutating methods yet — the gRPC server didn't exist in Phase A;
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

`MIGRATION.md`'s measured before/after tells the same non-triumphalist
story review-service's and notification-service's own measurements told:

| Build | Startup time | RSS | Installed features |
|---|---|---|---|
| Phase A — JVM, Spring-compat (no gRPC port bound) | 1.673 s | ~313 MB | 19 features incl. `spring-data-jpa`, `spring-di`, `spring-web` |
| Phase B — JVM, idiomatic + gRPC server live | 1.677 s | ~300 MB | 16 features; `spring-*` gone, `grpc-server` added |

Startup is a wash within run-to-run noise, but RSS drops roughly 4% —
*even though* Phase B adds a live `grpc-server` feature Phase A never bound a
port for — because the three spring-compat translation extensions removed
cost more at boot than one more gRPC server costs to add. Sixteen tests
back this transition, including a concurrency test that races two `Reserve`
calls for a sku's last remaining unit, proving the atomicity claim the next
section depends on.

## Reserve's atomic conditional decrement, and Release its mirror

`InventoryGrpcServiceImpl#reserve` does not read the current quantity, decide
in application code, and then write — the classic check-then-act race. It
issues one conditional `UPDATE`:

```sql
UPDATE inventory.inventory_items
   SET quantity_on_hand = quantity_on_hand - :qty
 WHERE sku = :sku AND quantity_on_hand >= :qty
```

and reports the rows-affected count. Exactly one row affected means the
check and the decrement happened atomically, in the database's own
transaction, under the database's own row-level write lock — no
`SELECT ... FOR UPDATE`, no optimistic `@Version` column, no application-level
lock of any kind. Two concurrent `Reserve` calls racing the same sku's last
unit serialize on that lock automatically: whichever commits first leaves
`quantity_on_hand` below the other caller's `>= :qty` predicate, so the second
`UPDATE` affects zero rows and reports insufficient stock, with *no* decrement
having happened on its behalf. `InventoryGrpcServiceTest`'s concurrency case
proves exactly this — two simultaneous reservations for one remaining unit,
exactly one reports `reservationOk: true`.

`Release` is the unconditional mirror — `quantity_on_hand = quantity_on_hand + :qty
WHERE sku = :sku`, no upper bound — and its own javadoc is explicit about what
it does *not* yet guard against: unlike `Reserve`'s conditional predicate,
`Release` carries no idempotency key, so a retried call whose effect already
landed server-side (the same at-least-once hazard the CDC consumer's upsert
guards against) would over-restore stock. That gap is documented, not
silently built around — a limitation deferred to Chapter
23's full saga, not speculative compensation infrastructure this extraction
does not need yet.

## The consistency crux: where the free rollback went

Smell 3, named back in Chapter 9 and the subject of Chapter 22's own
treatment, is the monolith's single in-process ACID transaction spanning
order, inventory, payment, and shipping. One concrete, valuable property fell
out of that design automatically: when `PaymentService#charge` threw a declined
exception, Postgres rolled back *everything* in that transaction, including
the inventory decrement that had already executed a few lines earlier. No
application code anywhere had to be written to make that happen. It was a
side effect of one database's write-ahead log, not a designed guarantee.

{% include excalidraw.html file="inventory-reserve-compensation-sequence" alt="Two stacked sequence diagrams: the old flow where a single in-process ACID transaction rolls back a payment decline's inventory decrement automatically; the new flow where Reserve commits in the inventory service's own database outside the monolith's transaction, so a payment decline must trigger an explicit compensating Release instead." caption="Figure 19.2 — Reserve/Release: the cross-seam saga-lite that replaces the in-process free rollback a shared transaction used to provide" %}

The instant inventory's decrement commits in its own database, over its own
gRPC call, that free rollback is structurally gone — there is no longer one
write-ahead log spanning both writes for Postgres to unwind. `OrderService#placeOrder`
now tracks every sku it has successfully reserved remotely this checkout, and
its surrounding `catch` block issues a compensating `Release` for each one on
*any* failure that happens afterward — insufficient stock on a later line, a
payment decline, a shipping failure, or any other exception before the order
confirms:

```java
// examples/00-monolith/.../order/OrderService.java
try {
    // ... reserve each line via RemoteInventoryClient, track remoteReservations ...
    paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
    // ...
} catch (RuntimeException ex) {
    compensateRemoteReservations(remoteReservations); // DRQ-042
    throw ex;
}
```

This is a deliberate, minimal first taste of saga compensation — explicit,
best-effort, logged loudly on failure rather than silently swallowed — not
the full choreographed saga Chapter 23 eventually builds. The difference
between "the database undid it for me" and "I had to write the undo myself"
is this chapter's single most important lesson, and `CUTOVER.md`'s own
captured evidence proves it actually holds under real traffic, not just under
a mocked unit test:

```
CAPTURED EVIDENCE — inventory-plan.md S10, CUTOVER.md, Scenario 3
(payment-declined, THE crux case), both flags flipped to the cutover state:

| Scenario                        | Before (service) | Checkout            | After (service) |
|----------------------------------|-------------------|----------------------|-------------------|
| 3 — Payment-declined (qty 1)     | 29                | 402 PAYMENT_DECLINED | 29 (net zero)     |

gRPC Reserve succeeded (stock was available, decremented to 28 momentarily);
payment then declined; OrderService's catch block fired the compensating
gRPC Release for the sku it had just reserved. The net observable effect
across two INDEPENDENT databases matches the monolith's old
same-transaction-rollback behavior exactly: 29 in, 29 out.

A second, independent confirmation that routing (not coincidence) produced
this result: GET :8888/api/inventory/SKU-WIDGET-001 (through the proxy) and
GET :8084/api/inventory/SKU-WIDGET-001 (the service directly) both read 29
after the scenario run, while GET :8080/api/inventory/SKU-WIDGET-001 (the
monolith's own frozen local copy, untouched since cutover) read a DIFFERENT
value, 34 — proof the two backends own genuinely separate data, not a
shared-table coincidence of the kind Chapter 7's Review false-positive
sprang.
```

Scenario 2, out-of-stock, needed no compensation at all: `Reserve`'s
conditional predicate is what makes that true — when the `WHERE` clause
fails, zero rows are touched and `reservation_ok=false` comes back before any
decrement happens, so `OrderService` maps that straight to the familiar `409`
with nothing to undo.

## Decomposing the foreign key: `OrderItem` becomes a snapshot

`order_items.inventory_item_id` was a real, enforced Postgres foreign key and
a real JPA `@ManyToOne` — the kind of coupling Chapter 18 called "enforced by
the database, not declared in application code." It cannot survive inventory
owning its own database, because a foreign key cannot point across two
separate Postgres instances. `OrderItem` now holds a denormalized snapshot —
`sku`, `productName`, `unitPriceCents` — captured once, at checkout time, from
the gRPC `GetStock` reply, with `sku` kept only as a soft string reference, no
database-level constraint and no JPA association:

```java
// examples/00-monolith/.../order/OrderItem.java
/** Soft reference only — a plain string, no DB FK and no JPA association onto inventory.InventoryItem. */
@Column(nullable = false)
private String sku;
```

The Flyway migration that cut the live constraint also captures why the
snapshot is correct behavior, not merely a decomposition side effect: because
the price is captured at order time, a later price change in inventory never
retroactively rewrites a historical order's line items — exactly the
semantics a read model should have anyway. `OrderDto.Item`'s shape already
projected sku/quantity/unit-price from these same fields before the cut, so
`GET /api/orders/{id}` is byte-for-byte unchanged; the equivalence suite's
order-read assertions prove it rather than merely assert it should be true.

## Cutover: two flags, a hard negative check, and a documented finding

Inventory's reversibility story uses the same two-flag shape every prior
extraction used — a monolith-side flag (`inventory.mode=local|remote`) for
the write path, a proxy-side flag (`strangler.inventory.enabled`) for the
read path — flipped together at cutover. `CUTOVER.md`'s recorded timeline is
the strongest evidence yet produced in this book for two separate claims.

The first is the negative check Chapter 17 introduced and this chapter had to
extend to a cross-database seam: with both flags in the cutover
state, the inventory service's process was killed outright.

```
CAPTURED EVIDENCE — inventory-plan.md S10, negative check (RED):

Result: RED — newman exit code 1, 34 of 56 assertions failed.
- GET /api/inventory -> 500 (proxy's inventory target unreachable)
- POST /api/orders (Scenario 1)  -> 500, not the expected 201
- Scenario 2/3 checkouts -> 500, not their expected 409/402
  (the FIRST step of placeOrder in remote mode is the gRPC Reserve call —
  with no inventory service to answer it, checkout never reaches the
  out-of-stock or decline branches at all)
Review and Notification folders were UNAFFECTED — the failure was scoped
exactly to the inventory seam, not a proxy-wide outage.

The service was restarted; the full suite was re-run:
Result: GREEN — 75/75 assertions, newman exit code 0.
```

Unlike Notification's negative check, there was no backlog to drain on
restart — `Reserve`/`Release` are synchronous RPCs with no queue behind them,
so recovery was immediate. The failure mode itself is the point: the suite
does not degrade gracefully or coincidentally pass when the extracted
service is down; it fails hard, loud, and precisely scoped, closing the exact
false-equivalence gap a shared-table coincidence opened for Review back in
Chapter 7.

The second claim is harder-won, and this project chose to publish it rather
than quietly avoid it. Between cutover (S10) and the eventual decommission of
the monolith's local inventory module (S11), an operator could, in principle,
flip only the proxy's read-side flag and leave the monolith's write-side flag
at its own bare default (`local`). That combination was run as
a documented finding rather than a defect:

```
CAPTURED EVIDENCE — CUTOVER.md, the hybrid-state finding:

Proxy committed default (true) + monolith's own default (inventory.mode=local)
— checkout writes land in the monolith's shared inventory_items table; reads
are served by the inventory service's CDC-replicated copy. Run three times,
this combination consistently failed exactly one assertion per run:
"Scenario 1 / 1d. Stock decremented by exactly the ordered quantity" —
off by exactly 1 every time ("expected 32 to deeply equal 31"): the test's
immediate post-checkout read outran the CDC connector's replication of that
same checkout's write.
```

This is precisely the eventual-consistency risk the project's own decision
log anticipated — CDC replication is asynchronous by construction — but it
had been assumed to land only on the dedicated Inventory Context Contract
read folder, which was written with a bounded-wait precisely for this reason,
not on a checkout scenario's immediate read-after-write. No code was changed
to paper over the finding: the Newman collection stayed unedited, and the
monolith's `inventory.mode` default stayed `local`, because resolving it
is S11's job, not S10's. The resolution is unglamorous and correct —
decommission the monolith's local inventory write path entirely, so
`inventory.mode=remote` becomes the only path that exists, closing the
CDC-replica/shared-table hybrid state by removing one of its two writers.
Once `RemoteInventoryClient`'s gRPC path is the sole way `OrderService`
reaches inventory, `GET :8080/api/inventory` returns `404` on the monolith
directly, `scripts/retire-debezium.sh` retires the now-purposeless connector,
and Smell 5 — plus the foreign-key portion of Smell 1 — are marked cured in
`SMELLS.md`, with the evidence trail above as the receipt.

## A green gRPC gate in CI

None of the above is a local-machine ritual, and the committed job is more
modest than CDC's own war story might suggest — which is itself the point.
`.github/workflows/code-ci.yml`'s `inventory-equivalence-gate` job brings up a
plain disposable `postgres:16-alpine` (no logical replication, no `pgoutput`,
no replica identity tweaks — none of that is needed here), plus Kafka, started
only so the monolith's outbox producer has a broker to boot cleanly against,
not because this job asserts anything about the async notification path.
There is no Kafka Connect service and no Debezium connector: CDC was retired
at S11, so the job builds and starts the inventory service (REST `:8084` +
gRPC `:9004`) on its own, self-seeded via its committed `V2__seed_inventory.sql`
Flyway migration, and the monolith as an unconditional gRPC client with no
`inventory.mode` flag left to fall back on. With both services and the
strangler proxy up, the job runs the Smoke, Scenario 1, Scenario 2, Scenario
3, and Inventory Context Contract folders from the same unedited collection
through the proxy, failing the build on any non-zero `newman` exit code.
Before that job was trusted, it was proven red-then-green the same
disciplined way every negative check in this book has been: the compensating
`Release` call was disabled, which left stock decremented after a
forced decline and turned Scenario 3's "3c" assertion red in CI exactly as
expected; then the call was restored and the suite went green again. A CI job
that only ever reports green is not proof of anything; a CI job caught
failing on a deliberate break, then passing once the break is
reverted, is.

> **ADLC in Action** — This extraction ran the identical Frame → Map → Plan →
> Generate → Verify → Operate → Reconcile loop Chapter 17 demonstrated for
> Notification, at its hardest rung yet. Frame fixed eight decisions up front
> in `decisions.md` (DRQ-039 through DRQ-046 — gRPC for the synchronous hot
> path, Debezium-on-Kafka-Connect over Embedded, the sync-reserve/async-replication
> split, Reserve-plus-compensating-Release as a deliberate first taste of
> saga, the foreign-key decomposition, two-phase-plus-idiomatic-gRPC, two-flag
> reversibility, and the extended negative-check discipline). Plan laid out
> sixteen steps across three parallel lanes — the CDC/Connect infrastructure,
> the gRPC contract, and the new service's scaffold — with an Opus validation
> gate named before a line of gRPC or CDC code existed, most pointedly at the
> cutover step where out-of-stock and payment-decline-rollback had to be
> proven across the real service boundary, not asserted against a mock.
> Generate produced the proto, the gRPC server, the CDC consumer, and the
> monolith's compensating client under quarkus-agent and lgtm-quarkus
> tooling, following the `migrate-spring-to-quarkus` process for the lifted
> read surface. Verify is this chapter's negative check and its hybrid-state
> finding, both run against the real services and both included in the
> record, including the one that didn't go the way the plan first assumed. Operate is the two-flag
> cutover `CUTOVER.md` records end to end. Reconcile is `SMELLS.md` marking
> Smell 5 — and the foreign-key portion of Smell 1 — cured, with the
> replication-slot teardown as the closing housekeeping step.

## What you learned

- A **synchronous collaborator that mutates** cannot be extracted the way a
  read-only leaf or an asynchronous consumer can — the call has to cross the
  network before the caller can proceed, and the database that used to
  guarantee atomicity automatically no longer spans both sides of the write.
- A **proto with a distinct wire vocabulary** is a stronger
  anti-corruption layer than one whose field names happen to already agree —
  `stock_keeping_unit`/`on_hand_qty` versus `sku`/`quantityOnHand` forces a
  real translation step to exist on both sides of the seam, curing the
  raw-entity leak Chapter 9 first diagnosed.
- **Change data capture reads the write-ahead log directly** — no poll, no
  table scan — trading a replication slot and a Kafka Connect process you
  now operate for a lower latency floor than a polling relay can offer;
  Chapter 17 chose polling because notification didn't need that floor, and
  explicitly named this chapter as where that budget would be spent instead;
  Chapter 20 later generalizes the same tradeoff.
- **Reserve stays synchronous even after extraction**, because overselling a
  physical unit of stock is a correctness failure no compensating action can
  fully repair — only the *replication* of the owned copy for downstream
  reads is allowed to be asynchronous.
- The **free rollback a shared transaction used to provide is gone the
  instant a decrement commits in another service's database** — a
  **compensating action**, issued explicitly by the caller on every
  post-reservation failure path, is what has to replace it, and `CUTOVER.md`'s
  Scenario 3 evidence (29 in, 29 out, against a monolith copy frozen at a
  different value) is the proof it actually works across a real boundary.
- An **owned database only becomes provably "the one really answering"**
  once a negative check kills the dependency and forces the suite red — and
  this project's own documented hybrid-state finding, left in as evidence
  rather than quietly fixed, shows exactly what happens when the two
  reversibility flags move independently instead of together.

Part 7 picks up the compensation pattern this chapter introduced in its
smallest possible form — one reservation, one compensating call, no
idempotency key, no saga ledger — and builds it out fully. Chapter 23's
choreographed saga for Payment coordinates multiple events across multiple
services with the opposite control style from what you just read here;
Chapter 24 does the same for Shipping through an explicit orchestrator. Both
inherit the exact limitation this chapter's `Release` javadoc names
and leaves open. Chapter 26, extracting Order itself last, is where this
project's CQRS read side is finally built from the event stream these
extractions have been feeding since Chapter 17 — and where the gRPC seam and
the CDC-backfilled, now self-sufficient inventory database this chapter built
become one of several services that read side has to federate.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every artifact cited above is runnable code and dated evidence
already in this repository: `examples/04-inventory-service/` (`inventory.proto`,
`InventoryGrpcServiceImpl`, `InventoryRepository`, `InventoryCdcConsumer`/`InventoryCdcConsumerTest`,
`V1__create_inventory_items_table.sql`/`V2__seed_inventory.sql`, and `MIGRATION.md`'s
measured Phase A→B record), `examples/00-monolith/` (`RemoteInventoryClient`,
`OrderService#placeOrder`/`compensateRemoteReservations`, `OrderItem`'s snapshot
fields, and `SMELLS.md`'s smell 5 and smell 1 entries), `infra/debezium/`
(`inventory-connector.json` and its `README.md`'s retirement record),
`examples/01-strangler-proxy/` (`StranglerProxyRoute.java` and `CUTOVER.md`'s
full inventory-cutover timeline, including the negative check and the
hybrid-state finding), `tooling/newman/mea.postman_collection.json`'s
Inventory Context Contract folder, and `.github/workflows/code-ci.yml`'s
inventory equivalence-gate job. The captured outputs quoted above are taken
verbatim from `CUTOVER.md`, `InventoryCdcConsumerTest`, and `MIGRATION.md`
rather than re-run live here, per this project's own DRQ-025 discipline.
What a reader's own run should confirm independently: the exact RSS/startup
figures in the Phase A→B table will vary by hardware even if the qualitative
reading holds; and the hybrid-state finding's off-by-one race is a timing
result — the number of runs needed to observe it, and its exact margin, may
differ slightly against a reader's own CDC connector lag and database load.*
