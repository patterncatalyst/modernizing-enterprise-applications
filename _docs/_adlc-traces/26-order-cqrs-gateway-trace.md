---
title: "ADLC in Action — the order + GraphQL gateway CQRS/decommission trace (ch.26 source material)"
description: "Captured, narrated evidence (DRQ-025) for the ch.26 Order + GraphQL Gateway extraction — the sixth and LAST strangler extraction — walking the Frame -> Map -> Plan -> Generate -> Verify -> Operate -> Reconcile loop against the real order-plan.md steps, commits, CUTOVER.md/MIGRATION.md numbers, and the monolith's full decommission. This is a companion artifact, not a chapter; it is the source the ch.26 \"ADLC in Action\" callout is drawn from, mirroring the ch.23 payment and ch.24 shipping traces' format and, further back, ch.07's worked-loop format and the ch.17/ch.19 callouts."
published: false
---

> This is not a chapter. It is the pre-captured trace `_plans/iterations/order-plan.md`
> S13 asks for: the evidence, phase by phase, behind ch.26's eventual "ADLC in
> Action" callout (S14). Every quoted block below is real, dated output already
> sitting in this repository's own files and commit history —
> `examples/07-order-service/CUTOVER.md` and `MIGRATION.md`,
> `examples/08-graphql-gateway/README.md`, `examples/01-strangler-proxy/CUTOVER.md`,
> `examples/00-monolith/SMELLS.md` and `README.md`, `_plans/decisions.md`, and the
> `git log`/tags of the r08 order-extraction work on `r02-walking-skeleton` — never
> re-run live to produce this document (DRQ-025). It mirrors the per-phase structure
> `_docs/_adlc-traces/23-payment-choreographed-saga-trace.md` and
> `_docs/_adlc-traces/24-shipping-orchestrated-saga-trace.md` established (itself
> mirroring `_docs/07-adlc-safety-net.md`'s worked loop and the ch.17/ch.19 callout
> template). S13 runs in parallel with S12 (diagrams) once S10's decommission
> evidence exists; S11 (the Code-CI gate) and S14/S15 had not yet landed at the time
> this trace was captured — flagged as open at the end, not assumed closed. **This is
> the final trace of the whole book-build: the sixth and last of six extractions,
> the point at which the strangler fig completes and the monolith it strangled is
> decommissioned for good.**

## Frame: the last and hardest extraction, five decisions confirmed before a line of code existed

Order's Frame phase is `_plans/iterations/order-plan.md` itself (S1) — and
unlike every prior extraction's single defining decision, this plan opens
with **five** architectural calls, all brought to the user and confirmed the
same day the plan was written:

```
Captured — _plans/iterations/order-plan.md, "Decisions CONFIRMED (user, 2026-10-05)"

1. CQRS shape -- CONFIRMED: same-DB projected read model (denormalized
   order_view in the order service's own Postgres, projected from lifecycle
   events; reads served only from the view). NOT a separate read datastore.
2. GraphQL gateway -- CONFIRMED: additive cross-context aggregation gateway
   with its OWN front door, alongside the edge router (SmallRye GraphQL,
   data-less, stitches order->payments/shipments/reviews/stock). NOT Apollo
   federation, NOT order-only.
3. Monolith end-state -- CONFIRMED: fully decommissioned but frozen-not-deleted
   in-repo (DRQ-024 golden baseline); the strangler proxy sheds its flags and
   becomes a permanent REST edge router.
4. Equivalence-suite fate -- CONFIRMED: convert to a contract/acceptance suite
   against the frozen golden baseline; CI drops the live monolith from the
   order/gateway gate (frozen monolith re-bootable as break-glass to
   re-derive the baseline).
5. Reversibility -- CONFIRMED: reversible at the seam until S9 (proven
   exhaustively at S8), then the final decommission is the one deliberate
   system-wide irreversible move, de-risked by the S8 proof + the frozen
   break-glass monolith + the independently-revertible additive
   gateway/read-model.
```

