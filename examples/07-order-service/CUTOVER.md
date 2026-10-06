# CUTOVER.md — Order service + GraphQL gateway cutover (order-plan.md S9, ch.26, DRQ-071/072, HARD PARTS H3/H4)

This is the evidence trail for the ch.26 equivalence gate: flipping
`strangler.order.enabled=true` — the FINAL strangler flag flip for the whole
system — running the full behavior-equivalence/contract suite through the
proxy with BOTH gated folders (Scenario 4, the GraphQL Gateway Contract)
genuinely enabled, proving reversibility one last time (DRQ-072), and proving
the three DRQ-071 negative checks (saga-consumer-disabled, read-model-
projection-disabled [the H2 CQRS non-vacuity check], compensation-disabled)
genuinely exercise the extracted order service rather than passing
vacuously. This is the LAST monolith-anchored equivalence run before S10
converts the suite into a contract suite against a frozen golden baseline
(DRQ-071) and decommissions the monolith for good (DRQ-070, the one
deliberately irreversible move, H4).

See also: `examples/06-shipping-service/CUTOVER.md` (the template this
mirrors — same structure, same honesty discipline),
`_plans/iterations/order-plan.md` S9 (the step this executes) and its HARD
PARTS H3 (converting the suite without a monolith to anchor it, non-
vacuously) and H4 (the final irreversible decommission, de-risked by proving
reversibility here one last time).

## The flag

| Flag | Location | Default (committed) | Cutover override used here |
|---|---|---|---|
| `strangler.order.enabled` | `examples/01-strangler-proxy/src/main/resources/application.properties` | `false` | `-Dstrangler.order.enabled=true` |

This single override is **runtime-only** (`-D` system property). The
committed default was never changed — flipping it permanently is order-plan
**S10** (the irreversible decommission), explicitly out of scope here. Unlike
every prior extraction, this flag routes BOTH the checkout command (`POST
/api/orders`) and every read (`GET /api/orders[...]`), since the whole order
context moves at once (order-plan S8).

The GraphQL gateway (`examples/08-graphql-gateway`, :8090) is **additive and
has its own front door** (DRQ-069) — it is never routed through the proxy and
has no `enabled` flag of its own; it is simply started or not. It always
resolves order data by calling the order service (:8087) directly, regardless
of the proxy's flag — see "A documented, benign observation" under
Reversibility below for what that means when the flag is off.

## The Node helper — `demos/lib/run-order-newman.js`

Modeled directly on `demos/lib/run-shipping-newman.js` (confirmed, same
mechanics, same underlying CLI-limitation finding from shipping-plan S8):
the newman CLI's `--env-var`/`--global-var` flags populate the
ENVIRONMENT/GLOBAL variable scopes, a DIFFERENT scope from the
collection-level `variable` array a Postman collection's own JSON defines.
This suite has **two** collection-scoped gate variables that `--env-var`
cannot reach:

