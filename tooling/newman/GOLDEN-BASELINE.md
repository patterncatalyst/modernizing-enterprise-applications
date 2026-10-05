# The Golden Baseline (order-plan.md S2, ch.26, DRQ-071) — the LAST monolith-anchored run

This file freezes the **final** run of `mea.postman_collection.json` for which the
still-present Spring Boot monolith (`examples/00-monolith`) is the suite's
*referent* — the thing the collection is proving equivalence against. Order +
the GraphQL gateway (`examples/07-order-service`, `examples/08-graphql-gateway`)
are the last extraction in the roadmap (build-plan.md §E row 6); once it cuts
over and the monolith is decommissioned (S9/S10), there is no more monolith
left to version this suite against. **State this explicitly: this is the last
time the monolith is the referent.** Every later run of this exact collection
checks the system against *this* captured contract, not against a second,
independently-live monolith process.

## What was run, and against what

Full topology, committed defaults (no flags overridden except the monolith's
datasource password, which is operational, not behavioral):

| Service | Port | Mode |
|---|---|---|
| `examples/00-monolith` (Spring Boot) | `:8080` | checkout always `POST /api/orders` -> `202 PENDING` (DRQ-047's dual-mode 201/202 tolerance already retired when payment was decommissioned, r06/S9); shipping `orchestrated` only (shipping-plan S9 retired `shipping.mode`) |
| `examples/01-strangler-proxy` (Camel) | `:8888` | all five committed cutover flags at their permanent defaults: `strangler.review.enabled=true`, `strangler.notification.enabled=true`, `strangler.inventory.enabled=true`, `strangler.payment.enabled=true`, `strangler.shipping.enabled=true`. `/api/orders` has **no seam yet** — falls through to the monolith (order-plan S8 adds `strangler.order.enabled`) |
| `examples/02-review-service` (Quarkus) | `:8081` | extracted, decommissioned from monolith |
| `examples/03-notification-service` (Quarkus) | `:8083` | extracted, decommissioned from monolith |
| `examples/04-inventory-service` (Quarkus) | `:8084` (+ gRPC `:9004`) | extracted, decommissioned from monolith; monolith's own `public.inventory_items` is frozen/unused — live stock lives in `inventory.inventory_items` |
| `examples/05-payment-service` (Quarkus) | `:8085` | extracted, decommissioned from monolith (r06/S9) |
| `examples/06-shipping-service` (Quarkus) | `:8088` | extracted, decommissioned from monolith (r07/S9) |
| `examples/07-order-service` | — | **does not exist yet** (scaffolded S4) |
| `examples/08-graphql-gateway` | — | **does not exist yet** (scaffolded S7) |

The suite was run against `{{baseUrl}}=http://localhost:8888` (through the
proxy, not the monolith directly) — the proxy is a transparent pass-through
for every path except the five already-cut-over seams, so this exercises
exactly what a real client sees today.

Stock was topped up beforehand via a plain SQL `UPDATE` against the live
`inventory.inventory_items.quantity_on_hand` column (no Flyway migration) —
the monolith's own `public.inventory_items` table is frozen/historical since
the inventory extraction and was not what checkout actually reserves against.

## Result: fully green

```
┌─────────────────────────┬───────────────────┬──────────────────┐
│                         │          executed │           failed │
├─────────────────────────┼───────────────────┼──────────────────┤
│              iterations │                 1 │                0 │
│                requests │                49 │                0 │
│            test-scripts │                49 │                0 │
│      prerequest-scripts │                20 │                0 │
│              assertions │               140 │                0 │
└─────────────────────────┴───────────────────┴──────────────────┘
total run duration: 12.2s
```

Zero failures. The staged **GraphQL Gateway Contract** folder's `GG-gate` item
ran (1 request, asserted reachability only) and correctly `setNextRequest(null)`
past `GG-a`/`GG-b` — they did not execute, so they neither falsely passed nor
falsely failed against a gateway that doesn't exist yet. **Scenario 4**'s
`SF-gate` likewise skipped `SF-a..SF-d` (unchanged behavior, `shippingSagaEnabled`
still defaults to `false` in the committed collection — flipping it to exercise
the real SHIP-FAIL path is an operator/demo-time action, not this baseline's
job).

## Folders/scenarios and what each strictly asserts

1. **Smoke** — target reachable, seed inventory present.
2. **Scenario 1 — Happy-Path Checkout** — `POST /api/orders` accepted (`201`
   or `202` tolerance — historical, both branches currently resolve to `202`
   PENDING against this topology); **bounded-wait poll strictly asserts the
   terminal `CONFIRMED` status**; stock decremented by exactly the ordered
   quantity. Sets `happyPathOrderId`, reused by every later Context Contract
   folder.
3. **Scenario 2 — Out-of-Stock** — over-ordering returns `409 CONFLICT` /
   `OUT_OF_STOCK`; stock **left untouched** (negative check: a reservation
   that silently succeeded would fail this).
4. **Scenario 3 — Payment-Declined** — decline sentinel payment method;
   **bounded-wait poll strictly asserts terminal `PAYMENT_DECLINED`**, and a
   second bounded-wait poll **strictly asserts stock returns to net-zero**
   (the crux compensation check — a stuck decrement would fail this, not
   silently pass).
5. **Scenario 4 — Shipping-Failure** — `SF-gate` PENDING (unchanged from
   shipping-plan S2); when enabled (`shippingSagaEnabled=true`, flipped only
   via the Node-API helper, never `--env-var`), strictly asserts terminal
   `SHIPPING_FAILED` + inventory net-zero via the orchestrated compensation.
6. **Review Context Contract** — list/get-by-sku shape; unauthenticated
   write `401`; authenticated write `201` + `Location`; invalid rating `400`.
7. **Notification Context Contract** — checkout + bounded-wait to `CONFIRMED`
   + bounded-wait until the confirmation notification is observable, shaped
   `{id, customerId, orderId, channel, message, sentAt}`.
8. **Inventory Context Contract** — list/get-by-sku shape; unknown sku `404`
   `NOT_FOUND`. Does **not** duplicate the mutation checks in Scenarios 1-3.
9. **Payment Context Contract** — list-by-orderId + get-by-id, correlated to
   `happyPathOrderId`; strictly asserts the correlated payment is `CAPTURED`.
10. **Shipping Context Contract** — list-by-orderId + get-by-id + unknown-id
    `404`; asserts only the read contract, no mode-specific status.
11. **Order Context Contract** *(NEW at this step, DRQ-071)* — see below.
12. **GraphQL Gateway Contract** *(NEW at this step, STAGED pending)* — see
    below.

### Order Context Contract (new)

Asserts the `/api/orders` surface itself, not just checkout-as-a-side-effect:

- **9a.** `POST /api/orders` (a fresh checkout body, independent of
  Scenario 1's) -> strictly `202 Accepted` + `Location` header + `PENDING`
  status at POST time (DRQ-047's settled async contract — the dual-mode
  201/202 tolerance was already retired on this endpoint when payment was
  decommissioned, so this is a single strict assertion, not an `oneOf`).
- **9b.** `GET /api/orders/{id}` (correlated to `happyPathOrderId`) -> `200`
  with the OrderDto shape: `customerId` (number), `status` (one of the five
  documented `OrderStatus` enum values — shape check, not a pinned terminal
  value, since Scenario 1 already owns that strict terminal assertion),
  `totalCents` (number), `createdAt` (defined), **`shippingAddress` (string —
  exposed per r07/S6, previously missing from this DTO)**, `items[]` (each
  with `sku`/`quantity`/`unitPriceCents`).
- **9c.** `GET /api/orders` -> `200`, non-empty array, confirmed to include
  the correlated Scenario-1 order, each entry shape-checked.
- **9d.** `GET /api/orders/999999999` -> `404` `NOT_FOUND` + timestamp.

This folder is **mode-agnostic**: none of its assertions depend on whether
checkout is pre- or post-order-cutover — the same REST contract
(`OrderController`/`OrderService`/`OrderDto`) is lifted unchanged into
`examples/07-order-service` (order-plan S4/DRQ-073), so these same assertions
must stay green once `strangler.order.enabled` flips (S8) and after the
monolith is fully decommissioned (S9/S10).

### GraphQL Gateway Contract (new, STAGED — PENDING until S7)

`examples/08-graphql-gateway` does not exist at S2. Gated the **same proven
way** Scenario 4 is gated (shipping-plan S2 precedent):

- A collection variable `graphqlGatewayEnabled` (default `"false"`).
- A gate item, `GG-gate`, whose request is a cheap, harmless, always-true
  reachability check (`GET {{baseUrl}}/api/inventory` — deliberately
  unrelated to GraphQL semantics, so it can never itself constitute a false
  pass/fail of the gateway contract). Its test script reads
  `graphqlGatewayEnabled`; when disabled it calls
  `postman.setNextRequest(null)` — this folder is **last** in the collection,
  so "skip to the next folder" (the SF-gate idiom) becomes "stop the run
  here," which is the correct equivalent.
- Two staged, strictly-asserting requests that only execute when enabled:
  - **GG-a.** `order(id)` aggregation query (POST `{{graphqlGatewayBaseUrl}}/graphql`,
    default `http://localhost:8090`) — asserts `errors` is absent, `data.order`
    is non-null, the order's own fields (`customerId`, `status` enum
    membership, `totalCents`, `createdAt`, `shippingAddress`), each line
    item's nested `stock.quantityOnHand` and `reviews[]`, at least one
    `CAPTURED` payment, and at least one shipment — the full `order +
    payments/shipments/reviews/stock` aggregation DRQ-069 describes. This is
    the **real GraphQL response shape**, asserted strictly — not "any 2xx."
  - **GG-b.** `order(id)` with an unknown id — asserts the GraphQL error
    **envelope** (`data.order === null` AND a populated, well-formed
    `errors[]`), not merely HTTP `200` (GraphQL always answers `200` at the
    transport level, so a bare status check here would be vacuous).
- **Enabling at S7/S8 requires the Node-API helper pattern, NOT `--env-var`.**
  `newman --env-var "graphqlGatewayEnabled=true"` populates the
  **environment** variable scope; `GG-gate`'s script reads
  `pm.collectionVariables.get(...)` — a different scope `--env-var` cannot
  reach. This was verified empirically for the identical `shippingSagaEnabled`
  gate (see `demos/lib/run-shipping-newman.js`'s header comment) and holds
  for exactly the same reason here. Enabling this folder for real at S7/S8
  needs a future `demos/lib/run-order-newman.js`, modeled on
  `run-shipping-newman.js`, that loads the collection JSON into memory,
  patches the in-memory `graphqlGatewayEnabled` variable, and hands the
  patched object to `newman.run()` — never a rewrite of the committed
  collection file, never a CLI flag.

**Why this can't go vacuous:** the gate skips the *entire* GG-a/GG-b pair
together — there is no path where one runs and the other doesn't, and no path
where a malformed/partial gateway response is accepted. GG-a is strict about
the success shape; GG-b is strict about the error envelope. Disabled, neither
runs (no false pass, no false fail against code that doesn't exist); enabled,
both must hold exactly.

## Negative checks carried forward (not dropped)

These are NOT run as part of a single `newman` pass — they are the
documented, demo-script-driven proofs each prior extraction stood up (and
order-plan S9 will stand up its own version), restated here to confirm DRQ-071
preserves them, not drops them, in the contract conversion:

- **Stop a consumer ⇒ stuck state ⇒ RED.** Precedent:
  `demos/demo-shipping-cutover.sh` §5 (stop shipping-service, Scenario 1's
  bounded-wait goes RED on a stuck `AWAITING_SHIPMENT` order; GREEN again once
  restarted). `demos/demo-payment-cutover.sh` has the payment-service
  analogue. Order-plan S9 will add the order-service/gateway analogue.
- **Disable a compensation ⇒ non-net-zero ⇒ RED.** Precedent:
  `demos/demo-shipping-cutover.sh` §4 (disable the Camel Saga
  `.compensation(...)`, force a SHIP-FAIL order, Scenario 4 goes RED because
  stock is left decremented; byte-for-byte restore, rebuild, GREEN again).
- **Out-of-stock leaves stock untouched (Scenario 2).** A silently-succeeding
  over-reservation would fail `2c`.
- **Payment-declined / shipping-failure net to zero (Scenarios 3/4).** A
  compensation that never fires leaves stock decremented; both scenarios'
  final bounded-wait poll would go RED, not silently pass.
- **Proxy-reaches-the-real-backend evidence.** Precedent:
  `demos/demo-shipping-cutover.sh` §2c (byte-compare the proxy's response to
  the service's own direct response, and confirm it DIFFERS from the
  monolith's) — the CUTOVER.md §2 lesson (a silently-never-matching
  content-based-routing predicate can leave the suite green while testing the
  wrong backend) applies again at order-plan S8/S9 and must be repeated there.

None of these are weakened or removed by this step. DRQ-071's conversion is a
**re-designation of what the assertions mean**, not a reduction of what they
check.

## The DRQ-071 conversion plan (what happens next)

1. **S2 (this baseline, done):** final monolith-anchored baseline captured;
   Order Context Contract live; GraphQL Gateway Contract staged pending.
   Collection versioned with the monolith (R8) — this commit.
2. **S8 (order cutover):** `strangler.order.enabled` flips; full suite
   (including Order Context Contract) proven green **across the seam**
   (monolith vs. `examples/07-order-service`, both states), plus the
   negative checks above repeated for order/gateway. `graphqlGatewayEnabled`
   flips to `true` for real once `examples/08-graphql-gateway` exists (S7),
   and GG-a/GG-b are proven both ways too.
3. **S9 (equivalence gate, last reversibility window):** both flags proven
   reversible one final time (DRQ-072) — the LAST time this whole system is
   reversible — then the irreversible decommission step is taken.
4. **S10 (decommission, DRQ-071's re-designation):** the monolith is fully
   decommissioned (frozen-not-deleted in-repo, a break-glass referent only —
   DRQ-070/DRQ-024). This exact collection's assertions are **re-designated**:
   no longer "same as the monolith," now **"the system meets its captured
   contract."** CI's order/gateway gate (S11) stops bringing up a live
   monolith — Postgres + Kafka + order service + gateway + payment + shipping
   + inventory gRPC only. The frozen monolith can still be booted in a
   throwaway topology to re-derive this baseline from scratch (the
   break-glass audit path DRQ-071 names explicitly), but it is never again a
   routine part of a green run.

**This run is the last time the monolith is this suite's referent.** Every
later invocation of `mea.postman_collection.json` is checked against what
this document and this commit captured, not against a second monolith
process running somewhere.