Each decision carries its own rejected alternative in the plan's text, on the
record before Generate began: a physically separate read datastore for CQRS
(rejected as speculative infrastructure the same-DB two-table split avoids);
an Apollo-style federation router for the gateway (rejected as a new infra
component this teaching system does not need); deleting the monolith module
outright (rejected because it would discard the book's living "before" and
the baseline's reproducibility); and leaving the equivalence suite anchored
to a monolith that is about to stop existing (rejected as the one answer that
cannot survive S10). Ten decisions in total were seeded this way — DRQ-066
through DRQ-075 — appended to `_plans/decisions.md` at S1 and, by the time
this trace was captured, already carried through to **accepted**, each row
naming the step that realized it. DRQ-066 states the extraction's full scope
in one sentence:

```
Captured — _plans/decisions.md, DRQ-066 (accepted)

The order context (god OrderService + the four OrderSagaListener reactions +
Order/OrderItem/Customer + the outbox) is extracted to a new Quarkus
examples/07-order-service (own front door port :8087); RemoteInventoryClient
(gRPC) moves with it; a new examples/08-graphql-gateway (own front door port
:8090) is added; the monolith is then fully decommissioned. Customer FK
decomposed to a customerId value + email snapshot; the order service owns the
customers table (DRQ-068). Rejected: a surviving monolith shell; a separate
customer-service extraction.
```

## Map: why order is the hardest and differently-shaped rung of all six

Map's job is reconnaissance before Generate, and the plan's own "why this
extraction is the hardest" section names six concrete reasons, each of which
this trace's later phases return to:

1. **It is the god aggregate and the coordinator of the whole flow (SMELL
   #2).** `OrderSagaListener` hosts the reactions for all four Kafka topics
   and owns the reserved-line snapshot every prior saga's compensating
   `Release` depends on. Extracting it means lifting the thing the other five
   extractions were careful to leave in place — the hub.
2. **There is no monolith left to be equivalent *to*.** Five extractions
   asserted "the extracted service behaves like the monolith." Once order
   leaves and the monolith is decommissioned, that referent is gone — the
   subtlest point in the whole book-build (H3).
3. **There is no fallback to revert to.** Every prior cutover could flip back
   to a live monolith; S10 removes that for the entire system (H4).
4. **SMELL #2 and the last clause of SMELL #3 must finally be cured**,
   completing ACID→ACD for ALL contexts — `SMELLS.md` already said this was
   "NOT struck through... cured for payment and shipping, not yet for order,"
   naming order as its own resume boundary.
5. **Two net-new teaching surfaces land at once** — a CQRS write/read split
   and a SmallRye GraphQL aggregation gateway — neither ever built before in
   this codebase, layered on top of the hardest lift.
6. **The strangler itself completes.** `/api/orders` is the one path still
   routed to the monolith; cutting it over is the last flag flip, after which
   the proxy has no host tree left to strangle.

The plan names these as HARD PARTS H1 through H5 in a dedicated section and
states a mitigation for each before Generate begins — H1 (lifting the god
aggregate) at S5, H2 (the CQRS non-vacuity crux) at S6, H3 (the
equivalence→contract conversion) at S2 and S10, H4 (the irreversible
decommission) at S9/S10, H5 (curing SMELL #2/#3 with evidence) at S10. The
plan's own "Biggest risks" section ranks H4 **highest**: "the final
decommission is irreversible and the extracted order service is subtly
wrong" — with no live monolith left to revert to if that turns out to be
true.

## Plan: fifteen steps, Opus gates at nearly every rung, the hardest sequence yet

With Frame and Map settled, Plan is where the step sequence became a
commitment a human signed off on before Generate touched a file:

```
Captured — _plans/iterations/order-plan.md, the parallelism overview (condensed)

S1 (frame + decisions, SEQUENTIAL, must be first)
S2 equivalence suite: FINAL baseline vs the monolith; Order Context Contract;
   stage the contract-suite conversion + GraphQL contract folder    [Opus gate] <- THE crux (H3)
 |- S3 event contract: order svc becomes external order.placed producer
 \- S4 order svc scaffold + Phase A read-surface lift (own schema)  [Opus gate]
S5 order svc Phase B idiomatic + CQRS WRITE model + lifted saga
   reactions + own outbox (SEQ)                                     [Opus gate] <- HARD PART H1
S6 CQRS READ model + projection (order_view, rebuildable) (SEQ)     [Opus gate] <- HARD PART H2
S7 GraphQL gateway: examples/08-graphql-gateway (SEQ after S6)      [Opus gate]
S8 strangler proxy: order flag + /api/orders route (transparent)
S9 CUTOVER: flip the order flag; suite green across the seam;
   reversibility; three negative checks            [Opus gate - equivalence gate] <- HARD PARTS H3/H4
S10 DECOMMISSION the monolith (full) + proxy->edge router + suite->contract
   + SMELLS #2/#3 struck + ACID->ACD for ALL (SEQ after S9)         [Opus gate] <- HARD PARTS H4/H5
S11 Code-CI: order/gateway contract gate (no live monolith)
    red-then-green + cascade                                        [Opus gate]
 |- S12 ch.26 diagram(s)      (PARALLEL after S6/S7)
 \- S13 ch.26 ADLC trace      (PARALLEL after S10 evidence exists)  <- this document
S14 ch.26 authored to the bar (SEQ)                        [Opus gate - 2k + footer]
S15 reconcile + status + exit (SEQ, last)                            [Opus gate]
```

Opus validation gates sit on S2, S4, S5, S6, S7, S9, S10, S11 — every rung
with real behavior at stake — and S9 is explicitly labeled an **[Opus gate —
equivalence gate]**, the human sign-off point before the one irreversible
step. The plan's "Biggest risks" section states the mitigation chain
directly: prove reversibility exhaustively at S9 before the irreversible S10;
preserve every negative check in CI; freeze-not-delete the monolith as a
re-bootable break-glass referent; make the Opus equivalence gate a hard human
sign-off before the irreversible move. A second named risk — the
equivalence→contract conversion going vacuous once the referent disappears —
is the subtler failure mode S2's own Opus gate had to rule out directly, not
just at the final cutover.

## Generate: the two-phase order service, the CQRS read model, the gateway, and a flag that finally completes the strangler

Generate is where the plan became code, run across S3 through S8.

**S3** authored the order service's own event vocabulary field-for-field —
`OrderStatus`, `OrderDto`/`OrderCreate`, the `order.placed` payload, and the
four consumed event records — so no module depends on the monolith's
`common/*` once it is gone (commit `848ea24`, the "no shared code between
reactors" precedent, DRQ-038, applied a final time).

**S4 (Phase A)** scaffolded `examples/07-order-service` on its own schema
from day one — `orders`/`order_items`/`customers`, plus reserved,
still-empty tables for the outbox and the future `order_view` — lifting the
read+command surface via Quarkiverse Spring-compat and decomposing BOTH
sides of the `Order` ⇄ `Customer` relationship, a decomposition every prior
extraction only had to cut from one side (commit `fe429b3`, 11 tests green,
Opus-gated GO on own-schema isolation and the FK decomposition).

**S5 (Phase B)** is this extraction's hardest Generate pass — lifting the god
aggregate itself:

```
Captured — git log, commit ea2615b (S5, Phase B — HARD PART H1)

feat(order): Phase B -- idiomatic Quarkus + CQRS write model (placeOrder
command) + lifted SmallRye saga reactions + own order.placed outbox + gRPC
inventory client (idempotent, measured)
```

`MIGRATION.md` documents the write model as one `@Transactional` method —
validate the customer, reserve every line over gRPC, persist the order
`PENDING`, write `order.placed` to the order service's **own** outbox, in one
atomic commit, no dual-write — and the four lifted reactions preserving
every guarantee the monolith's `OrderSagaListener` carried: status-guard
idempotency, at-most-once compensating `Release`, and the mutual exclusion
between `onPaymentDeclined` (fires only from `PENDING`) and `onShipmentFailed`
(fires only from `AWAITING_SHIPMENT`) — disjoint by construction, so neither
reaction can ever double-`Release` the same reservation. The measured
before/after:

```
Captured — examples/07-order-service/MIGRATION.md, Phase A/B metrics

| Build                                                               | Startup | RSS    | Features |
|----------------------------------------------------------------------|------:|-------:|---------:|
| Phase A -- JVM, Spring-compat                                        | 1.721s | ~308MB |       17 |
| Phase B -- JVM, idiomatic + 4 Kafka consumers/1 producer + outbox     | 2.050s | ~407MB |       18 |
```

Startup is ~19% slower and RSS ~32% higher — proportionally the largest RSS
jump of any extraction's Phase A→B, because this step runs FOUR independent
live Kafka consumers (one per saga reaction) plus one producer plus a
scheduled outbox poller, where payment's and shipping's own Phase A→B each
added only a single trigger consumer.

```
Captured — examples/07-order-service/MIGRATION.md, "Reading the numbers"

... the honest cost of running four independent saga reactions as genuinely
live consumers, not a regression to chase.
```

**S6** is the CQRS teaching core and this extraction's second hard part — new
machinery, not a refactor of something already proven:

```
Captured — git log, commit c522b8f (S6 — HARD PART H2)

feat(order): CQRS read model -- denormalized order_view projected from
lifecycle events (rebuildable, idempotent); reads served exclusively from
the read model
```

`MIGRATION.md` records the projection running inside the SAME transaction as
each write (no dual-write, no second datastore), idempotent by `orderId`
(an upsert, proven directly against a real database by a
calledTwice-for-the-same-order test), and reads repointed so that
`OrderService.java` contains **exactly one** reference to the write-model
repository — inside `placeOrder` itself — with `OrderResource.java` carrying
zero references to it at all, closing off any silent fallback path that
would hide a broken projection.

**S7** built the SmallRye GraphQL aggregation gateway as a new, additive
module from day one:

```
Captured — git log, commit 82bb319 (S7)

feat(gateway): SmallRye GraphQL aggregation gateway (examples/08-graphql-gateway :8090)
```

`README.md` documents the gateway's one resolver class (`GatewayApi`)
stitching `order(id)` across five services — order and payments and
shipments over REST, reviews over REST by sku, stock over gRPC — each field
resolved by a `@Source` resolver calling the service that owns it, never a
second datastore. Security-by-design landed in the same step, not
after-the-fact: query depth and complexity are bounded in
`application.properties`, and every downstream target is fixed operator
config, never derived from the incoming request — a dedicated
`GatewayDepthComplexityLimitTest` proves the bound is actually wired and
active, under a tiny test-only budget.

**S8** wired the strangler proxy's sixth and final flag — and, like every
extraction since Inventory, found the anticipated translator unneeded:

```
Captured — git log, commit 7a070ea (S8)

feat(strangler): order flag + /api/orders route (transparent reverse proxy;
ACL honesty -- no translator, DTO identical)
```

Unlike every prior flag, `strangler.order.enabled` routes BOTH the checkout
command and every read at once, since the whole order context moves
together — the sixth and last context to earn the same no-translator
treatment Review, Inventory, Payment, and Shipping each established in turn.

## Verify: three Opus gates, four environmental findings, and the crux proven with real numbers

Verify is where this extraction's two hardest claims — that the lifted god
aggregate's guarantees survived the move, and that a contract suite with no
monolith left to anchor it is not vacuous — get checked against running code.

**S2's Opus gate** (commit `a8c812e`) captured the LAST monolith-anchored
baseline and added the new Order Context Contract folder, staging (but not
yet enabling) a GraphQL Gateway Contract folder for S7 to switch on — the
decision text is explicit that this is "the LAST monolith-anchored
baseline," the referent S10 will freeze as the golden contract.

**S5's Opus gate** confirmed the hardest correctness property a lift of the
god aggregate has to preserve — not just that the reactions run, but that
they can never double-compensate:

```
Captured — examples/07-order-service/MIGRATION.md, the saga-correctness excerpt (S5)

Mutual exclusion (disjoint by construction): onPaymentDeclined fires only
from PENDING; onShipmentFailed fires only from AWAITING_SHIPMENT. An order
reaches AWAITING_SHIPMENT ONLY via a successful onPaymentCaptured, so a
decline short-circuits at PENDING before AWAITING_SHIPMENT is ever reachable
... Neither reaction can ever double-Release the same reservation, and
neither can collide with OrderService#placeOrder's pre-handoff catch.
```

Proven with at least two SKUs per compensation test — not a single-item
special case — and 32 tests green at this step (`OrderResourceTest` 6,
`OrderServiceTest` 6, `OrderSagaListenerTest` 15, `CheckoutOutboxTest` 2,
`OrderSagaListenerIntegrationTest` 3).

**S6's Opus gate** is H2's non-vacuity proof, run as a dedicated negative
test rather than asserted in prose: `OrderViewProjectionDisabledTest`
suppresses the projection, sends `payment.captured`, confirms directly off
the write-model repository that the aggregate reached `AWAITING_SHIPMENT`
(ruling out "the event was never processed" as a false-negative
explanation), then asserts the READ stays stale at `PENDING` over a held
window:

```
Captured — examples/07-order-service/MIGRATION.md, the non-vacuity negative check (S6)

4. Asserts the READ stays STALE at PENDING over a held window -- this is the
   assertion that would go RED if a read ever fell back to the aggregate,
   proving reads genuinely come from order_view.
```

40 tests green at this step (the prior 32 plus 8 new).

**S9's equivalence gate** is where H3 and H4 both get proven with real
numbers against the full nine-process topology — order-service, the
GraphQL gateway, the five prior extracted services, the monolith, and the
proxy, all running at once for the first time. Before any assertion could be
trusted, four environmental findings had to be surfaced and fixed — none
were order-service defects, each isolated and disproved as a code issue in
`CUTOVER.md`:

```
Captured — examples/07-order-service/CUTOVER.md, the four findings (condensed)

Finding 1 -- order-service's OWN customers table starts EMPTY (DRQ-073, no
  CDC backfill by design); fixed with a single idempotent seed INSERT.
Finding 2 -- a brand-new Kafka consumer group must drain this repo's ENTIRE
  accumulated history across payment.captured/declined and shipment.
  dispatched/failed since ch.23/ch.24; confirmed via kafka-consumer-groups.sh
  (end offsets in the hundreds); resolved once LAG=0.
Finding 3 (the big one) -- order-service's fresh order-id sequence (starting
  at 1) collides with the shared monolith-era order-id history (high-water
  mark 322, confirmed live across payment.payments/shipping.shipments/the
  monolith's own orders table); order 1 stranded PENDING on a genuine
  collision with a 2026-01-07 monolith-era payment row -- isolated and
  disproved as a code defect (orders 2-5 placed moments later did NOT
  collide); fixed by a burn-in past the live high-water mark, queried not
  hardcoded.
Finding 4 -- shipping-service's own order.service.base-url still defaulted
  to the monolith (:8080); once the order flag cut over, shipping's enrich
  step 404'd against the monolith, fell back to its documented stub address,
  and Scenario 4 silently became a FALSE GREEN that CONFIRMED the order
  instead of failing it -- caught, diagnosed, and fixed with
  ORDER_SERVICE_BASE_URL=http://localhost:8087 before any cutover evidence
  could be trusted.
```

Finding 4 in particular is exactly the class of false-equivalence trap H3/H4
warn about — a green suite for the wrong reason, surfaced and closed before
the irreversible step rather than discovered after it. With the topology
confirmed healthy, the cutover evidence followed:

```
Captured — examples/07-order-service/CUTOVER.md, the cutover run

Final, clean cutover run: 167 assertions, 1 failed (the one documented
GraphQL content-type gap), 16.6s.

Scenario 1 (happy path) -- looped 7 times (~4.5s) to CONFIRMED (order 334) --
  the full chain order.placed (now produced by the order service's OWN
  outbox) -> payment.captured -> shipping saga -> shipment.dispatched -> the
  order service's OWN onShipmentDispatched reaction -> CONFIRMED.

Scenario 3 (payment-declined) -- net-zero confirmed, explicit numbers (order
  340): 490 -> 489 -> 490.

Scenario 4 (shipping-failure) -- net-zero confirmed, explicit numbers (order
  339): 490 -> 489 -> 490.
```

The order-flag-reaches-:8087 proof is the strongest version of the
"proxy-reaches-the-service" check every prior `CUTOVER.md` has made, because
here the monolith cannot even 200 with stale data:

```
Captured — examples/07-order-service/CUTOVER.md, the three-way flag-reach proof (order 339)

GET http://localhost:8888/api/orders/339   (via the proxy)       -> {"id":339,"status":"SHIPPING_FAILED",...}
GET http://localhost:8087/api/orders/339   (direct to the order service) -> BYTE-IDENTICAL
GET http://localhost:8080/api/orders/339   (direct to the monolith)      -> HTTP 404 -- the
  order was never minted on the monolith's side of the fork at all.
```

The GraphQL gateway's own aggregation evidence (order 339, abbreviated)
stitched four other services in one round trip — order, payment, shipment,
and the line item's live stock level over gRPC, plus 95 reviews for the
SKU, omitted above for length:

```
Captured — examples/07-order-service/CUTOVER.md, GraphQL aggregation evidence

One GraphQL query stitched the order (order service), its payment (payment
service, over REST), its cancelled shipment (shipping service, over REST),
and its line item's live stock level (inventory service, over gRPC) --
the read-aggregation surface DRQ-069 calls for, genuinely resolving across
all four other extracted services in a single round trip.
```

**The three negative checks (DRQ-071/072, H2/H3)** are the sharper proof,
each a local byte-for-byte-reverted edit to `OrderSagaListener.java`,
rebuilt, shown RED, reverted via a plain file copy (never `git`), confirmed
clean via a read-only `git status`, rebuilt, shown GREEN:

```
Captured — examples/07-order-service/CUTOVER.md, the three negative checks (condensed)

3a. Order saga consumer reaction disabled -> Scenario 1 RED
    order 403 status after 20 attempt(s): expected 'AWAITING_SHIPMENT' to
    deeply equal 'CONFIRMED'.  Reverted -> GREEN, 19/19.

3b. Read-model projection disabled on onShipmentDispatched -> stale reads
    (the H2 CQRS non-vacuity check). Direct DB proof: write-model aggregate
    order_service.orders shows 406=CONFIRMED; read-model order_service.
    order_view shows order_id 406=AWAITING_SHIPMENT. GET /api/orders/406
    returned AWAITING_SHIPMENT for 20 straight polls despite the aggregate
    already being CONFIRMED. Reverted -> GREEN, 21/21.

3c. Compensating Release disabled in onShipmentFailed -> non-net-zero.
    Explicit stock numbers (order 409): 494 -> 493 -> 493 (never restored).
    Reverted -> GREEN, 14/14, re-confirmed net-zero on a fresh order:
    492 -> 491 -> 492.
```

**Reversibility, proven one last time (DRQ-072):** with both overrides
removed, a direct checkout confirmed the monolith served checkout again
(`202`/`PENDING`, bounded-wait to `CONFIRMED`, order 341), and the order
service confirmed zero knowledge of it (`GET :8087/api/orders/341` → `404`).
A full suite re-run produced **164 assertions, 8 failed — all 8 inside the
single GraphQL Gateway Contract item**, a documented, benign consequence of
the gateway's own additive design (it has no monolith client at all, by
design, and correctly cannot see an order placed on the monolith's side
while reversed) — proven not a defect by a direct query for a real
order-service order from the earlier cutover run, which resolved correctly
throughout.

```
Captured — examples/07-order-service/CUTOVER.md, the reversibility conclusion

... this proves the reversibility window order-plan S8 built is genuinely
open -- flipping the flag back is a config change and a restart, nothing
more -- right up until S10.
```

**S10's decommission gate** is the last Opus sign-off this trace covers, and
the single irreversible move for the whole system:

```
Captured — git log, commit ce49202 (S10, Opus-gated, THE final irreversible move)

feat(order): decommission the monolith (frozen in-repo); proxy becomes REST
edge router; suite->contract; SMELL #2/#3 struck -- ACID->ACD realized for
ALL contexts (strangler completes)

Delete the monolith's order context + moved gRPC client + outbox relay +
orphaned common vocabulary (31 files); keep examples/00-monolith as a
frozen, still-building shell (3 config-only classes + frozen V1-V4
migrations; SixContextsSmokeTest -> boot+404, 6/6) -- the complete runnable
'before' is preserved on branch reference/monolith-before (tag v0-monolith)
+ stage/01..05 tags. Proxy sheds its strangler role -> flagless REST edge
router: one content-based choice() per /api/<ctx> to the six services, 404
otherwise (no dead-monolith fallback); 6 strangler.*.enabled flags +
strangler.monolith.base-url removed; EdgeRouterRoutingTest 8/8;
camel_validate_route clean. Equivalence suite -> contract/acceptance suite:
info.description re-designated only, NOT ONE assertion/negative-check
changed (150 pm.test / 40 items / 2065 lines unchanged). SMELL #2 (god
OrderService) + #3 (cross-context ACID) struck -- ACID->ACD realized for all
six contexts; the strangler fig stands alone.

Opus-gated GO (irreversible), all 9 criteria MET, 3 builds green (monolith
6/6, proxy 8/8, shipping 16/16).
```

The conversion's non-vacuity claim rests on one line in the commit message:
"NOT ONE assertion/negative-check changed" — the suite's `info.description` was
re-designated from equivalence-vs-monolith to contract-against-a-frozen-
baseline, but every strict terminal assertion and every negative check
stayed byte-for-byte what it was at S2, which is exactly what H3's
mitigation required to avoid a quietly-vacuous conversion.

## Operate: the final flag flipped, the strangler fig stands alone

Operate is where the order flag stopped being a side-by-side comparison and
`/api/orders` traffic started answering from the order service permanently.
`examples/01-strangler-proxy/CUTOVER.md` proves the collapsed routing
directly against the compiled route class:

```
Captured — examples/01-strangler-proxy/CUTOVER.md, the edge-router proof

EdgeRouterRoutingTest -- one unconditional routing assertion per context plus
one for the 404 fallback -- run against the ACTUAL compiled route against
stubbed-but-network-real extracted services. Result: 8/8 tests green --
real HTTP requests hit the real route class, resolved to their correct stub
backend, and an unmatched path got a 404 with the real choice()/.to()
dispatch logic -- not a mock of the route.
```

With the monolith decommissioned, `GET http://localhost:8080/api/reviews`
and every other former `/api/*` path 404 directly — "correctly routed to the
monolith, which has nothing left to serve." The `.choice()` collapsed from
six conditional flag branches plus a monolith default to one unconditional
per-context dispatch, `otherwise()` now returning `404` directly rather than
forwarding anywhere.

`examples/00-monolith/README.md` records where the complete "before" state
now lives, since `main` carries a frozen shell with zero live bounded
contexts:

```
Captured — examples/00-monolith/README.md, "Comparing before and after side by side"

reference/monolith-before branch (tag v0-monolith): the complete "before"
state, runnable end-to-end with no extracted services needed.
```

`stage/01-review-extracted` through `stage/06-order-extracted` tag each
extraction's completed state in sequence:

```
Captured — git tag -l -n1 stage/06-order-extracted

After extraction 6/6: Order+gateway extracted, monolith fully decommissioned
(frozen in-repo). The strangler completes -- six services + GraphQL gateway
behind a REST edge router; ACID->ACD for all contexts.
```

## Reconcile: the ledger, the ten decisions, and what is still open

`examples/00-monolith/SMELLS.md` carries the closing entries this trace's
whole arc was building toward — SMELL #2 and #3, the only two of the six
originally-planted smells struck through with a line rather than merely
annotated:

```
Captured — examples/00-monolith/SMELLS.md, SMELL #2 and #3 (struck)

~~God OrderService~~ -- CURED in order-plan.md S10 (DRQ-070), the FINAL
decommission. Order was deliberately left for LAST ... precisely because by
the time it moved, it had nothing left to reach into but its own aggregate
and its own saga reactions -- a genuinely independently deployable CQRS
service, not a god service with a shrunk blast radius.

~~One in-process ACID transaction spanning contexts~~ -- FULLY CURED in
order-plan.md S10 (DRQ-070/DRQ-075) -- ACID -> ACD realized for ALL contexts.
... There is no longer any in-process transaction anywhere in this system --
monolith or extracted -- that spans more than one bounded context's own
schema.
```

`_plans/decisions.md` carries DRQ-066 through DRQ-075 as **accepted**, each
row naming the step that realized it — the ledger was already current at the
time of this capture, the same discipline shipping's own trace found already
established by ch.24. What remains open at the point this trace was
captured, recorded here rather than assumed closed:

- **S11 (the order/gateway contract gate in GitHub Actions, red-then-green,
  plus the final cross-service cascade)** has not run yet — `git log` shows
  no `ci(r08.x)` order-gateway-gate commit, and `.github/workflows/code-ci.yml`
  does not yet reference an order-gateway-contract-gate job. S13's own
  dependency is only on "S10 evidence," not S11, so this is in scope for a
  later step, not a gap in this document.
- **S14 (ch.26 authored to the bar)** has not run yet — this trace is itself
  the source material S14 draws its callout from.
- **S15 (reconcile, status, exit)** has not run yet — `build-plan.md`'s §E
  row 6 and the six-extraction exit count have not yet been marked DONE in
  the reconciliation ledger from this step's own vantage point.

The resume boundary the plan itself records: with order extracted and the
monolith decommissioned, **all six bounded contexts are now independently
deployable services**, the strangler proxy is a flagless permanent REST edge
router, a new GraphQL aggregation gateway stands alongside it, and the
behavior-equivalence suite is now a contract/acceptance suite with no live
monolith in its gate. There is no seventh extraction. Part 8's remaining
chapters (ch.27 MicroProfile chassis, ch.28 Avro/Apicurio contracts) and
Parts 9–10 inherit six freshly-extracted Quarkus services, a CQRS read model,
and a GraphQL edge to secure and trace — not a strangler to complete.

---

## The condensed callout (for ch.26 — S14 to embed verbatim or adapt)

> **ADLC in Action** — This is the sixth and final extraction, and it ran
> the identical Frame → Map → Plan → Generate → Verify → Operate → Reconcile
> loop every prior chapter demonstrated, at the hardest shape yet. Frame
> brought FIVE architectural decisions to the user in one sitting and got
> all five confirmed before anything was built — same-DB CQRS over a
> separate datastore, an additive GraphQL aggregation gateway with its own
> front door over Apollo federation, a frozen-not-deleted monolith over
> deletion, an equivalence→contract conversion over a suite that would go
> vacuous, and reversibility proven one last time before the one
> system-wide irreversible move — plus five more decisions (DRQ-066 through
> DRQ-075, accepted) covering what moves, the customer FK decomposition, the
> two-phase service, idempotency, and the final cure of SMELL #2/#3. Map
> named six reasons order is the hardest rung: it is the god aggregate and
> the hub every other extraction left standing; there is no monolith left to
> be equivalent to; there is no fallback to revert to after decommission;
> two smells finally had to be cured; two net-new teaching surfaces (CQRS,
> GraphQL) land at once; and the strangler itself completes. Generate
> produced the two-phase order service (Phase A's own-schema lift, Phase B's
> CQRS write model plus four lifted saga reactions plus its own outbox), the
> CQRS read-model projection (`order_view`, idempotent, rebuildable, reads
> served exclusively from it), the SmallRye GraphQL aggregation gateway
> (depth/complexity bounded, fixed downstream targets, owning no data), and
> the sixth and final strangler flag — under quarkus-agent, lgtm-quarkus,
> and `migrate-spring-to-quarkus` for the lifted surface. Verify is this
> chapter's sharpest proof load: four environmental findings surfaced and
> fixed before any cutover evidence could be trusted (a
> brand-new order-id sequence colliding with a shared historical high-water
> mark of 322; a Kafka backlog spanning every topic since ch.23/ch.24; a
> stale downstream base-url that nearly produced a false-green shipping
> failure), real net-zero numbers (490→489→490, twice), the strongest
> flag-reach proof yet (the monolith 404s outright, having never minted the
> order at all), and three negative checks — consumer-disabled, projection-
> disabled (the CQRS non-vacuity proof), compensation-disabled — each shown
> RED then reverted GREEN. Operate is the final flag flip, reversibility
> proven one last time (164/172, the only gap a documented, by-design
> GraphQL/monolith correlation artifact), and then the one irreversible
> decommission: 31 files deleted from the monolith, the
> equivalence suite's `info.description` re-designated with "NOT ONE
> assertion/negative-check changed," and the complete pre-decommission
> system preserved forever on `reference/monolith-before` (tag
> `v0-monolith`) and the `stage/01`…`06-order-extracted` tags. Reconcile is
> `SMELLS.md` striking through SMELL #2 and #3 for good — ACID→ACD realized
> for all six contexts — with the strangler fig standing alone and no
> seventh extraction to resume into.

---

*Verification status: every figure quoted above is real, dated evidence
already committed to this repository — `examples/07-order-service/CUTOVER.md`
(the cutover, the four environmental findings, reversibility, and the three
negative checks), `examples/07-order-service/MIGRATION.md` (the Phase A/B
metrics and the saga-correctness/CQRS-projection design), `examples/08-graphql-gateway/README.md`
(the gateway's shape, security bounds, and tests), `examples/01-strangler-proxy/CUTOVER.md`
(the edge-router collapse and `EdgeRouterRoutingTest` 8/8), `examples/00-monolith/SMELLS.md`
and `README.md` (SMELL #2/#3 struck, the frozen "before" and the
`reference/monolith-before`/`v0-monolith`/`stage/*` referents), `_plans/decisions.md`
(DRQ-066…075, accepted), and the commits cited by hash above (`5637316`,
`a8c812e`, `848ea24`, `fe429b3`, `ea2615b`, `c522b8f`, `82bb319`, `7a070ea`,
`bc136ba`, `ce49202`). What remains open at the time of writing: S11 (the
order/gateway contract gate in GitHub Actions, red-then-green, plus the
final cross-service cascade) has not yet landed — no `ci(r08.x)` gate commit
exists and `code-ci.yml` does not yet reference an order-gateway-contract-gate
job; S14 (authoring ch.26 to the bar) has not yet run — this document is its
source material; and S15 (reconcile, status, exit) has not yet run — the
six-extraction exit count is not yet marked DONE from this step's own
vantage point. All three are tracked, not assumed, and this document should
be re-checked once S11/S14/S15 close them.*