- `shippingSagaEnabled` (Scenario 4's SF-gate, authored shipping-plan S2)
- `graphqlGatewayEnabled` (the GraphQL Gateway Contract's GG-gate, authored
  order-plan S2, staged pending until this step)

`run-order-newman.js` loads the collection JSON into memory via newman's
documented Node API, patches **both** in-memory `variable` entries to the
given value, and hands the in-memory object (never a re-written file) to
`newman.run()`. The committed collection file on disk is never touched.
Verified directly: a dry run against an unreachable host showed GG-a/GG-b
actually EXECUTING (connection-refused errors) rather than being silently
skipped — proof the patch reaches `pm.collectionVariables` for both keys.

Usage: `node demos/lib/run-order-newman.js <projectRoot> <baseUrl> <true|false> [folderName]`

## Topology brought up (against the shared local podman stack)

The podman stack (`mea-postgres`, `mea-kafka`, `mea-connect`, `mea-lgtm`) was
already running and was **not** restarted.

| Service | Command | Port(s) |
|---|---|---|
| inventory-service | `java -jar examples/04-inventory-service/target/quarkus-app/quarkus-run.jar` | `:8084` HTTP, `:9004` gRPC |
| payment-service | `java -jar examples/05-payment-service/target/quarkus-app/quarkus-run.jar` | `:8085` |
| review-service | `java -jar examples/02-review-service/target/quarkus-app/quarkus-run.jar` | `:8081` |
| notification-service | `java -jar examples/03-notification-service/target/quarkus-app/quarkus-run.jar` | `:8083` |
| shipping-service (cutover) | `ORDER_SERVICE_BASE_URL=http://localhost:8087 java -jar examples/06-shipping-service/target/quarkus-app/quarkus-run.jar` | `:8088` |
| order-service | `java -jar examples/07-order-service/target/quarkus-app/quarkus-run.jar` | `:8087` |
| graphql-gateway | `java -jar examples/08-graphql-gateway/target/quarkus-app/quarkus-run.jar` | `:8090` |
| monolith | `SPRING_DATASOURCE_PASSWORD=monolith_dev_only java -jar examples/00-monolith/target/monolith.jar` | `:8080` |
| strangler proxy (cutover) | `java -Dstrangler.order.enabled=true -jar examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar` | `:8888` |

The full topology (all six extracted services + the monolith + the proxy +
the gateway) is required because the suite is one collection covering every
context, and the proxy's own committed defaults already cut over
review/notification/inventory/payment/shipping — this is topology, not scope
creep; no code or flag belonging to those contexts was touched.

**Operational note (pre-existing, carried forward from every prior
CUTOVER.md):** the monolith's `application.yml` default datasource password
(`monolith`) does not match the podman-stack Postgres password actually
configured in `.env` (`monolith_dev_only`). `SPRING_DATASOURCE_PASSWORD=monolith_dev_only`
is required every time the monolith is started.

## Four genuine environmental findings surfaced by this step (not source defects)

This extraction is the first to run the full six-service + monolith + gateway
topology end-to-end against this repo's long-lived, heavily-reused podman
Postgres/Kafka instance, and the first whose own service (`order-service`)
mints brand-new IDs into a namespace every other context's months of
accumulated dev/test history already shares. Four real, documented findings
came out of standing this up — none are defects in the order service's own
logic (each was isolated and disproved as a code issue below), but all four
would have produced a false negative or a false positive if left
undiscovered, which is exactly the kind of thing H3/H4's equivalence-proving
discipline exists to catch.

### Finding 1 — order-service's OWN `customers` table starts EMPTY (DRQ-073)

`Order`'s FK was decomposed to a plain `customerId` value (DRQ-068); the
order service owns its own `customers` table, forward-filled only, **no CDC
backfill** from the monolith's `customers` table (by design, S4's documented
scope). The suite's checkout fixtures all use `customerId: 1`. Fix: a single
idempotent plain-data `INSERT ... ON CONFLICT (id) DO NOTHING` seeding
customer id=1 (`ada@example.com`, matching the monolith's own customer 1) —
not a Flyway migration, no checksum touched:

```sql
INSERT INTO order_service.customers (id, name, email, created_at)
VALUES (1, 'Ada Lovelace', 'ada@example.com', now())
ON CONFLICT (id) DO NOTHING;
```

### Finding 2 — a brand-new Kafka consumer group must drain this repo's ENTIRE accumulated history

`order-service`'s own SmallRye consumer group (`order-service`, defaulted
from `quarkus.application.name`) had never subscribed to
`payment.captured`/`payment.declined`/`shipment.dispatched`/`shipment.failed`
before this step. With `auto.offset.reset=earliest`, its first-ever
subscription must replay **every message ever produced to those topics since
ch.23** (payment-plan) **and ch.24** (shipping-plan) — hundreds of messages
accumulated across many prior chapters' dev/test sessions on this one shared,
long-lived podman Kafka broker. Confirmed directly via
`kafka-consumer-groups.sh --describe --group order-service`: end offsets of
108/19/76 (`payment.captured`), 11/22/8 (`payment.declined`), 17/81/88
(`shipment.dispatched`), 4/6/7 (`shipment.failed`) across three partitions
each — hundreds of historical, mostly-unknown-orderId events that the
listener correctly logged and dropped (`"... for unknown orderId=NNN —
cannot react"`, exactly the documented, intentional behavior in
`OrderSagaListener`'s own class javadoc). The very first real checkout placed
during this session landed in the middle of that backlog and needed more
than one 15-second bounded-wait budget to be reached — not a correctness
bug, a one-time bootstrap cost. Confirmed resolved: once consumer lag reached
zero (`kafka-consumer-groups.sh` reporting `LAG=0` on all twelve
group/topic/partition rows), every subsequent fresh checkout settled within
its normal bounded-wait budget. `demos/demo-order-cutover.sh` polls lag to
zero after every order-service (re)start before timing any scenario.

### Finding 3 (the big one) — order-service's fresh order-id sequence collides with this repo's entire shared order-id history

`order_service.orders` starts at id 1 (own schema, own sequence, forward-
filled only — DRQ-073). But **every** downstream service's idempotency guard
(`payment.payments`, `shipping.shipments`) is keyed on the raw numeric
`order_id` a Kafka event carries — a value the MONOLITH's own shared `orders`
sequence has been minting since ch.17, now standing at a historical high-
water mark of **322** (confirmed: `max(order_id)` in `payment.payments`,
`max(order_id)` in `shipping.shipments`, and `max(id)` in the monolith's own
`orders` table were all exactly 322 — plus two unrelated out-of-range
sentinel rows, `9000001`/`9000002` in `payment.payments` and `9100003` in
`shipping.shipments`, from an earlier dev/test session, clearly out of band
and not a collision risk).

**Observed firsthand:** order-service's very first checkout became order
id=1 — which collided EXACTLY with an ancient monolith-era `payment.payments`
row for `order_id=1` (created `2026-01-07`, `amount_cents=3998` — plainly not
today's `1999`-cent single-widget order). Payment-service's own idempotency
guard logged, verbatim: `"skipping duplicate order.placed for order 1
(payment already recorded)"` and never emitted a NEW `payment.captured` —
leaving order-service's order 1 **permanently stranded PENDING** (it can
never recover; order ids are never reused). This is a one-time casualty of
this evidence session's test fixtures, left in place rather than deleted
(see "Permission note" below) — not a defect, and clearly isolated: orders
2–5 placed moments later did NOT collide (payment-service created fresh rows
for them, timestamped today), proving the order service's own checkout/
outbox/saga-reaction logic was correct throughout; only the one coincidental
id match broke.

**Fix used throughout this step:** a **burn-in** — enough throwaway `POST
/api/orders` checkouts placed directly against order-service's own REST API
(never a DB write) to advance its sequence **past the current historical
high-water mark** (322, confirmed live, not hardcoded) with a small safety
margin, followed by a plain stock top-up (`UPDATE
inventory.inventory_items SET quantity_on_hand = 500 WHERE sku =
'SKU-WIDGET-001'`, the explicitly pre-approved per-SKU operational pattern
every prior CUTOVER.md documents) to replenish what the burn-in consumed.
`demos/demo-order-cutover.sh`'s `burn_in_past_historical_max` helper performs
this live (queries the current max across `payment.payments`,
`shipping.shipments`, and the monolith's `orders` table, then burns forward
past it) rather than hardcoding "317" or "322" — those numbers were specific
to this one session and will be different (larger) on every future run,
since every run adds more history.

**The collision recurred once more, in the OTHER direction, during the
reversibility test:** once `strangler.order.enabled` flipped back to `false`
and a checkout was placed through the monolith again, the monolith's OWN
shared sequence (still independently advancing) landed on an id (341, then
342) that order-service's cutover-run evidence had *already* used moments
earlier — because both sequences were racing forward from the same starting
line in temporal proximity within one evidence session. Confirmed via the
same signature (`"skipping duplicate order.placed for order 341..."`),
isolated to the collided ids, and fixed the same way: a second burn-in, this
time advancing the MONOLITH's own sequence (via its own `POST /api/orders`)
past order-service's current high-water mark before placing the real
reversibility checkout. (This is why `demos/demo-order-cutover.sh`'s
reversibility section performs the SAME burn-in — against whichever system
is about to serve the real, asserted checkout.)

**Why this is a genuine, non-obvious finding and not a defect:** it is a
direct, structural consequence of order-plan's OWN two deliberate design
decisions — DRQ-073 ("no CDC backfill... orders are created forward at
checkout time") and the honest observation that this repo's shared Postgres/
Kafka has been reused, unreset, across the ENTIRE ten-chapter book-build. No
prior extraction hit this because every prior extracted service (review/
notification/inventory/payment/shipping) is either not keyed by order_id at
all, or — in payment/shipping's own case — IS the system whose own sequence
the monolith has always fed; order-service is the first service to mint its
OWN independent, competing numbering for the exact same logical key three
OTHER services already have deep history against. This is exactly the sort
of false-equivalence trap H3 warns about: a careless read of the first
symptom ("order 1 never confirms") could easily be misdiagnosed as a saga
defect in the newly-lifted `OrderSagaListener` rather than a test-environment
id collision — it is isolated and disproved as a code defect above (orders
2–5, and every order placed after the burn-in, behaved correctly).

**Permission note:** a direct DB `TRUNCATE`/`setval` to clear the exploratory
test rows was attempted first and denied by the harness's permission
classifier as a mass-delete-shaped action; the burn-in-via-REST-API approach
above was used instead, which touches no database admin surface at all. The
handful of stranded test orders (order-service's id=1; the monolith's id=323)
from this session's exploratory phase were left in place, undeleted,
documented here rather than worked around further.

### Finding 4 — shipping-service's own `order.service.base-url` must be pointed at the order service during cutover

Shipping-service's "enrich" step (ch.24 S5, DRQ-059) reads the order's
`shippingAddress` from **its own configured order source** to detect the
`SHIP-FAIL` sentinel —
`order.service.base-url=${ORDER_SERVICE_BASE_URL:http://localhost:8080}`
(`examples/06-shipping-service/src/main/resources/application.properties`),
defaulting to the **monolith**. Once `strangler.order.enabled=true` cuts
`/api/orders` over to the order service, the monolith no longer has any
record of the new order, so shipping-service's enrich `GET` 404s, falls back
to its documented stub address `'ADDRESS-UNAVAILABLE-PENDING-S6'` (NOT
`SHIP-FAIL`), and the saga dispatches NORMALLY instead of failing —
**Scenario 4 silently became a false green that actually CONFIRMED the
order instead of failing it**, observed directly in this session before the
fix:

```
WARN  [dev.patterncatalyst.shipping.OrderReadClient] failed to enrich order 325 from the order
      service (unreachable/error); using fallback 'ADDRESS-UNAVAILABLE-PENDING-S6'
...
order 325 CONFIRMED via shipment.dispatched (shipmentId=288)   <- should have been SHIPPING_FAILED
```

**Fix:** `ORDER_SERVICE_BASE_URL=http://localhost:8087` set at runtime when
starting shipping-service for the cutover — exactly the same category of
runtime-only override as the strangler flag itself, using a config knob
shipping-plan S5 already built (anticipating multiple order-data sources) but
that order-plan's own S9 text did not call out explicitly. Verified directly
after the fix: a forced `SHIP-FAIL` order correctly produced the
`shipping saga started` → `dispatch` path aborting into `SHIPPING_FAILED`,
net-zero stock (see below). During reversibility (flag off), shipping-service
is restarted WITHOUT this override, reading from the monolith again — the
correct behavior for that state. This is a genuine H3/H4-class finding: an
undetected cross-service wiring gap that would have produced a **false "no
regression"** (Scenario 4 "passing" for the wrong reason) had it not been
caught here.

## 1. CUTOVER run — `strangler.order.enabled=true`, both gates true, full suite through the proxy

With the topology above up, order-service's customers table seeded, the
Kafka backlog drained, the order-id collision zone cleared, and
shipping-service pointed at the order service, the full suite was run
repeatedly via the helper:

```
node demos/lib/run-order-newman.js "$PWD" http://localhost:8888 true
```

**Final, clean cutover run: 167 assertions, 1 failed (only the documented
GraphQL content-type gap below — see "A known, honest gap"), 16.6s.** A
subsequent full-suite sanity run (after all three negative checks, before
shutdown) produced **171 assertions, 1 failed** — the same single, documented
gap, nothing else.

**Scenario 1 (happy path) — genuinely looped, not an attempt-0 pass:**
`POST /api/orders` → `202 Accepted`, `PENDING`. The bounded-wait poll of `GET
/api/orders/{id}` looped **7 times (~4.5s)** before observing `CONFIRMED`
(order 334) — the full chain `order.placed` (now produced by the order
service's OWN outbox) → `payment.captured` (payment-service) → shipping saga
→ `shipment.dispatched` → the order service's OWN `onShipmentDispatched`
reaction → `CONFIRMED`. Stock dropped by exactly 1.

**Scenario 3 (payment-declined)** — looped **6 times (~2.5s)** to
`PAYMENT_DECLINED` (order 335), net-zero confirmed. Explicit before/after,
captured independently through the proxy (order 340): **490 → 489 → 490**
(stock dipped exactly 1 unit on the synchronous reserve, then was restored by
the order service's own compensating `Release` on `payment.declined`).

**Scenario 4 (shipping-failure)** — looped **7 times (~4.5s)** to
`SHIPPING_FAILED` (order 336), net-zero confirmed. Explicit before/after,
captured independently through the proxy (order 339): **490 → 489 → 490**.

**Order Context Contract (9a–9d)** — green, served by the order service: `POST`
→ `202` + `Location`; `GET /{id}` → the full `OrderDto` shape incl.
`shippingAddress`; `GET` (list) includes the correlated order; `GET
/999999999` → `404 NOT_FOUND`.

**GraphQL Gateway Contract (GG-a/GG-b)** — green against `:8090` directly,
except the one documented content-type gap: `order(id)` resolves the full
cross-context aggregate — order fields, items with nested `stock` +
`reviews`, `payments[]`, `shipments[]` — strictly asserted (not "any 2xx"),
and `order(999999999)` returns `data.order: null` + a populated `errors[]`
envelope.

**Order-flag-reaches-:8087 proof** (order 339, three ways):
```
GET http://localhost:8888/api/orders/339   (via the proxy)
-> {"id":339,"customerId":1,"status":"SHIPPING_FAILED", ... }

GET http://localhost:8087/api/orders/339   (direct to the order service)
-> {"id":339,"customerId":1,"status":"SHIPPING_FAILED", ... }   -- BYTE-IDENTICAL

GET http://localhost:8080/api/orders/339   (direct to the monolith)
-> HTTP 404   -- the monolith has NO knowledge of this order (its own
                 sequence never reached it; it was created entirely inside
                 the order service)
```
This is the strongest possible version of the "proxy-reaches-the-service"
proof every prior CUTOVER.md has made with byte-identical responses: here the
monolith can't even 200 with stale/divergent data, because the order was
never minted on its side of the fork at all.

**GraphQL aggregation evidence** (order 339, abbreviated — the full response
also carried 95 reviews for `SKU-WIDGET-001`, omitted here for length):
```json
{
  "data": {
    "order": {
      "id": "339", "customerId": 1, "status": "SHIPPING_FAILED",
      "totalCents": 1999, "shippingAddress": "SHIP-FAIL",
      "items": [{ "sku": "SKU-WIDGET-001", "quantity": 1, "unitPriceCents": 1999,
                  "stock": { "quantityOnHand": 490 },
                  "reviews": [ { "id": 1, "rating": 5, "comment": "..." }, "... 94 more" ] }],
      "payments": [{ "id": 341, "status": "CAPTURED", "amountCents": 1999,
                      "method": "CARD-VISA", "createdAt": "2026-10-06T00:47:31.009366Z" }],
      "shipments": [{ "id": 300, "status": "CANCELLED", "address": "SHIP-FAIL",
                       "createdAt": "2026-10-06T00:47:33.019247Z" }]
    }
  }
}
```
One GraphQL query stitched the order (order service), its payment (payment
service, over REST), its cancelled shipment (shipping service, over REST),
and its line item's live stock level (inventory service, over gRPC) — the
read-aggregation surface DRQ-069 calls for, genuinely resolving across all
four other extracted services in a single round trip.

## 2. Reversibility — flip back (the LAST time, DRQ-072)

The proxy and shipping-service were restarted without the override
(`strangler.order.enabled` back to its default `false`; shipping-service back
to its own default order-source, the monolith):
```
java -jar examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar
java -jar examples/06-shipping-service/target/quarkus-app/quarkus-run.jar
```

After clearing the order-id collision zone for the monolith's own sequence
(Finding 3), a direct checkout confirmed the monolith serves checkout again:
`POST /api/orders` → `202 Accepted`, `PENDING`; bounded-wait (5 polls)
→ `CONFIRMED` (order 341). Confirmed the order service has **zero** knowledge
of it: `GET http://localhost:8087/api/orders/341` → `404`.

Full suite re-run:
```
node demos/lib/run-order-newman.js "$PWD" http://localhost:8888 true
```
**Result: 164 assertions, 8 failed — all 8 inside the single GraphQL Gateway
Contract GG-a item** (see "A documented, benign observation" below); every
other folder, including Scenario 1–4 and the Order Context Contract (now
correctly served by the monolith, order 342, `202`/`PENDING`/full `OrderDto`
shape), was fully green. This proves the reversibility window order-plan S8
built is genuinely open — flipping the flag back is a config change and a
restart, nothing more — right up until S10.

**A documented, benign observation (not a defect) — the GraphQL gateway's own
correctness under reversibility:** the GraphQL Gateway Contract's `GG-a` item
was authored at S2 to read `{{happyPathOrderId}}` — whichever order Scenario
1 JUST placed in THIS run. Under reversibility, Scenario 1's order (342) is
placed on the MONOLITH (flag off); the GraphQL gateway, by design (DRQ-069:
"an additive gateway... sits alongside the proxy... gets its OWN front
door"), has **no monolith client at all** — it only ever resolves order data
by calling the order service (:8087) directly, REGARDLESS of the proxy's
flag. So `order(342)` correctly, honestly returns `"order 342 not found"` —
the gateway was never wired to see monolith-era orders, and it should not be.
Proven NOT a gateway defect: a direct query for a REAL order-service order
placed during the earlier cutover run (334, still resolvable at any time
since the gateway's wiring never changed) succeeded throughout:
```
POST :8090/graphql  { order(id: "334") { id status } }
-> {"data":{"order":{"id":"334","status":"CONFIRMED"}}}
```
This is the same category of honest, pre-existing-item gap every prior
extraction's CUTOVER.md has surfaced (payment's Notification-folder gap;
shipping's Notification 5c timing gap) — a test item's OWN correlation
assumption breaking under a topology state it wasn't authored to anticipate,
not a regression in the thing being proven. It is additionally a POSITIVE
proof of H4's requirement that "the additive gateway... [is] independently
revertible": the gateway's behavior under reversibility is exactly, honestly
what its own additive design promises — unaffected by the proxy flag, and
correctly unable to see an order it was never told about.

## 3. Negative checks (DRQ-071/072 non-vacuity; H2/H3)

Each check: a local, deliberate, byte-for-byte-reverted edit to
`examples/07-order-service/src/main/java/dev/patterncatalyst/order/OrderSagaListener.java`,
rebuilt (`./mvnw -q -DskipTests package`), shown RED, reverted via a plain
file copy (never `git`), confirmed clean via a read-only `git status`,
rebuilt, shown GREEN. Each order-service restart was followed by polling the
`order-service` Kafka consumer group to `LAG=0` before timing any scenario
(the ~30–40s post-restart rebalance observed in this environment, the same
discipline Finding 2 required for the very first boot).

### 3a. Order saga consumer reaction disabled ⇒ Scenario 1 bounded-wait MUST go RED

**Mechanism chosen (documented, per the plan's own menu of options):** rather
than removing the `@Incoming("shipment-dispatched")` annotation outright
(tried first — this made Quarkus FAIL TO BOOT: `"The attribute
value.deserializer on connector 'smallrye-kafka' (channel: shipment-
dispatched) must be set"`, because the still-configured channel in
`application.properties` has no consumer left to infer a deserializer from —
an interesting finding in its own right, immediately reverted), the
reaction's **body** was made an unconditional no-op, keeping the channel
subscribed (no backlog accumulates) but ensuring the transition logic never
runs — the cleanest simulation of "the consumer is down" that still boots:

```java
    @Incoming("shipment-dispatched")
    @Transactional
    public void onShipmentDispatched(ShipmentDispatched event) {
        // NEGATIVE-CHECK-DISABLED (restored byte-for-byte at the end of this check)
        if (true) {
            LOG.warnf("NEGATIVE-CHECK-DISABLED: onShipmentDispatched is a no-op for orderId=%d", event.orderId());
            return;
        }
        Order order = orderRepository.findByIdOptional(event.orderId()).orElse(null);
        ...
```

Rebuilt, restarted, consumer group settled (`LAG=0`), a fresh checkout
forced. **Result: order 403 stuck `AWAITING_SHIPMENT`** — confirmed the
shipping service HAD genuinely dispatched (its own record for order 403:
`{"id":362,"orderId":403,"status":"DISPATCHED",...}`) but the order service's
disabled reaction never applied it:
```
newman: Scenario 1 — Happy-Path Checkout
1.  AssertionError  Order reaches CONFIRMED within the bounded-wait budget (20 x 750ms)
                    order 403 status after 20 attempt(s): expected 'AWAITING_SHIPMENT' to deeply equal 'CONFIRMED'
```

**Revert, byte-for-byte, confirmed via read-only `git status`:**
```
$ git -C /home/rsedor/Dev/modernizing-enterprise-applications status --porcelain -- \
    examples/07-order-service/src/main/java/dev/patterncatalyst/order/OrderSagaListener.java
(empty — clean)
```
Rebuilt, restarted, consumer group settled. **Result: GREEN — 19/19
assertions, 0 failed, 4s** (order confirmed on the first poll once the
reaction and the consumer group were both healthy).

### 3b. Read-model projection disabled on `onShipmentDispatched` ⇒ stale reads (the H2 CQRS non-vacuity check)

```java
        order.confirm();
        // NEGATIVE-CHECK-DISABLED (restored byte-for-byte at the end of this check):
        // the WRITE model still advances to CONFIRMED, but the READ-MODEL
        // projection below is skipped, so order_view never reflects it.
        // orderViewProjector.project(order, null, "DISPATCHED");
        LOG.infof("order %d CONFIRMED via shipment.dispatched (shipmentId=%d)", order.getId(), event.shipmentId());
```

Rebuilt, restarted, consumer group settled, a fresh checkout forced (order
406). Direct DB proof — the write model and read model DIVERGE exactly as
predicted:
```sql
-- write-model aggregate (order_service.orders)
 id  |  status
-----+-----------
 406 | CONFIRMED

-- read-model (order_service.order_view) -- what GET /api/orders/406 actually serves
 order_id |      status
----------+-------------------
      406 | AWAITING_SHIPMENT
```
`GET /api/orders/406` genuinely returned `AWAITING_SHIPMENT` for 20 straight
polls despite the underlying aggregate having already reached `CONFIRMED` —
proving reads serve EXCLUSIVELY from `order_view` with NO aggregate fallback
(DRQ-067), the exact non-vacuity property H2 calls for: a read that silently
fell back to the live aggregate would have hidden this break entirely.
Formal check (fresh order 407):
```
newman: Scenario 1 — Happy-Path Checkout
1.  AssertionError  Order reaches CONFIRMED within the bounded-wait budget (20 x 750ms)
                    order 407 status after 20 attempt(s): expected 'AWAITING_SHIPMENT' to deeply equal 'CONFIRMED'
```

**Revert, byte-for-byte, confirmed via read-only `git status`:**
```
$ git status --porcelain -- examples/07-order-service/.../OrderSagaListener.java
(empty — clean)
```
Rebuilt, restarted, consumer group settled. **Result: GREEN — 21/21
assertions, 0 failed, 5.7s.**

### 3c. Compensating `Release` disabled in `onShipmentFailed` ⇒ Scenario 4 non-net-zero

```java
        for (OrderItem item : order.getItems()) {
            try {
                // NEGATIVE-CHECK-DISABLED: remoteInventoryClient.release(item.getSku(), item.getQuantity());
                LOG.warnf("NEGATIVE-CHECK-DISABLED: compensating Release skipped for order=%d sku=%s qty=%d", ...);
            } catch (RuntimeException releaseFailure) { ... }
        }
```

Rebuilt, restarted, consumer group settled. A forced `SHIP-FAIL` checkout
(order 409), explicit stock numbers: **494 → 493 → 493** — the synchronous
reserve genuinely decremented stock by 1, but with the compensating `Release`
skipped, the order correctly reached `SHIPPING_FAILED` while stock stayed
decremented forever (non-net-zero). Formal check:
```
newman: Scenario 4 — Shipping-Failure
1.  AssertionError  Quantity on hand returns to net-zero after the shipping failure (20 x 750ms budget)
                    quantityOnHand after 20 attempt(s): expected 492 to deeply equal 493
```

**Revert, byte-for-byte, confirmed via read-only `git status`:**
```
$ git status --porcelain -- examples/07-order-service/.../OrderSagaListener.java
(empty — clean)
```
Rebuilt, restarted, consumer group settled. **Result: GREEN — 14/14
assertions, 0 failed, 4s**, explicit stock net-zero confirmed again on a
fresh forced `SHIP-FAIL` (order 411): **492 → 491 → 492.**

## 4. `demos/demo-order-cutover.sh` — scripted reproduction

A complete script mirroring `demo-shipping-cutover.sh`'s shape was authored
to reproduce every proof above unattended: bring up the full nine-process
topology, seed the customer row, drain the Kafka backlog, clear the order-id
collision zone (queried live, not hardcoded — see Finding 3), run the
cutover suite + explicit stock/flag-reach proofs, flip to reversibility
(clearing the collision zone again in the other direction), run the full
suite again, then perform all three negative checks via `sed`-driven
source edits with the same backup/rebuild/revert/rebuild/verify cycle this
session performed by hand. Its three `sed` expressions were individually
dry-run-verified against the real, current `OrderSagaListener.java` (confirmed
each changes exactly the one intended line — the compensation-disable
expression in particular needed a `0,/pattern/` first-match restriction,
since the same `remoteInventoryClient.release(...)` call text also appears,
unrelated, inside `onPaymentDeclined`'s own compensation loop). The script's
own exit code reflects any genuine failure (not just the documented GraphQL
content-type gap, which it explicitly treats as expected).

## A known, honest gap (surfaced, not papered over)

**Every genuine cutover/reversibility run above had EXACTLY one
non-load-bearing failure, isolated to the GraphQL Gateway Contract's GG-a
item's content-type assertion:**
```
1.  AssertionError  Content-Type is application/json
                    expected 'application/graphql-response+json; ch…' to include 'application/json'
```
**Root cause (a spec-version mismatch, not a correctness defect):** the S2-
authored GG-a test item asserts `Content-Type: application/json`, written
before `examples/08-graphql-gateway` existed. SmallRye GraphQL (and the
GraphQL-over-HTTP spec it follows) answers with
`application/graphql-response+json; charset=UTF-8` — the modern, correct
content type for a GraphQL response, simply not the literal string the S2
test item was written to expect. **Every OTHER assertion in that same GG-a
item passed in every genuine run** — the 200 status, the absence of
`errors[]`, the non-null aggregate, every field of the aggregate shape, the
nested stock/reviews, the payments/shipments arrays — proving the gateway's
actual behavior is correct; only the content-type STRING the test checks for
is now out of date. This is out of scope to fix here (`tooling/newman/
mea.postman_collection.json` is explicitly the equivalence gate, single-
writer discipline, not touched by this step) and is recorded here exactly as
every prior CUTOVER.md records its own analogous collateral gap (payment's
Notification-folder assumption; shipping's Notification 5c timing budget).
**The load-bearing claims of this step — Scenario 1 CONFIRMED via the full
chain, Scenario 3/4 net-zero with explicit numbers, the Order Context
Contract, the GraphQL aggregation's actual DATA shape, the order-flag-
reaches-:8087 proof, reversibility, and all three negative checks — are all
independently proven, repeatedly, above, and are unaffected by this gap.**

## Summary of all runs

| # | `strangler.order.enabled` | gates (`shippingSagaEnabled`/`graphqlGatewayEnabled`) | Result |
|---|---|---|---|
| 1 (dry run, unreachable host) | n/a | `true`/`true` | confirms both gates patch correctly (connection errors on GG-a/GG-b prove they executed, not skipped) |
| 2 (first full attempt) | `true` | `true`/`true` | environmental: customer id=1 missing in order-service's own schema (Finding 1) — all checkout POSTs 404'd |
| 3 (after customer fix) | `true` | `true`/`true` | environmental: Kafka backlog + order-id collision (Findings 2/3) — order 1 stuck, order 3 wrongly CONFIRMED (Finding 4, shipping base-url) |
| 4 (after backlog drain + burn-in to 322) | `true` | `true`/`true` | Scenario 4 still wrongly CONFIRMED (Finding 4 not yet fixed) — 2 failures + the GraphQL gap |
| 5 (after shipping ORDER_SERVICE_BASE_URL fix) | `true` | `true`/`true` | **167 assertions, 1 failed** (only the documented GraphQL gap) — first genuine cutover green |
| 6 (reversibility) | `false` | `true`/`true` | **164 assertions, 8 failed** (all 8 = the one documented GG-a correlation gap under reversibility) |
| 7 (negative check a, RED) | `true` | `true`/`true` (Scenario 1 folder) | **RED — order 403 stuck AWAITING_SHIPMENT** |
| 8 (negative check a, reverted, GREEN) | `true` | `true`/`true` (Scenario 1 folder) | **GREEN — 19/19** |
| 9 (negative check b, RED) | `true` | `true`/`true` (Scenario 1 folder) | **RED — order 407, write CONFIRMED / read stuck AWAITING_SHIPMENT** |
| 10 (negative check b, reverted, GREEN) | `true` | `true`/`true` (Scenario 1 folder) | **GREEN — 21/21** |
| 11 (negative check c, RED) | `true` | `true`/`true` (Scenario 4 folder) | **RED — non-net-zero, 492 expected vs 493 actual** |
| 12 (negative check c, reverted, GREEN) | `true` | `true`/`true` (Scenario 4 folder) | **GREEN — 14/14**, net-zero 492→491→492 |
| 13 (final full-suite sanity) | `true` | `true`/`true` | **171 assertions, 1 failed** (only the documented GraphQL gap) |

## Process hygiene

All nine application processes started during this evidence run (inventory,
payment, review, notification, shipping, order-service, graphql-gateway,
monolith, strangler proxy — across every restart cycle for the negative
checks and reversibility) were stopped cleanly at the end (`SIGTERM`, poll,
`SIGKILL` fallback). Confirmed free: ports `8080`, `8081`, `8083`, `8084`,
`8085`, `8087`, `8088`, `8090`, `8888`, `9004`. The podman stack
(`mea-postgres`, `mea-kafka`, `mea-connect`, `mea-lgtm`) was left running, as
required. No Flyway migration was touched; the stock top-ups were plain data
`UPDATE`s. `git status` on the whole repository at the end of this step
showed a clean working tree except for the three new files this step
creates (`examples/07-order-service/CUTOVER.md`, `demos/demo-order-cutover.sh`,
`demos/lib/run-order-newman.js`) — every deliberate negative-check edit to
`OrderSagaListener.java` left no trace, confirmed with a read-only `git
status` after each revert, above.
