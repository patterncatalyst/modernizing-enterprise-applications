# The Behavior-Equivalence Suite

This directory holds the project's **behavior-equivalence suite** — a Newman
(Postman) contract collection captured against the running Spring Boot
monolith (`examples/00-monolith/`) that asserts the monolith's
externally-observable HTTP contract: status codes, response-body fields, and
content types. It does **not** inspect internal database state directly (it
only observes effects through the same REST surface a real client would use).

This is the project's **equivalence gate** (build-plan.md §G, decisions.md
DRQ-014, DRQ-031): the load-bearing rule is that **an extracted service is
"done" only when it passes this exact collection, unchanged**, against its
own `baseUrl`. The collection is authored once here in r02 (step S6) and is
re-run — never re-written — against each later extraction (Review in r02/ch.15,
then Notification, Inventory, Payment, Shipping, Order+gateway in r04–r07).

> Terminology note: this suite is called the "behavior-equivalence suite" and
> its CI check the "equivalence gate" — not the deprecated pre-DRQ-031 term.

## Files

| File | Purpose |
|---|---|
| `mea.postman_collection.json` | The collection itself. Every request is parameterized by `{{baseUrl}}` so it can target any service without modification. |
| `local.postman_environment.json` | Points `{{baseUrl}}` at the monolith baseline, `http://localhost:8080`. Used for the S6 baseline run. |
| `review-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Review service (`examples/15-review-service/`, arrives in S8+), on its own port so it can run side-by-side with the monolith during the strangler cutover. Only the "Review Context Contract" folder is meaningful against this target until Order/Inventory/Payment are themselves extracted. |
| `notification-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Notification service (`examples/03-notification-service/`, arrives in notification-plan S4+), on its own port (`:8083`). Only the "Notification Context Contract" folder is meaningful against this target. |
| `inventory-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Inventory service (`examples/04-inventory-service/`, arrives in inventory-plan S5+), on its own port (`:8084`). Only the "Inventory Context Contract" folder is meaningful against this target until the order->inventory gRPC seam (inventory-plan S6/S7) and the proxy's cutover (S9/S10) are wired — see the scenario note below. |
| `payment-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Payment service (`examples/05-payment-service/`, arrives in payment-plan S4+), on its own port (`:8085`). Nothing in the collection targets this `baseUrl` directly yet — a dedicated Payment Context Contract folder is deferred to payment-plan S4/S5 (scope discipline). What payment-plan S2 *does* add is the async-tolerant checkout folders below (Scenario 1/3), which continue to run against the monolith/proxy `baseUrl` and only become meaningfully async once S6+ wires the choreography through this service. |
| `../../demos/demo-equivalence.sh` | Thin runner: `demos/demo-equivalence.sh [baseUrl]`. Defaults to the monolith baseline. |

## Scenarios asserted

