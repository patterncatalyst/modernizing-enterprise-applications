# The Contract/Acceptance Suite (formerly the Behavior-Equivalence Suite)

This directory holds the project's **contract/acceptance suite**, a Newman
(Postman) collection that asserts the system's externally-observable HTTP
contract: status codes, response-body fields, and content types. It does
**not** inspect internal database state directly (it only observes effects
through the same REST surface a client would use).

## Re-designated from equivalence to contract

This collection was originally captured against the running Spring Boot
monolith (`examples/00-monolith/`) and run UNCHANGED against each extracted
service to prove **behavior equivalence**, "the extracted service behaves
exactly like the monolith did." Since then, the **monolith has
been fully decommissioned** (its order context deleted, the last of six; see
its `SMELLS.md`) and removed from the running topology, it is kept frozen
in-repo only as the permanent "before" + golden-baseline referent, re-bootable
as a break-glass audit path (never as a live fallback). With no live monolith
left to be equivalent TO, the suite is **re-designated a contract/acceptance
suite**: every assertion below now means **"the system meets its captured
contract"**, not "matches the monolith." The contract itself is the frozen
golden baseline captured for the last time against the live monolith before the extractions began (`tooling/newman/GOLDEN-BASELINE.md`) and proven across the
order/gateway seam one final time (`examples/01-strangler-proxy/
CUTOVER.md`, `examples/07-order-service/CUTOVER.md`).

**This is a re-designation, not a weakening.** Not one assertion, negative
check, or test script changed, only this file, `GOLDEN-BASELINE.md`, and the
collection's own `info.description` field were updated to reflect what the
suite now means. Every `pm.test`/`pm.expect` in every item is byte-for-byte
what it was when the monolith was still the referent; the bounded-wait polls,
the net-zero stock assertions, and the three negative checks documented in
`examples/01-strangler-proxy/CUTOVER.md` and the service CUTOVER.md files all
still go RED when their mechanism is disabled. The **Order Context Contract**
and **GraphQL Gateway Contract** folders, staged as pending, then proven at the final cutover,
are now **permanent** members of this suite and run via the Node helper
(`demos/lib/run-order-newman.js`) in CI, the same
mechanism that already patches Scenario 4's `shippingSagaEnabled` gate.

This is the project's **contract gate** (the acceptance rule is unchanged): **an extracted
service is "done" only when it passes this exact collection, unchanged**,
against its own `baseUrl`. The collection was authored once
and is re-run, never re-written (except for this re-designation's prose),
against each extraction (Review first, then Notification, Inventory,
Payment, Shipping, Order+gateway).

> Terminology note: this suite was called the "behavior-equivalence suite"
> and its CI check the "equivalence gate" until the final cutover; afterward
> it is the "contract/acceptance suite" and the "contract gate", the
> monolith is no longer live, so "equivalence" (to what?) no longer applies,
> but the gate's defining property (an extracted service only counts as
> done once it passes, unchanged) is identical.

## Files

