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
| `../../demos/demo-equivalence.sh` | Thin runner: `demos/demo-equivalence.sh [baseUrl]`. Defaults to the monolith baseline. |

## Scenarios asserted

1. **Smoke** — the target is reachable and serves the seeded inventory.
2. **Happy-path checkout** — `POST /api/orders` with an in-stock line item
   returns `201 Created` with `status: "CONFIRMED"`, the correct total/line
   items, a `Location` header; a follow-up `GET /api/orders/{id}` confirms the
   persisted status; and inventory `quantityOnHand` drops by exactly the
   ordered quantity (the observable status *and* data transition).
3. **Out-of-stock** — ordering more than `quantityOnHand` returns
   `409 Conflict` with the documented error body
   `{ "error": "OUT_OF_STOCK", "message": "...<sku>...", "timestamp": ... }`,
   and inventory is confirmed unchanged afterward (the reservation is rejected
   before any write).
4. **Payment-declined** — a checkout whose `paymentMethod` contains `DECLINE`
   returns `402 Payment Required` with
   `{ "error": "PAYMENT_DECLINED", "message": "...", "timestamp": ... }`, and —
   this is the behavior worth protecting across the extraction — inventory is
   asserted **not left decremented**: the single ACID `@Transactional` that
   spans inventory + order + payment in the monolith rolls back the stock
   reservation when payment is declined, and the suite proves that externally
   by reading `/api/inventory/{sku}` before and after.
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
