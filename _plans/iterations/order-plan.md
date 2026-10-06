---
title: "Order + GraphQL Gateway Extraction Plan — ch.26 (the sixth and LAST strangler extraction, CQRS + SmallRye GraphQL gateway; the monolith is decommissioned)"
description: "Concrete, ordered, executable step plan for the Order service extraction — the last and hardest of the six. It lifts the god `OrderService` (SMELL #2), the checkout command path (`placeOrder`: validate customer → reserve inventory over gRPC → persist PENDING → emit `order.placed` via its own transactional outbox), the four lifted saga reactions (`OrderSagaListener`: payment.captured/declined, shipment.dispatched/failed, with the compensating gRPC Release), and the Order/OrderItem/Customer aggregate into a new Quarkus `examples/07-order-service`, re-shaped as a genuine CQRS write-model/read-model split, and adds a new `examples/08-graphql-gateway` (SmallRye GraphQL aggregation surface stitching order + payments + shipments + reviews + stock). The genuinely-subtle parts are unique to the LAST extraction: (1) once order leaves the monolith there is no monolith left to be *equivalent to*, so the behavior-equivalence suite must convert from equivalence-vs-monolith into a contract/acceptance suite against a frozen golden baseline, without going vacuous; (2) there is no monolith fallback to revert to after decommission, so the reversibility story and the de-risking of the irreversible final cutover change; (3) SMELL #2 (god service) and the LAST clause of SMELL #3 (one in-process ACID transaction) must finally be cured, completing the ACID→ACD story for ALL contexts; and (4) the strangler proxy sheds its strangler role and becomes the system's permanent REST edge router once there is no host tree left to strangle. Mirrors the payment (ch.23) and shipping (ch.24) extraction templates."
status: "execution plan — planning only; nothing built, scaffolded, or pushed until the user approves"
iteration: r08
chapter: 26
depends_on:
  - _plans/build-plan.md            # §E row 6 (order + gateway = CQRS read model + SmallRye GraphQL gateway, strangler completes, monolith decommissioned), §G (equivalence gate; CQRS read-after-write with bounded waits), §J cadence (order is r08), §K single-writer, two-phase DRQ-029, non-trivial example DRQ-032
  - _plans/decisions.md             # DRQ-029 (two-phase), DRQ-032 (datamesh reuse), DRQ-034 (outbox), DRQ-037 (bounded-wait + negative-check), DRQ-038 (JSON; Avro/Apicurio deferred to ch.28), DRQ-047 (async checkout 202/PENDING), DRQ-048/049 (choreographed topology + compensation), DRQ-056/059/060 (orchestrated saga + delegated compensation), DRQ-061 (order states), DRQ-063/064 (own-outbox + idempotency), DRQ-065 (reversibility/equivalence/ACL honesty)
  - _plans/iterations/payment-plan.md   # the proven extraction template (choreographed saga; the sync→async contract DRQ-047; the outbox-emit + idempotent-consumer shapes order lifts)
  - _plans/iterations/shipping-plan.md  # the proven extraction template (orchestrated saga; "Decisions CONFIRMED" branch note; the ACL-honesty transparent-proxy precedent; the cross-service-cascade CI lesson)
  - _plans/reconciliation.md        # ch.26 resume boundary (what order inherits: both saga styles, the OrderSagaListener reaction machinery, the Topics/event topology, the outbox-emit pattern, the honest-limitations list)
  - examples/00-monolith real code  # order/{OrderService,OrderController,Order,OrderItem,OrderRepository,OrderSagaListener}, common/{Customer,CustomerRepository,OrderDto,OrderCreate,OrderStatus,StockDto,Topics}, common/events/*, common/outbox/*, inventory/RemoteInventoryClient, db/migration/*, SMELLS.md (#2 god service, #3 one ACID txn — both tagged ch.26)
  - examples/01-strangler-proxy/.../StranglerProxyRoute.java  # the five committed-true flags + the monolith default backend; /api/orders is the ONE path still routed to the monolith
references:
  - "~/Dev/datamesh-reference-arch-quarkus/examples/graphql-gateway (DRQ-032: idiomatic Quarkus SmallRye GraphQL *aggregation* gateway — owns no data, federates reads by calling order-service over REST + inventory-service over gRPC; @GraphQLApi/@Query/@Source field-resolver stitching; this is the canonical shape the ch.26 gateway adapts, widened from order+stock to order+payments+shipments+reviews+stock)"
  - "~/Dev/datamesh-reference-arch-quarkus/examples/order-service (DRQ-032: idiomatic Quarkus order service — own Postgres, REST read surface, Kafka event producer; adapted here to the CQRS write/read split + the lifted OrderSagaListener reactions)"
  - "SmallRye GraphQL on Quarkus (quarkus-agent: quarkus_searchDocs \"smallrye graphql\", quarkus_skills graphql): @GraphQLApi, @Query/@Mutation, @Source field resolvers for aggregation-style stitching, quarkus-smallrye-graphql; aggregation-in-one-gateway vs Apollo-federation-router (the latter rejected, DRQ-069)"
---

# ch.26 — Order Service + GraphQL Gateway Extraction (execution plan — CQRS + SmallRye GraphQL; the monolith is decommissioned)

> **PLANNING ONLY.** This file is the Opus "Plan" phase (ADLC §F.1) and must be
> **user-approved before any code is written**. Nothing is built, scaffolded, or
> pushed until approval.
>
> **Branch for all work:** see "Decisions CONFIRMED" #1 below — work stays on the
> long-lived **`r02-walking-skeleton`** branch; commit scopes use `r08.x` / `§26`.
> **Total steps:** 15 (S1 … S15).
> **Relay tiering (DRQ-004):** every step is *executed* by **Sonnet**; steps marked
> **[Opus gate]** additionally require an **Opus validation** pass before their
> checkpoint commit. Steps marked **[Opus gate — equivalence gate]** are the ADLC
> Verify human sign-off points (§F.1).

## Decisions CONFIRMED (user, 2026-10-05 — all five signed off before execution)
This is the last and hardest extraction; its five architectural decisions were brought
to the user and CONFIRMED on the recommended options:
1. **CQRS shape — CONFIRMED: same-DB projected read model** (denormalized `order_view`
   in the order service's own Postgres, projected from lifecycle events; reads served
   only from the view). NOT a separate read datastore.
2. **GraphQL gateway — CONFIRMED: additive cross-context aggregation gateway** with its
   OWN front door, alongside the edge router (SmallRye GraphQL, data-less, stitches
   order→payments/shipments/reviews/stock). NOT Apollo federation, NOT order-only.
3. **Monolith end-state — CONFIRMED: fully decommissioned but frozen-not-deleted**
   in-repo (DRQ-024 golden baseline); the strangler proxy sheds its flags and becomes a
   permanent REST edge router.
4. **Equivalence-suite fate — CONFIRMED: convert to a contract/acceptance suite** against
   the frozen golden baseline; CI drops the live monolith from the order/gateway gate
   (frozen monolith re-bootable as break-glass to re-derive the baseline).
5. **Reversibility — CONFIRMED: reversible at the seam until S9 (proven exhaustively at
   S8), then the final decommission is the one deliberate system-wide irreversible move**,
   de-risked by the S8 proof + the frozen break-glass monolith + the independently-
   revertible additive gateway/read-model.

**Port correction (orchestrator):** the GraphQL gateway moves off the planner's
suggested **:8086** (that host port is Debezium `mea-connect`) to **:8090** (free).
The order service stays at **:8087** (payment's :8087 is a test-only port, free at
runtime). The detailed rationale + rejected alternatives for each decision follow below.

1. **CQRS shape (DRQ-067) — RECOMMEND: a separate denormalized read model in the
   *same* Postgres database, projected from the order-lifecycle events, with the
   write model being the normalized `Order` aggregate.** The read APIs (REST +
   GraphQL) serve exclusively from the read model; the read model is updated by the
   same event reactions that already drive the lifecycle (payment.captured/declined,
   shipment.dispatched/failed). *Trade-off:* this is a genuine CQRS teaching example
   (two models, separate update path, read-after-write eventual consistency —
   build-plan §G) **without** an event-sourcing rabbit hole and **without** a second
   datastore (scope discipline, DRQ-038 Avro is ch.28). Rejected: full event sourcing
   (cost/scope; build-plan T5 keeps ES light), and no-split-at-all (doesn't teach CQRS
   and build-plan §C marks CQRS load-bearing via the gateway read side). The only real
   call for the user: *same-DB two-table CQRS* (recommended) vs *a physically separate
   read datastore* (more "real," but speculative infra we recommend against).
2. **GraphQL gateway scope (DRQ-069) — RECOMMEND: an additive cross-context
   *aggregation* gateway (`examples/08-graphql-gateway`, SmallRye GraphQL) that owns
   no data and stitches `order → { its payments, its shipments, its reviews, each
   item's stock }` by calling the five extracted services (order/payment/shipping/
   review over REST, inventory over gRPC), sitting ALONGSIDE the REST surface + the
   proxy, not replacing them.** *Trade-off:* aggregation-in-one-gateway (matches the
   datamesh `graphql-gateway`, no new infra) teaches the CQRS read-aggregation surface
   and "one GraphQL question, many backends" cleanly; it is additive so it cannot
   regress the equivalence/contract suite. Rejected: Apollo-style *federation router*
   (a new infra component + subgraph SDL on every service — speculative infra), and
   order-only GraphQL (too thin to teach aggregation). The call for the user: does the
   gateway get its **own front door** (recommended — new port, GraphQL is a distinct
   protocol edge) or route through the proxy too?
3. **Monolith end-state (DRQ-070) — RECOMMEND: the monolith is *fully
   decommissioned* — its order Java code deleted like every prior context, removed
   from the running topology (it serves nothing) — but KEPT, frozen, in-repo at
   `examples/00-monolith/` per DRQ-024 as the permanent "before" picture and the
   reproducible golden-baseline referent.** The strangler proxy sheds its strangler
   role and becomes the system's permanent **REST edge router**: all `strangler.*`
   flags become permanent/removed and the `strangler.monolith.base-url` default
   backend is retired (there is no host tree left to fall back to). *Trade-off:* this
   is the honest close of the strangler-fig narrative (the fig stands alone) and
   satisfies DRQ-024; the alternative (delete the monolith module entirely) would
   discard the book's living "before" and the baseline's reproducibility. The call for
   the user: confirm "frozen-not-deleted + proxy-becomes-edge-router."