| File | Purpose |
|---|---|
| `mea.postman_collection.json` | The collection itself. Every request is parameterized by `{{baseUrl}}` so it can target any service without modification. |
| `local.postman_environment.json` | Points `{{baseUrl}}` at the monolith baseline, `http://localhost:8080`. Used for the baseline run. |
| `review-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Review service (`examples/02-review-service/`), on its own port so it can run side-by-side with the monolith during the strangler cutover. Only the "Review Context Contract" folder is meaningful against this target until Order/Inventory/Payment are themselves extracted. |
| `notification-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Notification service (`examples/03-notification-service/`), on its own port (`:8083`). Only the "Notification Context Contract" folder is meaningful against this target. |
| `inventory-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Inventory service (`examples/04-inventory-service/`), on its own port (`:8084`). Only the "Inventory Context Contract" folder is meaningful against this target until the order->inventory gRPC seam and the proxy's cutover are wired, see the scenario note below. |
| `payment-service.postman_environment.json` | Forward-reference environment for the extracted Quarkus Payment service (`examples/05-payment-service/`), on its own port (`:8085`). The collection's own "Payment Context Contract" folder (below) targets `{{baseUrl}}`, same as every other folder (not this file directly) so it runs correctly against the monolith (`:8080`), the proxy (`:8888`), or this service's own port once it is wired in standalone. |
| `../../demos/demo-equivalence.sh` | Thin runner: `demos/demo-equivalence.sh [baseUrl]`. Defaults to the monolith baseline. |

## Scenarios asserted

1. **Smoke**: the target is reachable and serves the seeded inventory.
2. **Happy-path checkout** (**adapted for the forthcoming ASYNC checkout contract**), `POST
   /api/orders` with an in-stock line item is asserted tolerant of **`201
   Created`** (today's synchronous monolith, payment captured in-process,
   the response already carries `status: "CONFIRMED"`) **or `202 Accepted`**
   (the future choreographed-saga payment service, examples/05-payment-service;
   payment hasn't happened yet, the response carries `status: "PENDING"`);
   either way the `Location` header and the total/line items (computed from
   the still-synchronous inventory reserve, before payment) are asserted
   unconditionally. A follow-up **BOUNDED-WAIT poll** of `GET
   /api/orders/{id}` then strictly asserts the order reaches the terminal
   `status: "CONFIRMED"`, immediately on attempt 0 against the synchronous
   monolith (it is already terminal), after the `payment.captured` event is
   consumed against the future saga, and inventory `quantityOnHand` drops by
   exactly the ordered quantity (unchanged, strict, both transports). Only the
   **transport** of the terminal-status observation is relaxed (synchronous
   response field vs. bounded-wait poll); the terminal status itself and the
   stock-decrement side effect are never weakened, and a bounded-wait that
   exhausts its budget without reaching `CONFIRMED` goes **RED**, not green.
3. **Out-of-stock**: **left untouched, fully synchronous** (inventory Reserve stays a synchronous gRPC call at checkout time
   even after the payment extraction, only the payment outcome goes async).
   Ordering more than `quantityOnHand` returns `409 Conflict` with the
   documented error body
   `{ "error": "OUT_OF_STOCK", "message": "...<sku>...", "timestamp": ... }`,
   and inventory is confirmed unchanged afterward (the reservation is rejected
   before any write).
4. **Payment-declined** (**adapted, the crux scenario**), a checkout whose `paymentMethod` contains
   `DECLINE` is asserted tolerant of **`402 Payment Required`** (today's
   synchronous monolith, `OrderService#placeOrder`'s catch block issues the
   compensating gRPC `Release` in-line, synchronously, BEFORE re-throwing, so
   the terminal decline AND the stock restoration are both already known at
   POST time; the whole checkout `@Transactional` rolls back, so no order
   resource is ever persisted to poll) **or `202 Accepted`** (the future
   choreographed saga, the order persists `PENDING`, the decline arrives
   later as a `payment.declined` event). On the `402` path the documented
   error body (`{ "error": "PAYMENT_DECLINED", "message": "...",
   "timestamp": ... }`) is asserted exactly as before, unchanged. On the
   `202` path a **BOUNDED-WAIT poll** of `GET /api/orders/{id}` strictly
   asserts the order reaches the terminal `status: "PAYMENT_DECLINED"`. Either
   way, a second **BOUNDED-WAIT poll** of `GET /api/inventory/{sku}` strictly
   asserts stock returns to **net-zero**, this is the behavior worth
   protecting across the extraction: the reservation is restored, whether by
   the monolith's in-line compensating `Release` today or by
   the `payment.declined` choreography reaction later. Only the
   **transport** is relaxed (`402` vs `202` at POST, synchronous vs
   bounded-wait observation of the terminal state); the terminal
   `PAYMENT_DECLINED` status and the net-zero stock assertion are never
   weakened, a bounded-wait that exhausts its budget without reaching the
   terminal state goes **RED**.