1. **Smoke** — the target is reachable and serves the seeded inventory.
2. **Happy-path checkout** (payment-plan.md S2, ch.23, DRQ-047/DRQ-055 —
   **ADAPTED for the forthcoming ASYNC checkout contract**) — `POST
   /api/orders` with an in-stock line item is asserted tolerant of **`201
   Created`** (today's synchronous monolith — payment captured in-process,
   the response already carries `status: "CONFIRMED"`) **or `202 Accepted`**
   (the future choreographed-saga payment service, examples/05-payment-service
   — payment hasn't happened yet, the response carries `status: "PENDING"`);
   either way the `Location` header and the total/line items (computed from
   the still-synchronous inventory reserve, before payment) are asserted
   unconditionally. A follow-up **BOUNDED-WAIT poll** of `GET
   /api/orders/{id}` then strictly asserts the order reaches the terminal
   `status: "CONFIRMED"` — immediately on attempt 0 against the synchronous
   monolith (it is already terminal), after the `payment.captured` event is
   consumed against the future saga — and inventory `quantityOnHand` drops by
   exactly the ordered quantity (unchanged, strict, both transports). Only the
   **transport** of the terminal-status observation is relaxed (synchronous
   response field vs. bounded-wait poll); the terminal status itself and the
   stock-decrement side effect are never weakened, and a bounded-wait that
   exhausts its budget without reaching `CONFIRMED` goes **RED**, not green.
3. **Out-of-stock** — **left untouched, fully synchronous** (payment-plan.md
   DRQ-047: inventory Reserve stays a synchronous gRPC call at checkout time
   even after the payment extraction — only the payment outcome goes async).
   Ordering more than `quantityOnHand` returns `409 Conflict` with the
   documented error body
   `{ "error": "OUT_OF_STOCK", "message": "...<sku>...", "timestamp": ... }`,
   and inventory is confirmed unchanged afterward (the reservation is rejected
   before any write).
4. **Payment-declined** (payment-plan.md S2, ch.23, DRQ-047/DRQ-049/DRQ-055 —
   **ADAPTED, the crux scenario**) — a checkout whose `paymentMethod` contains
   `DECLINE` is asserted tolerant of **`402 Payment Required`** (today's
   synchronous monolith — `OrderService#placeOrder`'s catch block issues the
   compensating gRPC `Release` in-line, synchronously, BEFORE re-throwing, so
   the terminal decline AND the stock restoration are both already known at
   POST time; the whole checkout `@Transactional` rolls back, so no order
   resource is ever persisted to poll) **or `202 Accepted`** (the future
   choreographed saga — the order persists `PENDING`, the decline arrives
   later as a `payment.declined` event). On the `402` path the documented
   error body (`{ "error": "PAYMENT_DECLINED", "message": "...",
   "timestamp": ... }`) is asserted exactly as before, unchanged. On the
   `202` path a **BOUNDED-WAIT poll** of `GET /api/orders/{id}` strictly
   asserts the order reaches the terminal `status: "PAYMENT_DECLINED"`. Either
   way, a second **BOUNDED-WAIT poll** of `GET /api/inventory/{sku}` strictly
   asserts stock returns to **net-zero** — this is the behavior worth
   protecting across the extraction: the reservation is restored, whether by
   the monolith's in-line compensating `Release` today (SMELL[ch.22]) or by
   the `payment.declined` choreography reaction later (DRQ-049). Only the
   **transport** is relaxed (`402` vs `202` at POST, synchronous vs
   bounded-wait observation of the terminal state); the terminal
   `PAYMENT_DECLINED` status and the net-zero stock assertion are never
   weakened — a bounded-wait that exhausts its budget without reaching the
   terminal state goes **RED**.
5. **Review context contract** (the first extraction's surface, ch.15):
   - `GET /api/reviews?sku=...` -> `200`, non-empty array, each item shaped
     `{ id, customerId, sku, rating (1-5), comment, createdAt }`.
   - `GET /api/reviews/{id}` -> `200` with the same shape.
   - `POST /api/reviews` with no credentials -> `401`.
   - `POST /api/reviews` with HTTP Basic `demo-customer`/`demo-pass` -> `201
     Created` echoing the submitted fields, with a `Location` header.
   - `POST /api/reviews` (authenticated) with an out-of-range `rating` -> `400`
     with `{ "error": "VALIDATION_FAILED", ... }`.
6. **Notification context contract** (notification-plan.md S2, ch.17, DRQ-037):
   a checkout (`POST /api/orders`), then `GET /api/notifications?customerId=`
   until the resulting order-confirmation notification is observable, shaped
   `{ id, customerId, orderId, channel: "EMAIL", message, sentAt }`. This
   assertion is a **bounded-wait poll** (retries the GET up to 10 times with a
   500ms busy-wait between retries, never delaying before the first attempt)
   so the *same* collection is correct against both a **synchronous** backend
   (today's monolith — the notification is already there on attempt 1, so the
   loop never actually waits) and a **future asynchronous** one (outbox ->
   Kafka -> consumer — later attempts give the event time to be consumed). If
   the budget is exhausted with no match, the folder fails (goes RED) rather
   than hanging — the same bounded loop is what makes a later "stop the
   consumer" negative check meaningful instead of a false positive
   (notification-plan.md DRQ-037, the false-equivalence trap documented in
   `examples/01-strangler-proxy/CUTOVER.md` §2).
7. **Inventory context contract** (inventory-plan.md S2, ch.19, DRQ-046): the
   `/api/inventory` READ surface — `GET /api/inventory` -> `200`, a non-empty
   array of `StockDto`-shaped items (`sku`, `name`, `priceCents`,
   `quantityOnHand`); `GET /api/inventory/{sku}` -> `200` with that same shape
   for a known sku; `GET /api/inventory/{sku}` for an unknown sku -> `404`
   with the documented `{ "error": "NOT_FOUND", "message": "...<sku>...",
   "timestamp": ... }` body, per `InventoryController`/`InventoryService` in
   `examples/00-monolith`. This folder asserts status + fields + content-type
   only — never raw DB rows — and, unlike the Notification folder, needs **no
   bounded-wait**: inventory's hot path (`CheckStock`/`Reserve`) stays
   synchronous end-to-end even after extraction (DRQ-041), so a plain
   request/response assertion is correct against both the monolith and the
   future extracted service. **This folder does NOT duplicate the
   cross-seam inventory-*mutation* behavior** — that is, and remains, the job
   of the existing checkout folders: **Scenario 1** (happy path — stock
   decrements by exactly the ordered quantity), **Scenario 2** (out-of-stock
   -> `409`, stock left unchanged) and **Scenario 3** (payment-declined ->
   stock **not** left decremented, i.e. the reservation is rolled back/
   compensated). Those three scenarios are the load-bearing
   inventory-equivalence checks the ch.19 extraction (synchronous gRPC
   reserve + a compensating `Release` on decline, per inventory-plan.md
   DRQ-042) must keep green **across the seam**, unchanged, alongside this
   new read-surface folder.

All assertions target status codes, response-body fields, and `Content-Type` —
the externally-observable contract — never internal DB rows directly, so the
same assertions hold whether `{{baseUrl}}` is the monolith or a Quarkus
service with a completely different internal schema.

The collection captures its own baseline state per scenario (e.g. stock level
"before") as collection variables rather than hard-coding absolute values, so
it is safe to re-run repeatedly against a long-lived service without a fresh
database reset between runs — a property the equivalence gate needs once it
runs unattended in CI (S-CI, `.github/workflows/code-ci.yml`).

## Running it

Start the target service first (it is not managed by this script), then:

```bash
# against the monolith baseline (default)
demos/demo-equivalence.sh

# or explicitly
demos/demo-equivalence.sh http://localhost:8080

# later, against the extracted Review service
demos/demo-equivalence.sh http://localhost:8081
```

Equivalently, with the `newman` CLI directly:

```bash
newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json
```

### Bringing up the monolith for a local run

```bash
# 1. podman stack up (Postgres on localhost:5432, db `monolith`) — see compose.yaml
podman compose --env-file .env up -d postgres

# 2. build + run the monolith (Flyway migrates + seeds automatically on boot)
cd examples/00-monolith
mvn -q -DskipTests package
SPRING_DATASOURCE_PASSWORD="$(grep ^POSTGRES_PASSWORD ../../.env | cut -d= -f2)" \
    java -jar target/monolith.jar

# 3. in another shell, from the project root
demos/demo-equivalence.sh
```

## How the equivalence gate uses this suite

- **Per-chapter acceptance (build-plan.md §G):** every extraction chapter
  (15, 17, 19, 23, 24, 26) runs this exact collection against the newly
  extracted service before its monolith module is decommissioned.
- **In CI (S-CI, DRQ-030):** `.github/workflows/code-ci.yml` runs
  `newman run tooling/newman/mea.postman_collection.json` against the
  extracted, cut-over service and fails the build on any non-zero Newman exit
  code — the "equivalence-gate-in-CI" mechanism.
- **Versioned with the monolith (R8, build-plan.md §M):** when the monolith's
  contract changes deliberately, this collection changes with it in the same
  commit; an extracted service that still diverges is a real regression, not
  suite drift.