4. **Equivalence-suite fate (DRQ-071) — RECOMMEND: convert the behavior-equivalence
   suite from *equivalence-vs-a-living-monolith* into a *contract/acceptance suite
   against a frozen golden baseline*.** The monolith is the referent for the LAST time
   at S2's baseline capture and S9's final equivalence check; at S9's decommission the
   captured baseline becomes the frozen golden contract, the suite's assertions are
   re-designated "the system meets its captured contract" (not "same as monolith"),
   and CI stops running the live monolith in the gate (the frozen monolith can still be
   booted in a throwaway topology to re-derive the baseline — break-glass). *Trade-off:*
   this is the only honest answer once the referent disappears; the risk is a suite that
   quietly goes vacuous, guarded by the preserved negative checks + strict terminal
   assertions. The call for the user: confirm the conversion + that CI drops the live
   monolith from the order/gateway gate.
5. **Reversibility of the last extraction (DRQ-072) — RECOMMEND: reversible at the
   seam (`strangler.order.enabled`) right up to S9, proven exhaustively at S8; S9 is
   the FINAL irreversible move for the whole system; the frozen-not-deleted monolith is
   the break-glass referent (not a live production fallback), and the additive gateway +
   read model are independently revertible.** *Trade-off:* every prior extraction kept
   the monolith as a live fallback; this one cannot after S9, so we de-risk by proving
   reversibility one last time before the irreversible step and by freezing (not
   deleting) the monolith. The call for the user: accept that the final decommission is
   deliberately the one irreversible move (consistent with every prior decommission,
   now system-wide), de-risked by S8's reversibility proof + S9's frozen referent.

## Why this extraction is the hardest (the framing)
Review (ch.15) proved the loop on a REST leaf. Notification (ch.17) went event-driven.
Inventory (ch.19) decomposed a DB behind a synchronous gRPC seam. Payment (ch.23) made
checkout a **choreographed** saga and turned the synchronous `201/402` contract into an
async `202 + PENDING` (DRQ-047). Shipping (ch.24) added the **orchestrated** contrast
(Camel Saga EIP) and moved `CONFIRMED` one hop later. Every one of those extractions
was deliberately engineered to leave the **monolith standing as the fallback** and the
**equivalence suite anchored to it**. Order is the opposite on every axis:

1. **It is the god aggregate and the coordinator of the whole flow (SMELL #2).**
   `OrderService` is the single orchestration point for checkout; `OrderSagaListener`
   hosts the reactions for *all four* Kafka topics
   (`payment.captured`/`payment.declined`/`shipment.dispatched`/`shipment.failed`) and
   owns the reserved-line snapshot (`OrderItem`) that every saga's compensating
   `Release` depends on (DRQ-043/049/060). Extracting it means lifting the thing the
   other five extractions were careful to leave in place — the hub.
2. **There is no monolith left to be equivalent *to*.** For five extractions the
   Newman collection asserted "the extracted service behaves like the monolith." Once
   order leaves and the monolith is decommissioned, that referent is gone. The suite
   must convert to a contract/acceptance suite against a frozen golden baseline
   **without going vacuous** (DRQ-071) — the subtlest point in the whole book-build.
3. **There is no fallback to revert to.** Every prior cutover could flip back to a live
   monolith. The final decommission (S9) removes that for the entire system. The
   reversibility story changes from "revert to the live monolith" to "prove reversibility
   one last time, then freeze-not-delete as a break-glass referent" (DRQ-072).
4. **It must cure SMELL #2 and the last clause of SMELL #3, completing ACID→ACD for
   ALL contexts.** SMELLS.md already marks SMELL #3 cured *for payment and shipping*;
   the note explicitly says it is "NOT struck through … cured for payment and shipping,
   not yet for order, which is exactly the resume boundary ch.26 picks up." This chapter
   finally strikes it: the one remaining local `@Transactional` (order persistence +
   outbox write) must end up spanning only the **order service's own schema**.
5. **It introduces TWO net-new teaching surfaces at once — CQRS and a GraphQL
   gateway.** No prior extraction added a read-model projection or a second module. The
   CQRS write/read split (DRQ-067) and the SmallRye GraphQL aggregation gateway
   (DRQ-069) are both first-time machinery, layered on top of the hardest lift.
6. **The strangler itself completes.** `/api/orders` is the one path still routed to the
   monolith (StranglerProxyRoute); cutting it over is the last flag flip, after which the
   proxy has no host tree to strangle and becomes the permanent REST edge router (DRQ-070).

## What "the order extraction" moves, stated plainly (DRQ-066, with rejected alternatives)
**DECISION: the entire order bounded context moves to a new Quarkus
`examples/07-order-service`, re-shaped as a CQRS command/query split; a new
`examples/08-graphql-gateway` is added as the read-aggregation surface; the monolith is
then fully decommissioned (DRQ-070).** What moves, precisely:

- **The write (command) side** — `OrderService#placeOrder`: validate customer → reserve
  every line **synchronously over gRPC** (the `RemoteInventoryClient` gRPC client moves
  with the order context — it is order's collaborator, not a monolith fixture) → persist
  the order `PENDING` → emit `order.placed` via the **order service's own transactional
  outbox** (DRQ-073; the order service becomes the external producer of `order.placed`,
  replacing the monolith's `common/outbox/*` relay) → return (the controller maps to
  `202 Accepted`, DRQ-047 unchanged). The pre-handoff compensation `try/catch` (DRQ-042)
  moves with it.
- **The saga reactions** — the four `OrderSagaListener` reactions, lifted *unmodified in
  shape* (DRQ-074) but re-authored idiomatic (SmallRye Reactive Messaging `@Incoming`
  consumers in their own consumer group, not Spring `@KafkaListener`), including the
  idempotent status guards and the compensating gRPC `Release` on `payment.declined` /
  `shipment.failed` (DRQ-049/060/064). Reconciliation already names this as "exactly the
  surface ch.26 lifts into the extracted order service, unmodified in shape."
- **The Order aggregate** — `Order`/`OrderItem` (write model) + `OrderRepository`;
  `OrderStatus` vocabulary; `OrderDto`/`OrderCreate` request/response shapes; the
  `common/events/*` records (authored field-for-field in the order service, the "no
  shared code between reactors" precedent, DRQ-038).
- **Customer handling + FK decomposition (DRQ-068):** `Order`'s `@ManyToOne Customer`
  FK (SMELL #1) becomes a plain **`customerId` value** plus the denormalized **customer
  email snapshot** already carried in `OrderPlacedEvent`. The order service **owns the
  `customers` table** (lifted into its own schema) for `placeOrder`'s customer-exists
  validation — customer is de-facto order-context master data now that every other
  context is extracted and the shared `customers` table is being retired to frozen
  history. `orderId`/`customerId` are plain values on every event and DTO (they already
  are on the wire — only the JPA associations are decomposed).

*Rejected — leave the saga reactions in a surviving monolith shell.* That would keep the
monolith in the running topology forever and never cure SMELL #2; the whole point of the
last extraction is that the hub moves out and the monolith is decommissioned. Rejected.

*Rejected — a separate `customer-service` extraction.* Customer was never one of the six
roadmap contexts (build-plan §D names order/inventory/payment/shipping/notification/
review); spinning one up now is scope creep (a seventh extraction the roadmap doesn't
call for). Customer rides with the order context as its master data. Rejected.

## The CQRS shape, stated plainly (DRQ-067, with rejected alternatives)
**DECISION: a CQRS write-model / read-model split within the one order service and the
one Postgres database. Write model = the normalized `Order`/`OrderItem` aggregate +
command handlers (`placeOrder` and the lifecycle transitions). Read model = a
denormalized `order_view` table (one row per order) carrying everything a reader needs —
`orderId`, `customerId`, `status`, `totalCents`, `createdAt`, `shippingAddress`, a JSON
item summary, and the projected latest payment/shipment status — maintained by
*projecting* the same lifecycle events the saga reactions already handle. All read APIs
(REST `/api/orders**` and the GraphQL gateway) serve EXCLUSIVELY from the read model.**

- **Why a separate read model and not just a DTO projection off the aggregate:** the
  chapter's job is to *teach CQRS*. A real two-model split — a write model optimized for
  invariants/commands and a read model optimized for queries, updated on a separate path
  and **eventually consistent** — is the teaching. It makes read-after-write latency
  real (build-plan §G: "CQRS / read model → eventual-consistency read-after-write with
  bounded waits") and gives the GraphQL gateway a purpose-built surface.
- **Why same-DB two-table and not a second datastore:** scope discipline. A physically
  separate read store (Elasticsearch/a read replica) is the "real" production shape but is
  speculative infrastructure this teaching system does not need; same-DB two-table CQRS
  (Greg Young's "two models, one store" variant) teaches the split honestly with zero new
  infra. The read-model table is rebuildable from the aggregate (a documented recovery
  path), which is itself part of the CQRS lesson.
- **Why the projection piggybacks on the saga reactions:** the order service already
  reacts to `payment.captured/declined` + `shipment.dispatched/failed` to drive the
  lifecycle; the read-model projection updates `order_view` in the *same* reaction
  transaction (and on `placeOrder` for the initial `PENDING` row). This keeps the read
  model consistent with the write model's own committed state without a second event bus.

*Rejected — full event sourcing.* High build cost; build-plan T5/§C keep ES
"light/optional," and the Avro/registry machinery it would want is ch.28. Rejected.

*Rejected — no split (read straight off the aggregate, as the monolith's `toDto` does).*
It would ship a chapter titled CQRS that doesn't do CQRS; build-plan §C marks CQRS
load-bearing. Rejected.

## The GraphQL gateway scope, stated plainly (DRQ-069, with rejected alternatives)
**DECISION: a new, additive, data-less SmallRye GraphQL *aggregation* gateway
(`examples/08-graphql-gateway`) that answers one GraphQL question by stitching reads from
the five extracted services.** Schema (illustrative, finalized at S7):
`type Query { order(id: ID!): OrderView }` where `OrderView` carries the order read-model
fields plus `payments: [PaymentView]`, `shipments: [ShipmentView]`, `reviews: [ReviewView]`,
and per-item `stock: StockView` — each resolved by a `@Source` field resolver that calls
the owning service (order/payment/shipping/review over REST, inventory over gRPC). It owns
no data (mirrors the datamesh `graphql-gateway`).

- **It sits ALONGSIDE the REST surface + the strangler proxy, not replacing them.** The
  proxy is cutover/edge-routing for REST; the gateway is a distinct protocol edge for
  read-aggregation. Making the gateway additive means it cannot regress the
  equivalence/contract suite (which keeps asserting the REST surfaces), and it gets its
  **own front door** (its own port) rather than being threaded through the proxy.
- **Aggregation, not federation.** One gateway resolves fields by calling downstreams —
  no subgraph SDL on each service, no federation router. This matches datamesh and avoids
  a new infra component (scope discipline).

*Rejected — an Apollo-style federation router* (new infra + per-service subgraph schemas):
speculative infrastructure for a teaching system; aggregation teaches the same "one query,
many backends" lesson. Rejected.

*Rejected — order-only GraphQL:* too thin to teach the read-aggregation surface build-plan
§E row 6 calls for ("the GraphQL gateway as the CQRS read-aggregation surface"). Rejected.

## The monolith end-state + the strangler's completion, stated plainly (DRQ-070)
**DECISION: after S9 the monolith is fully decommissioned — all order Java code deleted
(as every prior context's was), the monolith removed from the running topology — but KEPT
frozen in-repo (DRQ-024) as the permanent "before" and the reproducible golden-baseline
referent. The strangler proxy sheds its strangler role and becomes the system's permanent
REST edge router: the `strangler.*.enabled` flags and the `strangler.monolith.base-url`
default backend are retired (there is no host tree to fall back to), and the `.choice()`
collapses to straight per-context routing to the six services.** This is the honest close
of the strangler-fig narrative — the fig now stands alone — and satisfies DRQ-024's
"kept permanently in-repo as the living 'before'." The shared `orders`/`order_items`/
`customers` tables follow the established treatment (kept as write-only history in the
frozen schema, same as reviews/notifications/inventory_items/payments/shipments).

## The equivalence→contract suite conversion, stated plainly (DRQ-071)
**DECISION: the behavior-equivalence suite converts from equivalence-vs-monolith into a
contract/acceptance suite against a frozen golden baseline.** The monolith is the referent
for the LAST time at S2 (final baseline capture) and S8 (cutover equivalence across the
seam). At S9's decommission: the captured baseline becomes the **frozen golden contract**;
the suite's assertions are re-designated "the system meets its captured contract" (no
longer "same as monolith"); CI stops bringing up the live monolith in the order/gateway
gate (the frozen monolith can still be booted in a throwaway topology to re-derive the
baseline — a break-glass audit path). New **Order Context Contract** and **GraphQL Gateway
Contract** folders are added. Non-vacuity is preserved exactly as before: strict terminal
assertions (Scenario 1 → CONFIRMED, 2 → 409, 3 → PAYMENT_DECLINED + net-zero, 4 →
SHIPPING_FAILED + net-zero) plus the negative checks (stop a consumer ⇒ stuck state ⇒ RED;
disable a compensation ⇒ non-net-zero ⇒ RED).

## What this extraction delivers (from build-plan §E row 6, §G, DRQ-032)
1. A new **Quarkus order service** at **`examples/07-order-service`** (recommended :8087)
   that **owns its own schema/database** (`orders`, `order_items`, `customers`, its
   `order_view` read model, and its own outbox table + Flyway), exposes the lifted REST
   **`/api/orders`** command+query surface, runs the **CQRS write/read split** (DRQ-067),
   consumes the four saga topics via SmallRye Reactive Messaging and drives the order
   lifecycle + compensations (the lifted `OrderSagaListener`, DRQ-074), reserves inventory
   over gRPC (`RemoteInventoryClient` moved in), and **produces `order.placed` via its own
   transactional outbox** (DRQ-073, mirroring DRQ-053). Adapted from datamesh's
   `order-service` with attribution (DRQ-032).
2. A new **SmallRye GraphQL aggregation gateway** at **`examples/08-graphql-gateway`**
   (recommended :8090) that owns no data and stitches `order → payments/shipments/reviews/
   stock` across the five services over REST + gRPC (DRQ-069). Adapted from datamesh's
   `graphql-gateway` with attribution (DRQ-032), widened from order+stock to the full
   cross-context view.
3. The **CQRS read model + event projection** (DRQ-067): the denormalized `order_view`
   table updated from the lifecycle events, rebuildable from the aggregate, serving all
   reads (REST + GraphQL), with read-after-write eventual consistency taught honestly.
4. The **strangler completes** (DRQ-070): proxy gains `strangler.order.enabled` for the
   `/api/orders` cutover (S8), then at S9 sheds its strangler role to become the permanent
   REST edge router; the monolith is fully decommissioned and frozen in-repo.
5. The **behavior-equivalence suite converts to a contract/acceptance suite** (DRQ-071):
   final monolith baseline captured (S2), equivalence proven across the seam (S8), then
   re-designated against the frozen golden contract with new Order + GraphQL contract
   folders (S9); negative checks preserved.
6. Tests at every tier + an **order/gateway contract gate** in CI (Postgres + Kafka +
   order service + gateway + payment + shipping + inventory gRPC services, **no live
   monolith**), red-then-green by disabling a compensation / a read-model projection; the
   **cross-service cascade** extended once more (order is now required by every
   full-suite gate). **SMELL #2 and SMELL #3 struck through; ACID→ACD realized for ALL
   contexts.**

## Decisions seeded by this plan (append to `_plans/decisions.md`, next free IDs after DRQ-065)
- **DRQ-066 — The order context (god `OrderService` + the four `OrderSagaListener`
  reactions + `Order`/`OrderItem`/`Customer` + the outbox) is extracted to a new Quarkus
  `examples/07-order-service`; `RemoteInventoryClient` (gRPC) moves with it; a new
  `examples/08-graphql-gateway` is added; the monolith is then fully decommissioned.**
  Customer FK decomposed to `customerId` value + email snapshot; the order service owns the
  `customers` table (DRQ-068). Rejected: a surviving monolith shell; a separate
  customer-service extraction (both as stated plainly above).
- **DRQ-067 — CQRS shape: separate denormalized `order_view` read model in the same DB,
  projected from the order-lifecycle events; write model = the `Order` aggregate.** All
  reads (REST + GraphQL) serve from the read model; read-after-write is eventually
  consistent; the read model is rebuildable from the aggregate. Rejected: full event
  sourcing (ch.21/ch.28); no-split DTO projection. *(NEEDS USER CONFIRMATION.)*
- **DRQ-068 — Customer FK decomposition + ownership.** `Order`'s `@ManyToOne Customer` FK
  (SMELL #1) becomes a plain `customerId` value + a denormalized email snapshot; the order
  service owns the lifted `customers` table for checkout validation. `orderId`/`customerId`
  are plain values on every event/DTO. The shared `customers` table is retired to frozen
  write-only history at S9 (same treatment as reviews/notifications/inventory_items/
  payments/shipments).
- **DRQ-069 — SmallRye GraphQL gateway: an additive, data-less *aggregation* gateway
  (`examples/08-graphql-gateway`) stitching order + payments + shipments + reviews + stock
  over REST + gRPC; sits alongside the proxy with its own front door; aggregation not
  Apollo-federation.** Rejected: a federation router (new infra); order-only GraphQL (too
  thin). *(NEEDS USER CONFIRMATION — gateway scope + own-front-door vs via-proxy.)*
- **DRQ-070 — Monolith end-state: fully decommissioned, frozen-not-deleted in-repo
  (DRQ-024) as the "before" + golden-baseline referent; the strangler proxy sheds its
  strangler role and becomes the permanent REST edge router (flags + monolith default
  backend retired).** The strangler fig completes. *(NEEDS USER CONFIRMATION.)*
- **DRQ-071 — Equivalence→contract suite conversion.** The suite converts from
  equivalence-vs-a-living-monolith to a contract/acceptance suite against a frozen golden
  baseline; monolith is the referent for the last time at S2/S8; at S9 the baseline is
  frozen, assertions re-designated, CI drops the live monolith from the order/gateway gate
  (frozen monolith re-bootable for break-glass). New Order + GraphQL contract folders;
  negative checks preserved. *(NEEDS USER CONFIRMATION.)*
- **DRQ-072 — Reversibility of the last extraction.** Reversible at the seam
  (`strangler.order.enabled`) until S9, proven exhaustively at S8; S9 is the FINAL
  irreversible move for the whole system; the frozen-not-deleted monolith is a break-glass
  referent (not a live fallback); the additive gateway + read model are independently
  revertible. *(NEEDS USER CONFIRMATION — accept the final irreversible step, de-risked.)*
- **DRQ-073 — Order service: two-phase read surface; command/reactions/projection/outbox
  idiomatic from the start; own schema; FKs decomposed; forward-filled; own transactional
  outbox.** `/api/orders` (`OrderController`/`OrderService`/`Order`+repo) follows DRQ-029
  Phase A (spring-compat lift) → Phase B (idiomatic Quarkus REST + Panache). The CQRS write
  model + read-model projection, the four SmallRye saga reactions, the gRPC inventory
  client, and the `order.placed` outbox producer have no Spring original worth preserving
  idiomatically, so they are authored **idiomatic from day one** (honest reading of
  DRQ-029/035, as every prior service's event machinery was). The order service **becomes
  the external producer of `order.placed`** via its own transactional outbox
  (`OrderOutboxEvent`/`OrderOutboxRelay`, mirroring DRQ-053) — no dual-write. Owned store
  forward-filled; **no CDC backfill** (contrast inventory/DRQ-040 — orders are created
  forward at checkout time, not migrated; same justification as payment/DRQ-052,
  shipping/DRQ-063).
- **DRQ-074 — Idempotency, at-least-once & the read-model projection (reuses DRQ-034/037/
  051/064).** The four lifted saga reactions keep their status-transition idempotency guards
  (a redelivered event is a no-op once the order left the expected state; the compensating
  `Release` fires at most once per order). The **read-model projection is idempotent by
  `orderId`** (an upsert keyed on orderId, so a redelivered event or a replay re-derives the
  same `order_view` row); the read model is **rebuildable from the aggregate** (a documented
  recovery path, not a saga ledger). Honest limitation (reaffirming DRQ-051/057/064): no
  full saga ledger / no idempotency key on `Release`; read-after-write is eventually
  consistent by design.
- **DRQ-075 — SMELL #2 & #3 fully cured; ACID→ACD realized for ALL contexts.** SMELL #2
  (god `OrderService`): cured — the order aggregate is an independently deployable service
  with a clean CQRS split; it no longer reaches into other modules (every prior extraction
  removed one dependency; this removes the last — its own ownership). SMELL #3 (one
  in-process ACID transaction): the LAST clause struck — the one remaining local
  `@Transactional` (order persistence + read-model projection + the order-owned outbox
  write) now spans ONLY the order service's own schema; all cross-context consistency is
  the sagas + explicit compensations. SMELLS.md #2 and #3 marked fully cured with evidence.

## Standing constraints applied to every step
Scope discipline (nothing here that an r08 ch.26 deliverable doesn't require — no event
sourcing [ch.21 light/optional; Avro ch.28], no second read datastore [same-DB CQRS,
DRQ-067], no Apollo federation router [aggregation gateway, DRQ-069], no Apicurio/Avro
[ch.28], no MicroProfile-chassis deep dive [ch.27], no full saga ledger/state-machine
beyond what the four scenarios require, no seventh customer-service extraction); conceptual
coherence (the CQRS split and the gateway are each one readable artifact; the lifted
reactions keep their proven shape); security-by-design (OWASP/CIS — secrets hygiene, no
creds in git, Kafka/DB creds from env/Bitwarden, secure-by-default Camel dynamic-URI
allow-list, no request-derived routing targets, GraphQL query-depth/complexity bounded so
the aggregation surface is not an amplification vector); **SIMPLE git only —
`git -C <dir> …`, never `cd && git`**; **never push beyond
`github.com/patterncatalyst/modernizing-enterprise-applications` without explicit user
permission**; Conventional Commits (`feat`/`fix`/`docs`/`chore`/`refactor`/`ci`/`test`/
`site` + scopes `rNN.x`, `§NN`, service names); **NO attribution trailers**. A subagent
does **not** inherit a loaded skill — each executor prompt must explicitly invoke/read the
named skill. Base package is `dev.patterncatalyst.*` (monolith `dev.patterncatalyst.monolith`,
services `dev.patterncatalyst.order` / `dev.patterncatalyst.gateway`).

## Parallelism overview
```
S1 (frame + decisions, SEQUENTIAL, must be first; the five confirmations surfaced to the user)
S2 equivalence suite: FINAL baseline vs the monolith; add Order Context Contract folder;
   stage the contract-suite conversion + GraphQL contract folder (pending)              [Opus gate]  ← THE crux (suite-conversion honesty)
 ├─ S3 event contract + shared vocabulary: order svc becomes external order.placed producer;
 │     Topics/events/DTOs authored in the order service (field-for-field) ──────────────┐  (parallel after S1)
 └─ S4 order svc scaffold + Phase A read-surface lift (own schema; FK→value decompositions) ─┘ (lane N, parallel) [Opus gate]
S5 order svc Phase B idiomatic + CQRS WRITE model (placeOrder command) + lifted SmallRye
   saga reactions + own transactional outbox(order.placed) + gRPC inventory client; measured
   (SEQ after S3+S4)                                                                     [Opus gate]  ← HARD PART (lifting the god service)
S6 CQRS READ model + event projection (order_view, rebuildable, read-after-write); reads served
   from the read model (SEQ after S5)                                                    [Opus gate]  ← HARD PART (the CQRS teaching core)
S7 GraphQL gateway: examples/08-graphql-gateway (SmallRye GraphQL aggregation, own front door)
   (SEQ after S6 — needs the order read API) [Opus gate]
S8 strangler proxy: order flag + /api/orders route (transparent) (after S3+S5, may overlap S6/S7)
S9a CUTOVER: flip strangler.order.enabled; full suite green across the seam (4 scenarios +
   Order + GraphQL contract); reversibility; negative checks (SEQ after S6+S7+S8) [Opus gate — equivalence gate]  ← HARD PARTS
S9b DECOMMISSION the monolith (full) + proxy→edge router + suite→contract (frozen golden baseline)
   + SMELLS #2/#3 struck + ACID→ACD for ALL (SEQ after S9a) [Opus gate]  ← HARD PARTS (the final irreversible move)
S10 Code-CI: order/gateway contract gate (NO live monolith) red-then-green; cross-service
    cascade into sibling gates (SEQ after S9b)                                           [Opus gate]
 ├─ S11 ch.26 diagram(s) ───┐   (PARALLEL after S6/S7)
 └─ S12 ch.26 ADLC trace ───┘   (PARALLEL after S9b evidence exists)
S13 ch.26 authored to the bar (SEQ after S9b + S11 + S12)   [Opus gate — 2k + footer]
S14 reconcile + status + exit (SEQ, last)                   [Opus gate]
```
*(Step IDs: S9a/S9b are the cutover and decommission; they are counted as the 9th and 10th
of the 15 numbered steps below — S1…S8, S9 (= S9a CUTOVER), S10 (= S9b DECOMMISSION),
S11 (= Code-CI), S12 (diagrams), S13 (trace), S14 (author), S15 (reconcile). The diagram
above uses S9a/S9b for readability; the step headings below use the canonical S1…S15.)*

**Single-writer / serialize (§K):** the monolith reactor `pom.xml` + `order/*` +
`common/*` + `application.yml` + Flyway migrations + `SixContextsSmokeTest`
(S10/decommission — lane M, one writer); `examples/01-strangler-proxy/`
`application.properties` + `StranglerProxyRoute.java` (S8 add the order branch, S10 shed the
strangler role — append/collapse, don't rewrite blindly); `common/Topics.java` + the shared
event/DTO vocabulary the order service re-authors (S3 — single contract writer);
`tooling/newman/mea.postman_collection.json` (S2 — versioned with the monolith, R8; converted
at S10); `.github/workflows/code-ci.yml` (S11); `assets/diagrams/README.md` catalogue (S12);
the three `_plans/*` ledgers (S1, S15). `compose.yaml`/`.env` already run Kafka (ch.17) + the
inventory gRPC server (ch.19) + payment/shipping services — **no new infra container is
needed**; S-steps add the order-service + gateway processes and (at S10) *remove* the
monolith from the running topology.

---

## THE HARD PARTS (called out explicitly, per the brief)

**H1 — Lifting the god aggregate + its four saga reactions without regression (S5,
DRQ-066/074).** `OrderService` and `OrderSagaListener` are the hub: the reactions own the
reserved-line snapshot and the compensating `Release` for BOTH sagas, with carefully-argued
mutual-exclusion (payment decline only from `PENDING`; shipping failure only from
`AWAITING_SHIPMENT`) and at-most-once-Release guarantees (see the real `OrderSagaListener`
javadoc). Lifting them to a new service — idiomatic SmallRye consumers, own consumer group,
own gRPC client, own outbox — must preserve those guarantees exactly. Mitigation: lift the
reactions *unmodified in shape* (reconciliation says they are "ready to build on without
re-deriving"); re-assert the status-guard idempotency + at-most-once-Release in unit tests
before any end-to-end run; S2 baselines all four scenarios green vs the monolith first.

**H2 — The CQRS read model + projection that is a genuine teaching example, net-zero-safe,
and non-vacuous (S6, DRQ-067/074 — a crux).** The read model must be updated on a separate
path from the write model and be eventually consistent, yet the equivalence/contract suite
asserts read-after-write outcomes. Two traps: (i) a read that silently falls back to the
aggregate would hide a broken projection (the suite would stay green while CQRS does
nothing) — guard with a negative check: disable the projection ⇒ reads stop reflecting the
terminal state ⇒ the bounded-wait scenario goes RED; (ii) a too-short bounded-wait budget
goes falsely RED on the now-doubled (write-then-project) latency — tune the budget, do not
relax the terminal assertion. Mitigation: reads serve EXCLUSIVELY from the read model (no
aggregate fallback), the projection is idempotent/rebuildable (DRQ-074), and S6's acceptance
includes the projection-disabled negative check.

**H3 — Converting the equivalence suite without a monolith to anchor it, non-vacuously
(S2/S10, DRQ-071 — THE subtlest point).** For five extractions "green" meant "same as the
monolith." After S10 there is no monolith in the gate. If the conversion is done carelessly,
the suite becomes "assert the system agrees with itself" — vacuous. Mitigation: capture the
FINAL monolith baseline at S2 and freeze it as the golden contract; the assertions stay the
same strict terminal checks (CONFIRMED / 409 / PAYMENT_DECLINED+net-zero / SHIPPING_FAILED+
net-zero / the read-model + GraphQL shapes); the negative checks (consumer-down ⇒ stuck ⇒
RED; compensation-disabled ⇒ non-net-zero ⇒ RED; projection-disabled ⇒ stale read ⇒ RED) are
*preserved and run in CI*, which is what proves non-vacuity when the referent is gone; the
frozen monolith is re-bootable to re-derive the baseline (break-glass). S2 is an explicit
`[Opus gate]`.

**H4 — The final, irreversible decommission with no live fallback (S9/S10, DRQ-070/072 —
biggest risk).** Every prior cutover could flip back to a live monolith; this one cannot
after S10. If the extracted order service is subtly wrong and the monolith is already gone,
there is nothing to revert to in production. Mitigation: prove reversibility exhaustively at
S9 (flip `strangler.order.enabled` back → the monolith serves checkout again, full suite
green) BEFORE the irreversible S10; freeze-not-delete the monolith (DRQ-070) so it can be
re-booted for break-glass diffing; keep the gateway + read model additive and independently
revertible; make S10 the single, deliberately-irreversible move (consistent with every prior
decommission, now system-wide) only after S9's green reversibility proof.

**H5 — Curing SMELL #2 and the last clause of SMELL #3, with evidence (S10, DRQ-075).** The
chapter must *demonstrably* cure the god service and strike the one-ACID-transaction smell
for order, completing ACID→ACD for ALL contexts. The risk is a decommission that *looks*
done but leaves an orphaned in-process path or a `@Transactional` that still reaches cross-
context. Mitigation: `SixContextsSmokeTest` asserts `/api/orders` 404s on the monolith; the
order service's one remaining local `@Transactional` provably spans only its own schema
(reviewed at the Opus gate); SMELLS.md #2 and #3 struck through with the test/commit
evidence trail.

---

## S1 — Frame, decisions, surface the five confirmations  *(SEQUENTIAL — must be first)*
- **(b) Goal / DoD:** The ch.26 outcome + acceptance criteria are framed; **DRQ-066…075**
  are appended to `decisions.md` (esp. the five flagged decisions — CQRS shape DRQ-067,
  GraphQL scope DRQ-069, monolith end-state DRQ-070, equivalence-suite fate DRQ-071,
  reversibility DRQ-072); a `build-plan.md` status row for r08/ch.26 is set to "in progress"
  (and §E row 6 updated from "not started"); the version matrix gains forward rows
  (order-service `quarkus-messaging-kafka` + `quarkus-grpc` + Panache; gateway
  `quarkus-smallrye-graphql`; reuse of the existing Kafka broker tag). **The five "Decisions
  needing confirmation" are surfaced to the user for sign-off before S2 begins.**
- **(c) Creates/touches:** `_plans/decisions.md` (DRQ-066…075 + matrix rows),
  `_plans/build-plan.md` (status row + §E row 6 status). No code. (Branch: per shipping's
  precedent, work stays on `r02-walking-skeleton`; scopes use `r08.x` / `§26`.)
- **(d) Skills/MCP:** none (ledger authoring). `git -C <dir>` for status only.
- **(e) Deps / parallel:** none; SEQUENTIAL, gates everything.
- **(f) Collision risk:** `_plans/*` single-writer — this and S15 are the only ledger writers.
- **(g) Acceptance:** DRQ-066…075 present; the five confirmations flagged for the user with
  recommended defaults + trade-offs; build-plan §E row 6 marked in-progress.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(r08.x): frame ch.26 order + GraphQL gateway extraction (CQRS, monolith decommission); seed DRQ-066..075; surface the five confirmations`

## S2 — Equivalence suite: FINAL baseline vs the monolith + stage the contract conversion  *(SEQUENTIAL, after S1)*  **[Opus gate]**  — **HARD PART H3 — THE crux**
- **(b) Goal / DoD:** Capture the **last** monolith-anchored baseline and prepare the suite's
  conversion (DRQ-071): confirm the four checkout scenarios are green against the
  still-present monolith order context — **Scenario 1** (bounded-wait → `CONFIRMED`),
  **Scenario 2** (out-of-stock → `409`), **Scenario 3** (payment-declined → `PAYMENT_DECLINED`
  + inventory net-zero), **Scenario 4** (shipping-failure → `SHIPPING_FAILED` + net-zero) —
  plus the existing Review/Notification/Inventory/Payment/Shipping context contract folders.
  Add a new **"Order Context Contract"** folder asserting `/api/orders` (POST → `202` +
  `Location`; `GET /api/orders/{id}` shape incl. the read-model fields; `GET /api/orders`
  list; `404` on unknown id). Stage (but mark pending/`disabled` until S7) a **"GraphQL
  Gateway Contract"** folder asserting the `order(id)` aggregated query shape. Freeze this
  run as the **golden baseline artifact** (committed) that S10 will re-designate as the
  contract. Document explicitly that this is the LAST monolith-anchored baseline.
- **(c) Creates/touches:** `tooling/newman/mea.postman_collection.json` (add Order Context
  Contract folder; stage the GraphQL folder pending; scenarios 1–4 unchanged assertions),
  `tooling/newman/order-service.postman_environment.json` +
  `tooling/newman/graphql-gateway.postman_environment.json` (forward-ref envs),
  a committed `tooling/newman/GOLDEN-BASELINE.md` capturing the frozen baseline + the
  conversion plan. **Collection versioned with the monolith (R8).**
- **(d) Skills/MCP:** Newman (reuse the bounded-wait idiom from Scenario 1c/3c/4 / DRQ-037/
  055/065). Runs against the monolith on :8080 (via the proxy :8888 for read folders).
- **(e) Deps / parallel:** after S1; SEQUENTIAL (must baseline before any behavior changes).
- **(f) Collision risk:** low (own `tooling/`), but it is the contract that every later run
  rests on — and the honesty of the whole *last* extraction rests here.
- **(g) Acceptance:** the suite is **green against the unmodified monolith** (all four
  scenarios + every context-contract folder), the Order Context Contract folder is in place,
  the GraphQL folder is staged pending, and the golden baseline is committed. **[Opus gate]:**
  Opus confirms the assertions still strictly assert terminal status + side-effects (not "any
  2xx"), the golden baseline is captured faithfully, and the staged conversion cannot become
  vacuous (the negative checks are carried forward, not dropped).
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `test(equivalence): final monolith baseline + Order Context Contract; stage GraphQL contract + the equivalence→contract conversion (golden baseline frozen)`

## S3 — Event contract + shared vocabulary: order service becomes the external `order.placed` producer  *(PARALLEL, after S1)*
- **(b) Goal / DoD:** Settle where the shared vocabulary lives once the monolith is gone
  (DRQ-073). The order service authors its own **field-for-field** `OrderStatus`,
  `OrderDto`/`OrderCreate`, the `order.placed` payload (`OrderPlacedEvent`), and the four
  consumed event records (`PaymentCaptured`/`PaymentDeclined`/`ShipmentDispatched`/
  `ShipmentFailed`) — the "no shared code between reactors" precedent (DRQ-038), so no module
  depends on the monolith's `common/*`. Document the full wire contract: the order service
  **produces** `order.placed` (via its own outbox, replacing the monolith's `common/outbox`
  relay) and **consumes** the four saga outcomes; `common/Topics.java` names stay the registry
  of record until the monolith is frozen. JSON per DRQ-038 (Avro/Apicurio still ch.28).
- **(c) Creates/touches:** the order service's own event/DTO records (authored under
  `examples/07-order-service/` in S4's scaffold), a short in-service `EVENTS.md` documenting
  producer/consumer for every topic (order.placed ← order svc; the four outcomes → order
  svc). `common/Topics.java` is read, not rewritten, here (single contract writer; coordinate
  so S10's decommission is the only step that touches it in lane M).
- **(d) Skills/MCP:** none new (plain records + topic docs).
- **(e) Deps / parallel:** after S1; **PARALLEL with S4**. Feeds S5, S6, S7, S8.
- **(f) Collision risk:** low — authored inside the new module; `Topics.java` read-only here.
- **(g) Acceptance:** the order service's event/DTO records match the established wire shapes
  field-for-field; producer/consumer mapping documented (order svc is the `order.placed`
  producer); no dependency on the monolith `common/*`.
- **(h) Tier:** Sonnet. No Opus gate (proven in S5/S6).
- **(i) Checkpoint commit:** `feat(order): event contract + shared vocabulary — order service as the external order.placed producer; field-for-field consumed events (DRQ-038)`

## S4 — Order service: scaffold + Phase A read-surface lift (own schema; FK decompositions)  *(PARALLEL lane N, after S1)*  **[Opus gate]**
- **(b) Goal / DoD:** A new Quarkus module `examples/07-order-service` (recommended :8087)
  that **owns its own schema/database** (`orders`, `order_items`, `customers`, + the
  `order_view` read-model table reserved for S6, + its outbox table + Flyway). The monolith's
  Spring read surface is **lifted via Quarkiverse Spring-compat** (`quarkus-spring-web`/`-di`/
  `-data-jpa`): `OrderController` → `/api/orders` (POST/GET/{id}/GET), `OrderService.getById/
  listAll/placeOrder`, `Order`/`OrderItem` + repo, `Customer` + repo. **FK decompositions
  (DRQ-068):** `Order`'s `@ManyToOne Customer` → plain `customerId` value + email snapshot;
  `OrderItem` already carries the denormalized sku/name/price snapshot (DRQ-043, keep). The
  gRPC `RemoteInventoryClient` is scaffolded in (the inventory `.proto` + client) but the
  live event/outbox/projection wiring is deferred to S5/S6. Owned store starts empty,
  forward-filled — **no CDC backfill** (contrast inventory/DRQ-040), documented as deliberate.
- **(c) Creates/touches:** `examples/07-order-service/**` (pom, `application.properties` on
  :8087 with its own datasource + Flyway + gRPC client config, controller/service/entities/
  repos, tests), `src/main/docker/*`. Isolated subtree.
- **(d) Skills/MCP:** **quarkus-agent** — `quarkus_skills` against the monolith dir to
  discover + follow **`migrate-spring-to-quarkus`** (do NOT self-plan the migration);
  `quarkus_create`/`quarkus_start`/`quarkus_searchDocs`; **lgtm-quarkus**.
- **(e) Deps / parallel:** scaffold after S1; **PARALLEL with S3**. Isolated dir.
- **(f) Collision risk:** low — isolated under `examples/07-order-service/`.
- **(g) Acceptance:** service boots on :8087 against its own DB; `/api/orders` read contract
  matches the monolith's `OrderDto` shape byte-for-byte; Spring-compat extensions present
  (Phase A); `Order` holds `customerId` as a value (no cross-context FK), the order service
  owns `customers`; unit tests green. **[Opus gate]:** Opus confirms it owns its schema (no
  reach into the shared monolith tables), the read contract matches, and the FKs are
  decomposed to value references.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(order): Phase A — lift /api/orders onto Quarkus via Spring-compat; own schema (orders/order_items/customers); customerId value (FK decomposed)`

## S5 — Order service: Phase B idiomatic + CQRS write model + lifted saga reactions + own outbox, measured  *(SEQUENTIAL, after S3 + S4)*  **[Opus gate]**  — **HARD PART H1 (lifting the god service)**
- **(b) Goal / DoD:** Refactor the read/command surface off the compat shim to **idiomatic
  Quarkus** (Quarkus REST + Panache, native CDI) and author the **net-new core (idiomatic
  from start, DRQ-073):** the **CQRS write model** — `placeOrder` as a command handler
  (validate customer → reserve every line over **gRPC** via the moved `RemoteInventoryClient`
  → persist the `Order` `PENDING` → write `order.placed` to the order service's **own
  transactional outbox**; the pre-handoff compensation `catch` moves in unchanged, DRQ-042);
  the **four lifted saga reactions** as SmallRye Reactive Messaging `@Incoming` consumers in
  their own consumer group (`onPaymentCaptured` → `AWAITING_SHIPMENT`; `onShipmentDispatched`
  → `CONFIRMED`; `onShipmentFailed` → `SHIPPING_FAILED` + compensating `Release`;
  `onPaymentDeclined` → `PAYMENT_DECLINED` + compensating `Release`), **preserving the
  status-guard idempotency + at-most-once-Release + mutual-exclusion** guarantees exactly
  (DRQ-074, lifted unmodified in shape); the **`OrderOutboxEvent`/`OrderOutboxRelay`** (own
  outbox, no dual-write, DRQ-053-style). Capture **measured before/after** (startup/RSS/
  native) as `MIGRATION.md`. **Adapt from datamesh `order-service` with attribution
  (DRQ-032).** (The read model + projection are S6.)
- **(c) Creates/touches:** `examples/07-order-service/**` — pom (remove spring-compat; add
  `quarkus-messaging-kafka`, `quarkus-grpc`, `quarkus-hibernate-orm-panache`,
  `quarkus-rest(+jackson)`, `quarkus-scheduler` for the outbox relay, `quarkus-smallrye-health`),
  `application.properties` (incoming the four outcome channels + outgoing `order.placed`
  channel + inventory gRPC target), the command handler, the four `@Incoming` reactions, the
  order outbox + relay, the gRPC client, Panache refactor, `MIGRATION.md`, Citrus/`@QuarkusTest`
  + Dev Services tests (a checkout→order.placed round-trip; a payment.captured→AWAITING_SHIPMENT,
  shipment.dispatched→CONFIRMED happy chain; a payment.declined→Release and a
  shipment.failed→Release compensation round-trip; an idempotent-redelivery test asserting
  at-most-once Release; a mutual-exclusion unit test).
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills messaging,kafka,grpc,panache`;
  `quarkus_searchDocs`), **lgtm-quarkus**; Dev Services for Kafka + PG in tests.
- **(e) Deps / parallel:** after S3 (contract) + S4 (service). SEQUENTIAL.
- **(f) Collision risk:** low (same isolated module).
- **(g) Acceptance:** `placeOrder` validates/reserves-over-gRPC/persists-PENDING/emits
  `order.placed` via the outbox atomically; the four reactions drive the lifecycle with the
  SAME idempotency + at-most-once-Release + mutual-exclusion guarantees as the monolith
  (verified by tests); a redelivered event is a no-op; `/api/orders` read contract unchanged;
  before/after metrics captured (real runs). **[Opus gate]:** Opus confirms the outbox makes
  `order.placed` atomic with the DB write (no dual-write), the lifted reactions preserve
  at-most-once Release + mutual exclusion, and the pre-handoff catch cannot double-compensate
  with the decline/shipment-failed reactions.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(order): Phase B — idiomatic Quarkus + CQRS write model (placeOrder command) + lifted SmallRye saga reactions + own order.placed outbox + gRPC inventory client (idempotent, measured)`

## S6 — CQRS read model + event projection (read-after-write, rebuildable)  *(SEQUENTIAL, after S5)*  **[Opus gate]**  — **HARD PART H2 (the CQRS teaching core)**
- **(b) Goal / DoD:** Add the **denormalized `order_view` read model** (DRQ-067): a table
  with one row per order carrying `orderId`, `customerId`, `status`, `totalCents`,
  `createdAt`, `shippingAddress`, a JSON item summary, and the projected latest
  payment/shipment status. A **projection** updates `order_view` in the SAME transaction as
  each write: on `placeOrder` (initial `PENDING` row) and inside each of the four reactions
  (on every lifecycle transition). **All reads (`/api/orders` REST + the GraphQL gateway S7)
  serve EXCLUSIVELY from the read model** — no fallback to the aggregate (that would hide a
  broken projection). The projection is **idempotent by `orderId`** (upsert) and the read
  model is **rebuildable from the aggregate** (a documented recovery endpoint/command,
  DRQ-074). Teach read-after-write eventual consistency honestly: the read model is updated
  on the write path here (same-tx), so it is strongly consistent within the service, but the
  *cross-service* outcomes (CONFIRMED after the shipping hop) are still eventually consistent
  — the bounded-wait scenarios cover that.
- **(c) Creates/touches:** `examples/07-order-service/**` — a Flyway migration for `order_view`,
  the projection component (invoked from `placeOrder` + the four reactions), the read side of
  `OrderService`/`OrderController` repointed to the read model, a rebuild command/endpoint,
  tests (read-after-write assertions; a projection-disabled negative test proving reads go
  stale ⇒ the scenario would go RED; a rebuild-from-aggregate test).
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills panache`; `quarkus_searchDocs
  "cqrs read model"`), **lgtm-quarkus**; Dev Services for PG in tests.
- **(e) Deps / parallel:** after S5. SEQUENTIAL.
- **(f) Collision risk:** low (same isolated module).
- **(g) Acceptance:** every read is served from `order_view`; a placed order's read reflects
  `PENDING` immediately and transitions as the reactions project; disabling the projection
  makes reads go stale (the negative check that proves CQRS is genuinely exercised); the read
  model rebuilds correctly from the aggregate. **[Opus gate]:** Opus confirms reads never fall
  back to the aggregate, the projection is idempotent + rebuildable, and the
  projection-disabled negative check genuinely goes RED (non-vacuity of the CQRS teaching).
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(order): CQRS read model — denormalized order_view projected from lifecycle events (rebuildable, idempotent); reads served exclusively from the read model`

## S7 — GraphQL gateway: `examples/08-graphql-gateway` (SmallRye GraphQL aggregation)  *(SEQUENTIAL, after S6)*  **[Opus gate]**
- **(b) Goal / DoD:** A new Quarkus module `examples/08-graphql-gateway` (recommended :8090)
  that **owns no data** and exposes a SmallRye GraphQL aggregation surface (DRQ-069):
  `type Query { order(id: ID!): OrderView }` with `OrderView` = the order read-model fields +
  `payments: [PaymentView]` (payment svc REST) + `shipments: [ShipmentView]` (shipping svc
  REST) + `reviews: [ReviewView]` (review svc REST) + per-item `stock: StockView` (inventory
  svc **gRPC**), each resolved by a `@Source` field resolver calling the owning service.
  Secure-by-design: **query depth/complexity bounded** so the aggregation is not an
  amplification vector; downstream targets are fixed operator config, never request-derived.
  Enable the staged "GraphQL Gateway Contract" Newman folder (from S2). **Adapt from datamesh
  `graphql-gateway` with attribution (DRQ-032)**, widened from order+stock to the full view.
- **(c) Creates/touches:** `examples/08-graphql-gateway/**` (pom with
  `quarkus-smallrye-graphql` + REST clients + `quarkus-grpc` client, `application.properties`
  on :8090 with the five downstream targets, `@GraphQLApi` + the `OrderView`/field resolvers +
  the REST/gRPC clients, tests), `src/main/docker/*`. Enables the GraphQL folder in
  `tooling/newman/mea.postman_collection.json`. Isolated subtree + the one collection edit.
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills graphql`; `quarkus_searchDocs
  "smallrye graphql"` for `@Query`/`@Source`/aggregation), **lgtm-quarkus**; Dev Services /
  mock downstreams in tests (per datamesh's `MockInventoryService` pattern).
- **(e) Deps / parallel:** after S6 (needs the order read API). SEQUENTIAL.
- **(f) Collision risk:** low (isolated module + one collection folder enable).
- **(g) Acceptance:** `query { order(id:…) { … payments{…} shipments{…} reviews{…}
  items{ stock{…} } } }` returns a correctly-stitched view from the five services; depth/
  complexity bounds enforced; the GraphQL Gateway Contract folder passes; the gateway owns no
  data. **[Opus gate]:** Opus confirms the gateway is aggregation-only (no data ownership),
  the stitching is correct across REST + gRPC, and the security bounds (depth/complexity,
  fixed targets) are in place.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(gateway): SmallRye GraphQL aggregation gateway — order stitched with payments/shipments/reviews/stock over REST+gRPC (owns no data; depth/complexity bounded)`

## S8 — Strangler proxy: order flag + /api/orders route (transparent)  *(SEQUENTIAL, after S3 + S5; may overlap S6/S7)*
- **(b) Goal / DoD:** Append to `StranglerProxyRoute` a `strangler.order.enabled` flag
  (default **false** → monolith) + `strangler.order.base-url` (:8087), content-based routing
  on the **full `/api/orders`** path prefix (heeding the Review `/reviews`-prefix bug, applied
  a sixth time), reverse-proxied transparently. **ACL honesty (DRQ-065 precedent):** `OrderDto`
  is byte-for-byte portable (the order service lifts it unchanged), so **no `OrderAclRoute`
  translator** — follow the honest transparent-proxy precedent set by Inventory/Payment/
  Shipping (the proxy javadoc already argues a no-op translator is a speculative-infra trap).
  This flag routes BOTH the POST (checkout command) and the GET reads, since the whole order
  context moves at once. Secure-by-default: only the fixed operator-configured target.
- **(c) Creates/touches:** `examples/01-strangler-proxy/.../StranglerProxyRoute.java` (append
  the order flag/branch to the existing `.choice()` — do not rewrite), `application.properties`
  (flag + base-url). Tests mirroring the existing pattern (`OrderFlagOnProfile`/`OrderFlagOffProfile`
  + `OrderRouteFlagOnTest`/`...OffTest` + `StubBackendServer`). Single writer of the proxy.
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (`camel_route_context`,
  `camel_validate_route`, `camel_render_route_diagram`).
- **(e) Deps / parallel:** after S3 (contract) + S5 (order service command/reactions). May
  overlap S6/S7.
- **(f) Collision risk:** med — single writer of the proxy route + properties (shares the file
  with five existing flags; append, don't rewrite).
- **(g) Acceptance:** with the flag **false**, `/api/orders` still reaches the monolith and the
  full suite is green through :8888; `camel_validate_route` clean; a Citrus/route test proves
  the full `/api/orders` prefix match and transparent pass-through (and documents the
  no-translator decision).
- **(h) Tier:** Sonnet. No Opus gate (proof is S9).
- **(i) Checkpoint commit:** `feat(strangler): order flag + /api/orders route (transparent reverse proxy; ACL honesty — no translator, DTO identical)`

## S9 — CUTOVER: flip the order flag; suite green across the seam; reversibility; negative checks  *(SEQUENTIAL, after S6 + S7 + S8)*  **[Opus gate — equivalence gate]**  — **HARD PARTS H3/H4**
- **(b) Goal / DoD:** Flip `strangler.order.enabled=true` (the final cutover flag; `/api/orders`
  — checkout command AND reads — served by the order service; the GraphQL gateway already live
  alongside). Run the **full equivalence suite through the proxy** (:8888): **Scenario 1
  bounded-waits to `CONFIRMED`** across the full chain (now order.placed is produced BY the
  order service), **Scenario 2** out-of-stock `409`, **Scenario 3** `PAYMENT_DECLINED` +
  net-zero, **Scenario 4** `SHIPPING_FAILED` + net-zero, plus the Order Context Contract and
  GraphQL Gateway Contract folders. Demonstrate **reversibility** (flip back → the monolith
  serves checkout again, full suite green — the LAST time this is possible, DRQ-072). Run the
  **negative checks**: stop the order service's saga consumer ⇒ orders stuck `PENDING`/
  `AWAITING_SHIPMENT` ⇒ Scenario 1 RED; disable the read-model projection ⇒ reads go stale ⇒
  RED (the CQRS non-vacuity check, H2); disable a compensation ⇒ non-net-zero ⇒ RED.
- **(c) Creates/touches:** flag config only (`strangler.order.enabled`); a
  `demos/demo-order-cutover.sh`; evidence appended to a new
  `examples/07-order-service/CUTOVER.md` (reversibility baseline, cutover run with the
  bounded-wait proofs, all three negative checks).
- **(d) Skills/MCP:** **camel-mcp** (`camel_runtime_*` to confirm routing), Newman re-run,
  Kafka consumer-lag/topic inspection to show the full flow; the GraphQL gateway hit directly.
- **(e) Deps / parallel:** after S6 + S7 + S8. SEQUENTIAL.
- **(f) Collision risk:** touches the one flag; no code rewrite.
- **(g) Acceptance:** suite green through the proxy in the cutover state (all four scenarios +
  both new contract folders); **reversibility demonstrated one last time**; all three negative
  checks RED when their mechanism is disabled. **[Opus gate — equivalence gate]:** human/Opus
  signs off that the extracted order service + gateway are genuinely equivalent across the seam
  — especially that checkout still converges to the correct terminal state, that a decline/
  shipping-failure still ends net-zero, and that the read model + GraphQL view are correct —
  BEFORE the irreversible S10.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(order): flag-gated cutover to the extracted order service + GraphQL gateway; equivalence green across the seam, reversibility + negative checks (the last reversible state)`

## S10 — DECOMMISSION the monolith (full) + proxy→edge router + suite→contract + strike SMELL #2/#3  *(SEQUENTIAL, after S9)*  **[Opus gate]**  — **HARD PARTS H4/H5 — the final irreversible move**
- **(b) Goal / DoD:** Make the order service the **sole owner** of the order context and
  **fully decommission the monolith** (DRQ-070): delete the monolith's `order/*`
  (`OrderService`/`OrderController`/`Order`/`OrderItem`/`OrderRepository`/`OrderSagaListener`),
  the moved `inventory/RemoteInventoryClient`, the `common/outbox/*` relay, and the
  now-orphaned `common/*` vocabulary; retire the shared `orders`/`order_items`/`customers`
  tables to frozen write-only history (keep-and-stop-writing, same treatment as the other five
  contexts). The monolith is **removed from the running topology** but **kept frozen in-repo**
  (DRQ-024) as the "before" + golden-baseline referent. **The strangler proxy sheds its
  strangler role → permanent REST edge router:** collapse the `.choice()` to straight
  per-context routing to the six services, retire the `strangler.*.enabled` flags and the
  `strangler.monolith.base-url` default backend (there is no host tree to fall back to).
  **Convert the equivalence suite → contract/acceptance suite** (DRQ-071): re-designate the
  frozen S2 golden baseline as the contract; the suite's assertions now mean "the system meets
  its captured contract"; the negative checks are preserved. **Strike SMELL #2 and SMELL #3**
  in `SMELLS.md` (DRQ-075): #2 (god service) cured — order is an independent CQRS service; #3's
  last clause struck — the one remaining local `@Transactional` spans only the order service's
  own schema; **ACID→ACD realized for ALL contexts**. Update `SixContextsSmokeTest`
  (`/api/orders` → 404 on the monolith).
- **(c) Creates/touches:** `examples/00-monolith/**` (delete order + moved client + outbox +
  orphaned common; Flyway note for the retired tables; freeze the module), `SMELLS.md` (#2 +
  #3 struck with evidence), `SixContextsSmokeTest.java`,
  `examples/01-strangler-proxy/.../StranglerProxyRoute.java` + `application.properties` (shed
  strangler role → edge router), `tooling/newman/mea.postman_collection.json` + its docs
  (contract re-designation), `compose.yaml`/`.env` (remove the monolith from the running
  topology). Lane M + proxy. (A decommission `rm` may be blocked by the permission classifier —
  expect a human-action step if a delete is refused.)
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (validate the collapsed edge-router route);
  Newman re-run (now contract mode); `mvn -f examples/00-monolith clean verify` (frozen module
  still builds as the "before").
- **(e) Deps / parallel:** after S9. SEQUENTIAL. **This is the single, deliberately
  irreversible move for the whole system (H4).**
- **(f) Collision risk:** monolith (lane M) + proxy + suite — single writer each.
- **(g) Acceptance:** `GET :8888/api/orders` reaches the order service; the monolith is out of
  the running topology and 404s if booted on `/api/*`; the proxy is a flagless edge router; the
  contract suite is green (no live monolith in the loop); the order service's one remaining
  local `@Transactional` provably spans only its own schema; `SMELLS.md` #2 + #3 struck with
  evidence; `SixContextsSmokeTest` green. **[Opus gate]:** decommission reviewed — no orphaned
  in-process order path, no cross-context `@Transactional` left, the contract conversion is
  non-vacuous (negative checks still RED when tripped), and the frozen monolith still builds as
  the re-bootable referent.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(order): decommission the monolith (frozen in-repo); proxy becomes REST edge router; suite→contract; SMELL #2/#3 struck — ACID→ACD realized for ALL contexts (strangler completes)`

## S11 — Code-CI: order/gateway contract gate (no live monolith) red-then-green + cascade  *(SEQUENTIAL, after S10)*  **[Opus gate]**
- **(b) Goal / DoD:** Add an `order-gateway-contract-gate` job to
  `.github/workflows/code-ci.yml` (sibling to the existing context gates) that brings up
  Postgres + Kafka + the **order service** + the **GraphQL gateway** + the payment + shipping
  + inventory gRPC services — **and NO live monolith** (DRQ-071) — then runs the **full**
  contract suite through the proxy/edge-router (all four scenarios + Order + GraphQL contract
  folders) and **fails on non-zero Newman exit**. Prove the gate truly gates with
  **red-then-green**: disable the read-model projection ⇒ reads go stale ⇒ RED; disable a
  compensation ⇒ non-net-zero ⇒ RED; restore ⇒ green. **Cross-service cascade (ch.23 S10b /
  ch.24 S10 lesson, applied a final time):** the order service + gateway are now required by
  every full-suite gate; extend the sibling gates' topology to include them (and drop the
  live monolith), so no gate silently hangs/500s on the fully-extracted system.
- **(c) Creates/touches:** `.github/workflows/code-ci.yml` (add the order/gateway gate; extend
  siblings; remove the live monolith from the full-suite topology; do not fork a workflow).
  Isolated.
- **(d) Skills/MCP:** **lgtm-github** (GitHub Actions conventions). Reuses S2/S9 suite +
  S5/S6/S7 artifacts.
- **(e) Deps / parallel:** after S10. SEQUENTIAL.
- **(f) Collision risk:** low — single workflow file.
- **(g) Acceptance:** workflow green on push/PR with the fully-extracted system (no live
  monolith) exercised end-to-end incl. the GraphQL gateway; the deliberate breaks make the
  relevant scenarios RED (recorded), then green; sibling gates still green with order+gateway
  added and the monolith removed. **[Opus gate]:** Opus confirms the gate genuinely exercises
  the contract suite + CQRS + aggregation without a live referent (non-vacuous via the
  preserved negative checks), and the cross-service cascade is handled.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `ci(r08.x): order/gateway contract gate (no live monolith) — CQRS + GraphQL aggregation, red-then-green; extend sibling gates for the final topology`

## S12 — ch.26 diagram(s)  *(PARALLEL, after S6/S7)*
- **(b) Goal / DoD:** Paired **SVG + `.excalidraw`** figures: (1) the **CQRS write/read split**
  (command → Order aggregate + outbox; events → projection → `order_view`; reads ← read model);
  (2) the **GraphQL aggregation gateway** (one `order(id)` query stitched from order/payment/
  shipping/review over REST + inventory over gRPC); (3) the **strangler completes / final
  topology** (the six services + the gateway behind the now-flagless edge router, the monolith
  frozen out of the topology — the fig standing alone). House style, catalogued; follow the
  existing descriptive-name convention (e.g. `order-cqrs-split`, `graphql-aggregation-gateway`,
  `strangler-completes-final-topology`), tagged `ch.26` in the catalogue chapter column.
- **(c) Creates/touches:** `assets/diagrams/order-*.svg` + `graphql-*` + `strangler-completes-*`
  (+ `.excalidraw` + `.py` spec importing `_lib.py`); append rows to `assets/diagrams/README.md`
  (serialize this catalogue — one writer).
- **(d) Skills/MCP:** **lgtm-diagram-generator**.
- **(e) Deps / parallel:** after S6/S7 (CQRS + gateway shapes known); PARALLEL with S13.
- **(f) Collision risk:** low per-SVG; serialize `assets/diagrams/README.md`.
- **(g) Acceptance:** SVGs render; `.excalidraw` sources present; catalogue updated with ch.26 rows.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(§26): CQRS split, GraphQL aggregation gateway, and strangler-completes final-topology diagrams (SVG + excalidraw)`

## S13 — ch.26 ADLC trace capture  *(PARALLEL, after S10 evidence exists)*
- **(b) Goal / DoD:** Capture the **"ADLC in Action"** trace for ch.26 as pre-captured,
  narrated tool output (DRQ-025): the `migrate-spring-to-quarkus` run (S4), the CQRS write+read
  + outbox validation (S5/S6), the SmallRye GraphQL gateway validation (S7), the equivalence→
  contract cutover + all three negative checks + the decommission (S9/S10/S11), both human
  gates, and the DRQ-066…075 entries. The `_plans/` ledger is Exhibit A — and this is the trace
  where the ledger shows the *last* extraction and the monolith's decommission.
- **(c) Creates/touches:** captured transcripts under `_docs/_adlc-traces/` (e.g.
  `26-order-cqrs-gateway-trace.md`), reusing the established callout template.
- **(d) Skills/MCP:** **lgtm-tutorial** (callout format); evidence sourced from S4–S11 runs
  (captured, not re-run live).
- **(e) Deps / parallel:** needs S10 evidence; PARALLEL with S12.
- **(f) Collision risk:** low; coordinate callout reuse with S14.
- **(g) Acceptance:** a complete Frame→Map→Plan→Generate→Verify→Operate→Reconcile trace exists
  as checked-in narrated output; both gates + the contract conversion + the three negative
  checks + the decommission visible.
- **(h) Tier:** Sonnet. Opus gate folded into S14.
- **(i) Checkpoint commit:** `docs(§26): ADLC-in-Action trace for the order + GraphQL gateway extraction (CQRS, monolith decommission)`

## S14 — ch.26 authored to the full bar  *(SEQUENTIAL, after S10 + S12 + S13)*  **[Opus gate — 2k + footer]**
- **(b) Goal / DoD:** Chapter 26 ("Communication Styles & Extraction 6 — Order + GraphQL
  Gateway", per build-plan §B.2, Part 8 "Communication & Contracts") authored to the full bar:
  **≥2000 words excl. code/diagrams**, progressive, referencing the runnable
  `examples/07-order-service/` (CQRS write/read split + lifted reactions) +
  `examples/08-graphql-gateway/` (SmallRye GraphQL aggregation) + the now-edge-router proxy;
  the S12 diagrams embedded; a real **"ADLC in Action" callout** (S13); a **verification-status
  footer** naming the tests/demos run (contract suite incl. all four scenarios + Order +
  GraphQL contract folders, the checkout/reaction/idempotent/compensation round-trip tests, the
  CQRS read-after-write + projection-disabled + rebuild tests, the three negative checks, the
  gateway stitching test, Citrus route test, Dev Services, native). Must teach: **REST vs gRPC
  vs GraphQL and when each fits** (build-plan §B.2); **CQRS** write/read split + read-after-write
  (DRQ-067); the **GraphQL aggregation gateway** as the CQRS read-aggregation surface (DRQ-069);
  **extracting the god aggregate last** and why (SMELL #2, DRQ-066); **the strangler completing**
  + the monolith's decommission (DRQ-070); **how the equivalence suite converted to a contract
  suite without going vacuous** (DRQ-071) and **reversibility of the last extraction** (DRQ-072);
  and **ACID→ACD finally realized for ALL contexts** (SMELL #3 struck, DRQ-075). Fill the
  existing `_docs/26-*.md` stub if present (do NOT create a duplicate `order: 26`); otherwise add
  the `_docs` file. Only *adds/edits* that `_docs` file (no `_config.yml`/`_parts/` edits).
- **(c) Creates/touches:** `_docs/26-order-graphql-gateway.md` (front matter: `title`,
  `order: 26`, `part: "Communication & Contracts"` (matches `_parts/08-*.md`), `description`,
  `duration`).
- **(d) Skills/MCP:** **lgtm-tutorial** (authoring + static validation) + **lgtm-jekyll**
  (build/word-count).
- **(e) Deps / parallel:** after S10 (behavior final), S12 (diagrams), S13 (callout). SEQUENTIAL.
- **(f) Collision risk:** low (single `_docs` file).
- **(g) Acceptance:** lgtm-jekyll/lgtm-tutorial validation green: **word count ≥2000**,
  example-dirs present, verification footer present, links/diagrams resolve, no duplicate
  `order: 26`. **[Opus gate]:** Opus confirms the 2k-with-running-code bar, that the CQRS +
  GraphQL + last-extraction + strangler-completes + equivalence→contract + ACID→ACD teaching is
  honest, and the callout is authentic.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `docs(§26): author Communication Styles & Extraction 6 — Order + GraphQL Gateway (CQRS, strangler completes) to the full bar`

## S15 — Reconcile, status, exit validation  *(SEQUENTIAL, last)*  **[Opus gate]**
- **(b) Goal / DoD:** Append r08/ch.26 outcomes to the ledger: `reconciliation.md` updated
  (artifact→source drift incl. the datamesh `order-service` + `graphql-gateway` adaptations —
  note the CQRS re-shape + the aggregation-gateway widening as named divergences; zero
  unexplained drift, R4); `build-plan.md` §E row 6 marked **DONE** (extractions **6 of 6** — the
  strangler complete, the monolith decommissioned) + the §J/status row for ch.26; `decisions.md`
  version matrix updated (order-service `quarkus-messaging-kafka`/`quarkus-grpc`/Panache; gateway
  `quarkus-smallrye-graphql`; reused Kafka broker tag). The ch.26 EXIT CHECKLIST (below)
  verified; clean resume boundary for **Part 8+ (ch.27 Quarkus/MicroProfile chassis, ch.28
  contracts & registry — Avro + Apicurio, then Parts 9–10)** recorded.
- **(c) Creates/touches:** `_plans/reconciliation.md`, `_plans/build-plan.md` (status),
  `_plans/decisions.md` (matrix). Serialize all three.
- **(d) Skills/MCP:** none new; lgtm-jekyll full-site validation; re-run the contract suite once.
- **(e) Deps / parallel:** after all. SEQUENTIAL.
- **(f) Collision risk:** the three `_plans/*` are single-writer — only S1 and S15 write them in r08.
- **(g) Acceptance:** exit checklist all-green; matrix updated; status current; **all six
  extractions DONE**. **[Opus gate]:** Opus signs off the whole extraction + the strangler's
  completion.
- **(i) Checkpoint commit:** `docs(r08.x): reconcile ch.26, update version matrix, mark order + GraphQL gateway extraction DONE (6 of 6 — strangler complete, monolith decommissioned)`

---

## ch.26 EXIT CHECKLIST (the strangler completes + ACID→ACD realized for ALL contexts)
- [x] **Order extraction decided & applied (DRQ-066/073):** the god `OrderService`, the four
      `OrderSagaListener` reactions, `Order`/`OrderItem`/`Customer`, the gRPC inventory client,
      and the outbox are extracted to `examples/07-order-service` (own schema, FKs decomposed,
      two-phase A→B, measured); the order service is the external `order.placed` producer.
- [x] **CQRS shape genuinely taught (DRQ-067):** a separate denormalized `order_view` read
      model projected from the lifecycle events, reads served exclusively from it, rebuildable
      from the aggregate, read-after-write eventual consistency demonstrated; the
      projection-disabled negative check goes RED.
- [x] **GraphQL aggregation gateway delivered (DRQ-069):** `examples/08-graphql-gateway` owns no
      data and stitches order + payments + shipments + reviews + stock over REST + gRPC; depth/
      complexity bounded; the GraphQL Gateway Contract folder passes.
- [x] **Lifted saga reactions preserve their guarantees (DRQ-074):** at-most-once compensating
      `Release`, status-guard idempotency, and the payment-decline vs shipment-failure mutual
      exclusion all hold in the extracted service (tests + redelivery proof).
- [x] **Equivalence green across the seam (S9) then converted to a contract suite (DRQ-071):**
      all four scenarios + Order + GraphQL contract folders green across the seam; the frozen S2
      golden baseline re-designated as the contract; CI drops the live monolith; the negative
      checks are preserved and prove non-vacuity.
- [x] **Reversibility shown one last time (DRQ-072)** at S9 (flip back → monolith serves checkout,
      green) before the final irreversible decommission; the frozen-not-deleted monolith is the
      break-glass referent.
- [x] **Strangler completes + monolith decommissioned (DRQ-070):** `/api/orders` served by the
      order service; the monolith is out of the running topology, frozen in-repo (DRQ-024); the
      proxy is a flagless REST edge router (`strangler.*` flags + monolith default backend
      retired).
- [x] **SMELL #2 & #3 fully cured; ACID→ACD realized for ALL contexts (DRQ-075):** the god
      service is an independent CQRS service; the one remaining local `@Transactional` spans only
      the order service's own schema; SMELLS.md #2 + #3 struck through with evidence;
      `SixContextsSmokeTest` asserts `/api/orders` → 404 on the monolith.
- [x] **Code-CI green:** the order/gateway contract gate exercises the fully-extracted system (no
      live monolith) incl. CQRS + GraphQL end-to-end (red-then-green via disabling the projection
      / a compensation); sibling gates still green with the final topology (cascade handled).
- [x] **ch.26 authored ≥2000 words**, runnable examples (order service + gateway), embedded
      diagrams, real "ADLC in Action" callout, verification-status footer; REST/gRPC/GraphQL +
      CQRS + strangler-completes taught.
- [x] **Ledger reconciled:** `decisions.md` DRQ-066…075 accepted + version matrix updated; §E row
      6 DONE (**6 of 6**); this plan's steps and exit checklist marked DONE.

## Biggest risks
1. **(Highest) The final decommission is irreversible and the extracted order service is subtly
   wrong (H4/DRQ-070/072).** After S10 there is no live monolith to revert to; a latent defect in
   the lifted god aggregate, the CQRS projection, or a compensation path ships with no fallback.
   Mitigation: prove reversibility exhaustively at S9 before S10; preserve all negative checks in
   CI; freeze-not-delete the monolith as a re-bootable break-glass referent; the Opus equivalence
   gate at S9 is a hard human sign-off before the irreversible move.
2. **The equivalence→contract conversion goes vacuous (H3/DRQ-071).** Once the monolith referent
   is gone, a carelessly-converted suite asserts the system agrees with itself. Mitigation: freeze
   the S2 golden baseline as the contract; keep the strict terminal assertions unchanged; keep the
   three negative checks (consumer-down, compensation-disabled, projection-disabled) running in CI
   — they are what prove the suite still bites without a live referent; S2 is an `[Opus gate]`.
3. **The CQRS read model silently falls back to the aggregate / goes stale (H2/DRQ-067/074).** A
   read path that quietly reads the aggregate would make the whole CQRS teaching vacuous and hide a
   broken projection. Mitigation: reads serve EXCLUSIVELY from `order_view` (no fallback); the
   projection is idempotent + rebuildable; the projection-disabled negative check must go RED
   (S6/S9/S11).
4. **Lifting the god aggregate regresses the saga guarantees (H1/DRQ-074).** The reactions own the
   at-most-once compensating `Release` + the payment-decline/shipment-failure mutual exclusion; a
   lift that loses a status guard double-compensates or confirms without a dispatch. Mitigation:
   lift the reactions unmodified in shape; re-assert the guarantees in unit tests before any
   end-to-end run; S2 baselines all four scenarios green vs the monolith first.
5. **The GraphQL gateway becomes an amplification / data-ownership leak.** An unbounded aggregation
   query, or a gateway that caches/owns data, breaks scope discipline and security-by-design.
   Mitigation: depth/complexity bounds; fixed operator-configured downstream targets (no
   request-derived routing); the gateway owns no data (Opus-gate check at S7).
6. **Cross-service CI cascade, final form.** The order service + gateway are now required by every
   full-suite gate, and the live monolith must be removed from all of them at once. Mitigation: S11
   extends every sibling gate's topology (add order + gateway, drop the monolith) and records the
   final cascade.

## Resume boundary for Part 8+ (ch.27 chassis, ch.28 contracts & registry, then Parts 9–10)
ch.27 resumes from the `build-plan.md` §E status table with **all six extractions DONE** (6 of 6),
the monolith decommissioned and frozen, the strangler proxy now a flagless REST edge router, and a
new GraphQL aggregation gateway live. What the rest of Part 8 and Parts 9–10 inherit:
- **ch.27 (Quarkus / MicroProfile chassis):** six Quarkus services now exist to retrofit the chassis
  onto (Config, Fault Tolerance, Health, Metrics, OpenAPI, REST Client, JWT) — "what you migrated
  *to*," with the order service + gateway as the freshest, most complete examples.
- **ch.28 (Contracts & the Service Registry — Avro + Apicurio):** the JSON-on-the-wire decision
  (DRQ-038) and the "no shared code, field-for-field mirror" event vocabulary are exactly what ch.28
  replaces with Apicurio-registered Avro/Protobuf; the order service is now the `order.placed`
  producer and the hub consumer, so it is the natural first schema-registry citizen. The in-memory
  saga durability limitation (DRQ-057, LRA) also remains cross-referenced here (and to ch.25
  resilience, not yet started).
- **The CQRS read model + the GraphQL aggregation surface** are the read-side foundation Parts 9–10
  operate (observability across the aggregation; deploy/mesh of the gateway; CI/CD of the
  fully-extracted system) — and the GraphQL gateway is a new edge to secure/trace in ch.30.
- **The frozen monolith** stays in-repo (DRQ-024) as the permanent "before" the whole book
  references and the re-bootable golden-baseline referent; no further extraction remains — the
  strangler is complete.