5. **Review context contract** (the first extraction's surface):
   - `GET /api/reviews?sku=...` -> `200`, non-empty array, each item shaped
     `{ id, customerId, sku, rating (1-5), comment, createdAt }`.
   - `GET /api/reviews/{id}` -> `200` with the same shape.
   - `POST /api/reviews` with no credentials -> `401`.
   - `POST /api/reviews` with HTTP Basic `demo-customer`/`demo-pass` -> `201
     Created` echoing the submitted fields, with a `Location` header.
   - `POST /api/reviews` (authenticated) with an out-of-range `rating` -> `400`
     with `{ "error": "VALIDATION_FAILED", ... }`.
6. **Notification context contract** (checkout contract adapted for the async checkout): a checkout (`POST /api/orders`), tolerant of **`201`** (today's
   synchronous monolith) or **`202`** (the future choreographed saga) exactly
   like Scenario 1's 1b, followed by a **bounded-wait poll** of `GET
   /api/orders/{id}` strictly asserting the order reaches the terminal
   `CONFIRMED` status (same technique and 10x500ms budget as
   Scenario 1's 1c, immediate on attempt 0 against the synchronous monolith,
   goes RED rather than hanging if the order never leaves `PENDING`), and only
   then `GET /api/notifications?customerId=` until the resulting
   order-confirmation notification is observable, shaped `{ id, customerId,
   orderId, channel: "EMAIL", message, sentAt }`. That last step is its own
   **bounded-wait poll** (retries the GET up to 10 times with a 500ms
   busy-wait between retries, never delaying before the first attempt) so the
   *same* collection is correct against both a **synchronous** backend
   (today's monolith, the notification is already there on attempt 1, so the
   loop never actually waits) and a **future asynchronous** one (outbox ->
   Kafka -> consumer, later attempts give the event time to be consumed). If
   either budget is exhausted with no match, the folder fails (goes RED)
   rather than hanging, the same bounded loop is what makes a later "stop the
   consumer" negative check meaningful instead of a false positive
   (the false-equivalence trap documented in
   `examples/01-strangler-proxy/CUTOVER.md`). (This folder originally
   asserted the checkout POST as an unconditional `201`/`CONFIRMED`, which
   would have gone RED after the payment cutover; it was fixed to
   the same bounded-wait pattern as Scenario 1 without weakening the
   downstream notification assertion.)
7. **Inventory context contract** (read surface): the
   `/api/inventory` READ surface, `GET /api/inventory` -> `200`, a non-empty
   array of `StockDto`-shaped items (`sku`, `name`, `priceCents`,
   `quantityOnHand`); `GET /api/inventory/{sku}` -> `200` with that same shape
   for a known sku; `GET /api/inventory/{sku}` for an unknown sku -> `404`
   with the documented `{ "error": "NOT_FOUND", "message": "...<sku>...",
   "timestamp": ... }` body, per `InventoryController`/`InventoryService` in
   `examples/00-monolith`. This folder asserts status + fields + content-type
   only (never raw DB rows) and, unlike the Notification folder, needs **no
   bounded-wait**: inventory's hot path (`CheckStock`/`Reserve`) stays
   synchronous end-to-end even after extraction, so a plain
   request/response assertion is correct against both the monolith and the
   future extracted service. **This folder does NOT duplicate the
   cross-seam inventory-*mutation* behavior**, that is, and remains, the job
   of the existing checkout folders: **Scenario 1** (happy path, stock
   decrements by exactly the ordered quantity), **Scenario 2** (out-of-stock
   -> `409`, stock left unchanged) and **Scenario 3** (payment-declined ->
   stock **not** left decremented, i.e. the reservation is rolled back/
   compensated). Those three scenarios are the core
   inventory-equivalence checks the inventory extraction (synchronous gRPC
   reserve + a compensating `Release` on decline) must keep green **across the seam**, unchanged, alongside this
   new read-surface folder.
8. **Payment context contract** (read surface): the
   `/api/payments` READ surface, `GET /api/payments?orderId=` -> `200`, a
   non-empty array of `PaymentDto`-shaped items (`id`, `orderId`,
   `amountCents`, `method`, `status` one of `CAPTURED`/`DECLINED`,
   `createdAt`); `GET /api/payments/{id}` -> `200` with that same shape for
   the payment id found above. It correlates with **Scenario 1's confirmed
   order** (the `happyPathOrderId` collection variable), reusing an id the
   suite already placed rather than seeding a payment of its own, the same
   way the Review/Notification/Inventory folders reuse ids. By the time this
   folder runs, Scenario 1 has already bounded-wait-confirmed that order,
   which is only possible once the payment was captured, synchronously
   in-process against today's monolith (`/api/payments` served by the
   monolith), or via the `payment.captured` choreography reaction against the
   future cutover saga (`/api/payments` served by the extracted payment
   service through the strangler proxy), so a `CAPTURED` payment row for
   that order is guaranteed to exist, and is asserted strictly, in **both**
   states; this folder needs no bounded-wait of its own because it only runs
   after Scenario 1's own bounded-wait has already resolved. (This folder was
   required by the payment acceptance criteria but was missing from the
   collection until it was added.)

All assertions target status codes, response-body fields, and `Content-Type`,
the externally-observable contract, never internal DB rows directly, so the
same assertions hold whether `{{baseUrl}}` is the monolith or a Quarkus
service with a completely different internal schema.

The collection captures its own baseline state per scenario (e.g. stock level
"before") as collection variables rather than hard-coding absolute values, so
it is safe to re-run repeatedly against a long-lived service without a fresh
database reset between runs, a property the equivalence gate needs once it
runs unattended in CI (`.github/workflows/code-ci.yml`).

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

`demos/demo-equivalence.sh` and the cutover demos use an installed `newman`
when one is on `PATH`; otherwise they run the pinned `npx -y newman@6.2.3`
(newest non-prerelease on npm, 2026-10-09; `demos/lib/newman.sh`),
which installs into the per-user npm cache: no `sudo`, no global install.
`NEWMAN_VERSION=<x.y.z>` overrides the pin. CI installs the same version.

To run the suite through the edge router against the finished topology, keep
the apps up after the final-project demo:

```bash
demos/demo-final-topology.sh --keep-running
demos/demo-equivalence.sh http://localhost:8888
demos/demo-final-topology.sh --stop
```

Equivalently, with the `newman` CLI directly:

```bash
newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json
```

### Re-deriving the golden baseline from the monolith (break-glass only, )

The monolith is **removed from the running topology**
(`compose.yaml` no longer stands it up) and is never brought up as part of a
normal local run or CI gate. The steps below exist only as a break-glass audit
path, re-deriving `GOLDEN-BASELINE.md` from scratch in a throwaway topology,
not as something a day-to-day contributor needs to do:

```bash
# 1. check out the preserved "before" (the complete, runnable six-context
#    monolith) rather than running it from main, which only has the frozen
#    shell left:
git worktree add /tmp/mea-before reference/monolith-before   # or: git checkout v0-monolith

# 2. compose stack up (Postgres on localhost:5432), see compose.yaml. Its
#    initdb already applies main's V1-V4 to db `monolith`, which the
#    "before" monolith (V1-V2 only, ddl-auto=validate) would reject, so give it
#    an empty database of its own:
docker compose --env-file .env up -d postgres
docker exec mea-postgres createdb -U monolith monolith_before

# 3. build + run the preserved monolith (Flyway migrates + seeds automatically)
cd /tmp/mea-before/examples/00-monolith
mvn -q -DskipTests package
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/monolith_before \
SPRING_DATASOURCE_PASSWORD="$(grep ^POSTGRES_PASSWORD ../../.env | cut -d= -f2)" \
    java -jar target/monolith.jar

# 4. in another shell, from the project root
demos/demo-equivalence.sh
```

The frozen shell on `main` (`examples/00-monolith/`) itself no longer serves
anything under `/api/**` (see its `SixContextsSmokeTest`), it is not a valid
target for this suite anymore; use the `reference/monolith-before` branch (or
the `v0-monolith`/`stage/NN-*-extracted` tags) for any "what did the monolith
actually do" comparison.

## How the contract gate uses this suite

- **Per-extraction acceptance:** every extraction ran this exact collection against the newly
  extracted service before its monolith module was decommissioned.
- **In CI:** `.github/workflows/code-ci.yml` runs
  `newman run tooling/newman/mea.postman_collection.json` against the
  extracted, cut-over services, **with no live monolith in the topology**
  (and fails the build on any non-zero Newman exit code): the
  "contract-gate-in-CI" mechanism (formerly "equivalence-gate-in-CI").
- **Versioned with the frozen golden baseline:**
  the contract is now fixed (`GOLDEN-BASELINE.md`) rather than tracking a
  live, changeable monolith; an extracted service that diverges from it is a
  regression, not suite drift.
