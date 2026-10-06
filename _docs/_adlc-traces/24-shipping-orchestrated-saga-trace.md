---
title: "ADLC in Action — the shipping orchestrated-saga trace (ch.24 source material)"
description: "Captured, narrated evidence (DRQ-025) for the ch.24 Shipping extraction, walking the Frame -> Map -> Plan -> Generate -> Verify -> Operate -> Reconcile loop against the real shipping-plan.md steps, commits, and CUTOVER.md/MIGRATION.md numbers. This is a companion artifact, not a chapter; it is the source the ch.24 \"ADLC in Action\" callout is drawn from, mirroring the ch.23 payment trace's format and, further back, ch.07's worked-loop format and the ch.17/ch.19 callouts."
published: false
---

> This is not a chapter. It is the pre-captured trace `_plans/iterations/shipping-plan.md`
> S12 asks for: the evidence, phase by phase, behind ch.24's eventual "ADLC in
> Action" callout (S13). Every quoted block below is real, dated output already
> sitting in this repository's own files and commit history —
> `examples/06-shipping-service/CUTOVER.md`, `examples/06-shipping-service/MIGRATION.md`,
> `examples/00-monolith/SMELLS.md`, `_plans/decisions.md`, and the `git log` of
> the r07 shipping-extraction work on `r02-walking-skeleton` — never re-run
> live to produce this document (DRQ-025). It mirrors the per-phase structure
> `_docs/_adlc-traces/23-payment-choreographed-saga-trace.md` established
> (itself mirroring `_docs/07-adlc-safety-net.md`'s worked loop and the
> ch.17/ch.19 callout template). S12 runs in parallel with S11 (diagrams) once
> S9's decommission evidence exists; S10 (the CI gate), S11, and S14 had not
> yet landed at the time this trace was captured — flagged as open at the end,
> not assumed closed.

## Frame: the orchestration decision, stated and defended before a line of code existed

Shipping's Frame phase is `_plans/iterations/shipping-plan.md` itself (S1) —
the Opus Plan artifact — and it fixed one fact before anything was
scaffolded: shipping would be extracted as a **bounded, orchestrated** saga,
not a continuation of ch.23's choreography.

```
Captured — _plans/iterations/shipping-plan.md, DRQ-056 (the load-bearing decision)

DECISION: Shipping is extracted as a new, bounded ORCHESTRATED saga,
triggered on payment.captured, whose COORDINATOR is a Camel Saga EIP route
co-located in the new Quarkus shipping service. It sequences the fulfilment
steps and registers the compensating action; on success it emits
shipment.dispatched (-> order CONFIRMED), on abort/timeout it compensates
(cancel shipment + emit shipment.failed -> order SHIPPING_FAILED + inventory
Release).
```

That row is why every later phase in this trace exists. Two alternatives
were considered and rejected in the same plan document, in writing, before S1
closed: **(b) re-express the whole order→payment→shipping flow as one
orchestrated saga** — rejected because it would tear out the already-done,
already-accepted choreographed payment saga (DRQ-048/049) and **erase the
very contrast the book is teaching**; and **shipping calling inventory gRPC
directly for the release** — rejected because the reserved-line snapshot
lives on the order aggregate, not in shipping, so the coordinator must
delegate the undo to the context that owns the data rather than reach into
it. Nine more decisions were framed alongside DRQ-056, covering the rest of
the saga's shape:

```
Captured — _plans/iterations/shipping-plan.md, DRQ-057 through DRQ-065 (condensed)

DRQ-057 — Coordinator = InMemorySagaService (camel-quarkus-saga), co-located;
  no new infra container. LRASagaService/Narayana deferred as the
  distributed/crash-durable alternative; a documented, accepted limitation
  (in-memory saga state is not crash-durable).
DRQ-058 — Orchestrated topology: payment.captured -> shipping saga ->
  shipment.dispatched (wires the already-reserved SHIPMENT_DISPATCHED topic)
  | shipment.failed (new topic). The order's CONFIRMED transition moves from
  payment.captured (ch.23) to shipment.dispatched (here).
DRQ-059 — Saga steps & compensating action: .saga().propagation(REQUIRES_NEW)
  .completionMode(AUTO).timeout(...).compensation("direct:ship-compensate")
  .option("orderId", ...) -> enrich -> dispatch -> book carrier (the
  deterministic SHIP-FAIL injection point) -> emit shipment.dispatched.
  direct:ship-compensate: cancel shipment + emit shipment.failed. Orchestrated
  (coordinator-decided) compensation, contrasted explicitly with ch.23's
  choreographed (each-service-reacts) compensation.
DRQ-060 — Compensation delegation across contexts: the compensating
  inventory Release is issued by the ORDER context (reusing the exact ch.23
  OrderSagaListener + RemoteInventoryClient machinery), not by shipping,
  because shipping does not own the reserved-line snapshot. The coordinator
  still owns the decision and ordering to compensate.
DRQ-061 — New order states AWAITING_SHIPMENT (intermediate) and
  SHIPPING_FAILED (terminal); orderId remains the saga correlation key; the
  external async contract (DRQ-047) is unchanged.
DRQ-062 — Deterministic SHIP-FAIL failure injection (mirrors payment's
  CARD-DECLINE), fired AFTER the Shipment row is persisted so both
  compensations (cancel + release) are exercised.
DRQ-063 — Two-phase read surface; saga/consumer/producer idiomatic from day
  one; FK decomposed to a plain orderId value; forward-filled, no CDC
  backfill; own transactional outbox.
DRQ-064 — Idempotency & at-least-once: saga consumer dedupes by orderId
  (unique constraint); order-context reactions guard the status transition;
  Release issued only on the first shipment.failed.
DRQ-065 — Reversibility via two flags (shipping.mode / strangler.shipping.
  enabled); equivalence extended for the orchestrated saga (longer Scenario 1
  + new shipping-failure scenario + Shipping Context Contract); a negative
  check (disable the Camel Saga compensation) proves the compensation is
  genuinely exercised; ACL accuracy (ShipmentDto portable, no translator).
```

Four decisions needed explicit user confirmation before S1 could close, and
the plan records all four as resolved the same day: the branch/iteration
label (work stays on `r02-walking-skeleton`, labels only say `r07`/`§24`),
saga scope (bounded, option (a)), coordinator choice (`InMemorySagaService`),
and the intermediate order state (`AWAITING_SHIPMENT` added). `_plans/decisions.md`
shows DRQ-056 through DRQ-065 already carried through to **accepted**, each
row naming the realizing step — a different state than ch.23's own trace
found at its own capture time, where DRQ-047…055 were written but not yet
copied into the ledger; shipping's ledger entries were current by S12.

## Map: why shipping is a harder/different rung than payment

Map's job is reconnaissance before Generate, and here — as with payment — the
reconnaissance is written directly into the plan's framing section. Payment
(ch.23) proved a **choreographed** saga: no coordinator, each service reacts
to events it subscribes to, and the compensation on decline is itself a
reaction. The plan names four concrete reasons shipping is a harder and
*differently shaped* rung, each of which this trace's later phases return to:

1. **There is now a coordinator, and it lives in the shipping service.**
   Writing a runnable Camel Saga EIP coordinator — picking the
   `CamelSagaService`, carrying correlation data into the compensation
   callback, getting `completionMode(AUTO)` abort semantics right — is
   net-new machinery payment never needed (H1).
2. **Compensation is coordinator-initiated and spans service boundaries.**
   In ch.23 the inventory `Release` fired because the order context
   *reacted* to `payment.declined`. Here the **coordinator decides** to
   compensate and *delegates* the cross-context undo (H3 — the crux and the
   biggest named risk).
3. **The order's `CONFIRMED` transition moves one hop later.** Today
   `onPaymentCaptured` immediately confirms and dispatches in-process; once
   shipping owns fulfilment, `CONFIRMED` is reached only via
   `shipment.dispatched`, so the equivalence suite's Scenario 1 bounded-wait
   must tolerate a longer convergence (H4).
4. **A new failure-bearing terminal outcome must be proven net-zero.**
   Payment's crux was Scenario 3 (decline ⇒ net-zero). Shipping's crux is a
   **new** shipping-failure scenario (forced `SHIP-FAIL` ⇒ `SHIPPING_FAILED`
   ⇒ net-zero), driven entirely by the orchestrated compensation, with a
   sharper negative check: disable `.compensation(...)` and the forced
   failure must leave stock decremented and the order not failed (H2).

The plan names these H1–H4 explicitly in a dedicated "THE HARD PARTS" section
and states a mitigation for each before Generate begins — H2 landed in S2
(the equivalence-suite contract, an Opus gate), H1/H3 in S5 (the saga core,
an Opus gate), H3/H4 again in S6 (the monolith wiring, an Opus gate), and all
four get their final proof at S8 (the equivalence/cutover gate).

## Plan: fourteen steps, two parallel lanes, Opus gates at nearly every rung

With Frame and Map settled, Plan is where the step sequence became a
commitment a human signed off on before Generate touched a file. The approved
sequence (`_plans/iterations/shipping-plan.md`, the parallelism diagram) ran:

```
Captured — _plans/iterations/shipping-plan.md, the parallelism overview

S1 (frame + branch + decisions, SEQUENTIAL, must be first)
S2 equivalence suite: extend for the ORCHESTRATED saga (longer Scenario 1;
   NEW shipping-failure scenario; Shipping Context Contract); baseline green
   vs monolith (in-process shipping)                                   [Opus gate] <- THE crux
 |- S3 event contract: SHIPMENT_FAILED + wire SHIPMENT_DISPATCHED (shared)
 \- S4 shipping svc scaffold + Phase A read-surface lift (lane N)       [Opus gate]
S5 shipping svc Phase B idiomatic + Camel Saga EIP orchestrator (SEQ)   [Opus gate] <- HARD PART
S6 monolith wiring: shipping.mode flag; AWAITING_SHIPMENT/SHIPPING_FAILED;
   order-saga reactions (lane M, SEQ)                                  [Opus gate] <- HARD PARTS
S7 strangler proxy: shipping flag + /api/shipments route (transparent)
S8 CUTOVER: flip both flags; equivalence green; reversibility; negative
   check (SEQ after S6+S7)                        [Opus gate - equivalence gate] <- HARD PARTS
S9 DECOMMISSION monolith in-process shipping path                      [Opus gate]
S10 Code-CI: shipping equivalence gate, red-then-green                 [Opus gate]
 |- S11 ch.24 diagram(s)          (PARALLEL after S5)
 \- S12 ch.24 ADLC trace          (PARALLEL after S9 evidence exists)  <- this document
S13 ch.24 authored to the bar (SEQ)                           [Opus gate - 2k + footer]
S14 reconcile + status + exit (SEQ, last)                              [Opus gate]
```

Opus validation gates sit on S2, S4, S5, S6, S8, S9, S10 — nearly every
rung — and the plan's own "Biggest risks" section names, as **highest**, the
orchestrated compensation silently failing or diverging (H3): the mitigation
chain it lays out — baseline Scenario 4 green vs. the monolith first, a saga
abort-path unit test, an idempotent order reaction, a forced `SHIP-FAIL` at
cutover bounded-waiting stock to net-zero, and CI proving it red-then-green
by disabling the compensation — is exactly the sequence Verify below shows
actually happening. A second named risk — the coordinator "looks wired but
never compensates" (H1) — is the subtler failure mode Generate's S5 had to
rule out directly, not just at cutover.

## Generate: the orchestrator, the monolith's new lifecycle, and a routing decision proven moot

Generate is where the plan became code, run across S3 through S7.

**S3** defined the saga's event vocabulary first: `SHIPMENT_FAILED` added to
`common/Topics.java` alongside the already-reserved but previously unused
`SHIPMENT_DISPATCHED`, plus matching JSON payload records on both sides
(commit `050685a`, `feat(shipping): event contract — wire shipment.dispatched
+ add shipment.failed topic + JSON payloads`).

**S4 (Phase A)** scaffolded `examples/06-shipping-service` on its own schema
from day one, lifting the read surface via Quarkiverse Spring-compat and
decomposing the `Shipment` entity's `@ManyToOne Order` FK to a plain
`orderId` value — the same move payment's entity made (commit `4950070`, 9
tests green, Opus-gated GO on own-schema isolation, byte-for-byte contract,
and the FK decomposition).

**S5 (Phase B)** is this extraction's hardest Generate pass — net-new
machinery, not a refactor of an existing choreography:

```
Captured — git log, commit 0f0b345 (S5, Phase B + Camel Saga EIP orchestrator)

feat(shipping): Phase B — idiomatic Quarkus + Camel Saga EIP orchestrator
(InMemorySagaService): consume(payment.captured)/dispatch/compensate/emit via
transactional outbox (idempotent, measured)

Phase B removes spring-compat (JAX-RS ShippingResource + @ServerExceptionMapper,
Panache, @ApplicationScoped); /api/shipments contract unchanged. Orchestrated
core: @Incoming(payment.captured) -> Camel .saga()(InMemorySagaService,
completionMode AUTO, 15s timeout, compensation=direct:ship-compensate,
option orderId) -> enrich -> dispatch(persist PENDING) -> book-carrier(SHIP-FAIL
throw point) -> emit shipment.dispatched. Step 4 flips PENDING->DISPATCHED + outbox
insert in ONE @Transactional; compensate flips PENDING->CANCELLED + shipment.failed
in ONE @Transactional (atomic emit; resolves DRQ-059/062/063 tension). Compensation
fires on any abort/timeout exactly-once (Camel saga SPI). Idempotent by orderId
(findByOrderId guard + uq_shipments_order_id V3). Own transactional outbox (V4).

Enrichment: OrderReadClient GET /api/orders/{id} for shippingAddress, with a safe
ADDRESS-UNAVAILABLE-PENDING-S6 fallback (never == SHIP-FAIL). S6 MUST add
shippingAddress to OrderDto for live enrichment + the live SHIP-FAIL demo.

16 tests green (incl. SHIP-FAIL abort+compensate, idempotent redelivery via real
@Incoming pipeline, AdviceWith compensation-exactly-once); camel_validate_route
clean. Phase A->B: 1.519s/289MB/15feat -> 2.002s/364MB/21feat. Opus-gated GO.
```

That "S6 MUST add" line is S5 naming, in its own commit message, a gap it is
leaving open rather than quietly papering over — the
`OrderDto` the order context exposes did not (yet) carry a shipping address,
so every live-enriched dispatch at this point in the extraction used a
documented sentinel, `ADDRESS-UNAVAILABLE-PENDING-S6`, verified live against
the real podman stack with zero errors. `MIGRATION.md` records why the
"dispatch persists PENDING, not DISPATCHED" shape (`ShipmentStatus.PENDING`,
reserved but unused in Phase A) was the only way to jointly satisfy three
constraints at once — DRQ-059's "persist Shipment DISPATCHED" prose, DRQ-063's
atomic-emit requirement, and DRQ-062's requirement that the row already exist
before the carrier-booking step can throw:

```
Captured — examples/06-shipping-service/MIGRATION.md, atomicity design

1. Step 2 (dispatch) persists a NEW Shipment row as PENDING, in its own
   committed transaction.
2. Step 3 (book carrier) is a pure check -- no persistence -- and is the
   deterministic SHIP-FAIL throw point.
3. Step 4 (emit, happy path) -- in ONE @Transactional method -- transitions
   that SAME row PENDING->DISPATCHED and persists the shipment.dispatched
   outbox row.
4. Compensation (abort/timeout) -- in ONE @Transactional method -- transitions
   the row PENDING->CANCELLED and persists the shipment.failed outbox row.
```

And the measured before/after metrics, the same `MIGRATION.md` template
payment established:

```
Captured — examples/06-shipping-service/MIGRATION.md, before/after metrics

| Build                                                              | Startup | RSS     | Features |
|----------------------------------------------------------------------|------:|-------:|---------:|
| Phase A — JVM, Spring-compat                                          | 1.519s | ~289MB |       15 |
| Phase B — JVM, idiomatic + Camel Saga EIP + Kafka consumer/producer   | 2.002s | ~364MB |       21 |
```

Startup is ~32% slower and RSS ~26% higher Phase A to Phase B — noticeably
larger than payment's own Phase A→B deltas (~23%/~12%), because this step
adds **both** a full Camel engine *and* the Kafka messaging stack, where
payment's choreography only needed the latter. `MIGRATION.md` frames this as
the real cost of the orchestrated core actually running, not a regression to
be optimized away, and documents two concrete gotchas found and fixed
during this step: a plain `new InMemorySagaService()` CDI bean threw
`NullPointerException` on first use because CDI never called Camel's
`Service#start()` on it (fixed by explicitly registering it with the
`CamelContext` via `addService(sagaService, true, true)`), and the Reactive
Messaging consumer needed `@ActivateRequestContext` because
`processPaymentCaptured` is deliberately *not* `@Transactional` (each saga
step owns its own transaction boundary).

**S6** closed S5's flagged gap and gave the monolith its new lifecycle
(H3/H4) — covered in full under Verify below, since its checkpoint commit
*is* an Opus sign-off.

**S7** wired the strangler proxy's shipping flag and route, and — like
payment's S7 before it — found the anticipated translator was unneeded:

```
Captured — git log, commit 64e23e4 (S7)

feat(strangler): shipping flag + /api/shipments route (transparent reverse
proxy; ACL honesty — no translator, DTO identical)

Append strangler.shipping.enabled (default false -> monolith) +
strangler.shipping.base-url=:8088 and the TARGET_SHIPPING content-based branch
(full /api/shipments path match, bridgeEndpoint transparent proxy). ShipmentDto
byte-for-byte identical monolith vs shipping-service -> no ShippingAclRoute
(honest transparent-proxy precedent).
```

This is the third extraction in a row (Review, then Payment, now Shipping) to
check a translator's necessity against the real DTO shapes on both sides
rather than build one on the strength of analogy — the same discipline, not
a new one.

## Verify: three Opus gates, a CLI-limitation finding, and the crux proven with real numbers

Verify is where this extraction's hardest claim — that a coordinator-decided,
cross-context compensation provably fires and converges to net-zero stock —
gets checked against running code rather than asserted in prose.

**S2's Opus gate** (commit `da82287`) extended the suite for the longer
chain and the new terminal outcome *without weakening either assertion*:
Scenario 1's `CONFIRMED` bounded-wait was widened from the payment-era
budget to `20×750ms` for the extra Kafka hop (the terminal `eql('CONFIRMED')`
assertion itself untouched); a new Scenario 4 (Shipping-Failure) was added,
asserting *terminal* `SHIPPING_FAILED` **and** inventory net-zero, but
gated **pending at baseline** via a `shippingSagaEnabled` flag
— explicitly marked, not falsely green on a path the in-process monolith
cannot yet exercise. Baseline: **121 assertions, 0 failed, Scenario 4
correctly skipped** — green against the still-in-process monolith before any
saga code existed.

**S5's Opus gate** confirmed the hardest correctness property the orchestrator
itself must satisfy — that compensation fires on *any* abort or timeout
exactly once, and that the outbox makes each emit atomic with the Shipment
state change:

```
Captured — git log, commit 0f0b345 (S5, Opus-gated, the saga-correctness excerpt)

Compensation fires on any abort/timeout exactly-once (Camel saga SPI).
Idempotent by orderId (findByOrderId guard + uq_shipments_order_id V3). Own
transactional outbox (V4). ... 16 tests green (incl. SHIP-FAIL abort+compensate,
idempotent redelivery via real @Incoming pipeline, AdviceWith compensation-
exactly-once); camel_validate_route clean. ... Opus-gated GO. Documented
limitations: in-memory saga non-durability (LRA deferred), crash-window
PENDING orphan, concurrent-redelivery (single-partition model; ch.25 hardening).
```

`MIGRATION.md` is explicit that Camel's own saga SPI — not this code —
provides the "compensated or completed, never both, at most once" guarantee;
the implementation's job was correlating the compensation lookup by `orderId`
(via `ShipmentRepository#findByOrderId`, the same mechanism the idempotency
guard uses) rather than betting on undocumented Camel-internal timing for a
later-captured `.option(...)` value. The reinterpretation of DRQ-059's
"persist Shipment DISPATCHED" as "persist `PENDING`, flip to `DISPATCHED`
only atomically with the emit" is flagged explicitly in both the commit and
`MIGRATION.md` as a deliberate, documented interpretation of the plan's
shorthand — not a silent deviation.

**S6's Opus gate** closed S5's flagged enrichment gap and wired the
monolith's new lifecycle (H3/H4):

```
Captured — git log, commit 973bc19 (S6, Opus-gated)

Add shipping.mode=inprocess|orchestrated (default inprocess, baseline byte-for-byte).
Orchestrated: onPaymentCaptured -> AWAITING_SHIPMENT (no confirm/dispatch); new
@KafkaListeners onShipmentDispatched -> CONFIRMED and onShipmentFailed ->
SHIPPING_FAILED + compensating gRPC Release for every reserved sku (DRQ-060,
reuses onPaymentDeclined machinery). Both reactions idempotent (status guard;
Release once). No CONFIRMED without shipment.dispatched; no double-compensation
(PENDING vs AWAITING_SHIPMENT disjoint). Documented limitation: inventory
compensated, captured payment NOT refunded (bounded saga, DRQ-056).

Expose OrderDto.shippingAddress (+ toDto) so the shipping saga enriches live via
GET /api/orders/{id} (closes S5's flagged gap) — additive, suite-safe.

Opus-gated GO, 37 tests green, live e2e proven (orchestrated CONFIRMED + SHIP-FAIL
net-zero per-sku; inprocess reversibility; backlog-replay idempotency).
```

**S8's equivalence gate** is where H3 — the crux — gets proven with numbers,
not an assertion count. Before any assertion ran, S8 surfaced a
finding the plan's own text had not anticipated: the plan proposed enabling
the pending Scenario 4 folders via `newman --env-var "shippingSagaEnabled=true"`,
and this was tried first and empirically shown **not** to work —

```
Captured — examples/06-shipping-service/CUTOVER.md, the CLI-limitation finding

newman's --env-var/--global-var flags populate the ENVIRONMENT/GLOBAL
variable scopes -- a DIFFERENT scope from the collection-level `variable`
array a Postman collection's own JSON defines. A real run ... produced,
verbatim, 'Scenario 4 (Shipping-Failure) is PENDING at baseline
(shippingSagaEnabled=false): ...' in the console output -- the collection
variable was never set, despite the flag. 127/127 assertions still passed in
that run (SF-a..SF-d were simply skipped ...), so this would have been an
easy false green to miss.
```

The fix — load the collection JSON via newman's Node API, patch the
in-memory `shippingSagaEnabled` variable, and hand the in-memory object to
`newman.run(...)` — was packaged into `demos/lib/run-shipping-newman.js` and
used for every cutover run thereafter; the committed collection file
on disk was never rewritten. With both flags flipped
(`shipping.mode=orchestrated`, `strangler.shipping.enabled=true`,
`shippingSagaEnabled=true` via the helper), the full suite ran green through
the proxy repeatedly, and the forced-`SHIP-FAIL` stock trace — the H3 crux —
was captured directly against the inventory service, twice:

```
Captured — examples/06-shipping-service/CUTOVER.md, forced-SHIP-FAIL stock trace

| Step                                                          | SKU-WIDGET-001 | Order status      |
|-------------------------------------------------------------|-------------:|-------------------|
| Before checkout                                              |          496 | --                |
| Immediately after POST (synchronous gRPC Reserve committed)  |          495 | PENDING           |
| After the bounded-wait observes SHIPPING_FAILED              |          496 | SHIPPING_FAILED   |

Net-zero: 496 -> 495 -> 496. Repeated again inside the scripted run, a
different order, after additional suite runs had consumed stock: 478 -> 477
-> 478.
```

The bounded-wait polls behind those numbers were not instant: Scenario 1
looped 5 retries (~3.75s) before observing `CONFIRMED`, and
Scenario 4's SF-c folder looped 5 retries before observing `SHIPPING_FAILED`
— real `order.placed` → `payment.captured` → shipping-saga → `shipment.dispatched`
(or `.failed`) → order-reaction latency, not an attempt-0 pass. The same
CUTOVER.md run closed the routing-proof loop ch.23's trace established the
pattern for: `GET /api/shipments?orderId=276` through the proxy returned
byte-identical JSON to the same query direct to `:8088`, while the same query
against the monolith directly (`:8080`) returned an empty array — proof
`/api/shipments` traffic was reaching `examples/06-shipping-service`.

**The two negative checks (DRQ-065, H2/H3)** are the sharper proof, because a
green suite alone cannot distinguish a firing compensation from one
that would pass regardless. First, the Camel Saga `.compensation(...)`
registration was commented out, the service rebuilt and restarted, and a
forced `SHIP-FAIL` order re-run:

```
Captured — examples/06-shipping-service/CUTOVER.md, negative check (a), compensation disabled

1. AssertionError  Order reaches SHIPPING_FAILED within the bounded-wait budget (20 x 750ms)
                   order 260 status after 20 attempt(s): expected 'AWAITING_SHIPMENT' to deeply equal 'SHIPPING_FAILED'

2. AssertionError  Quantity on hand returns to net-zero after the shipping failure (20 x 750ms budget)
                   quantityOnHand after 20 attempt(s): expected 487 to deeply equal 488

RED — 2 of 163 (manual, order 260) / 2 of 47 (scripted, order 280) assertions failed.
```

With no compensation route registered, the Camel Saga coordinator had
nothing to invoke on the thrown `ShipFailException` — `shipment.failed` was
never emitted, the order stuck permanently at `AWAITING_SHIPMENT`, and stock
stayed decremented — exactly the predicted failure mode. The edit was
reverted byte-for-byte (confirmed via a read-only `git status` showing a
clean working tree — git was never used to perform the revert itself), the
service rebuilt, and Scenario 4 re-run **GREEN — 127/127 (manual) and 16/16
(scripted)**. Second, the shipping service's sole `payment.captured` consumer
was killed and a normal checkout placed through the proxy:

```
Captured — examples/06-shipping-service/CUTOVER.md, negative check (b), consumer down

1. AssertionError  Order reaches CONFIRMED within the bounded-wait budget (20 x 750ms)
                   order 266 status after 20 attempt(s): expected 'AWAITING_SHIPMENT' to deeply equal 'CONFIRMED'

RED — 1 of 33 assertions failed.
```

With the consumer restarted, Kafka's at-least-once redelivery of the
un-committed offset drained the backlog within seconds and a fresh checkout
went **GREEN — 19/19 assertions, 0 failed**.

**A gap was also found and closed rather than silently absorbed.** The
scripted cutover run (`demos/demo-shipping-cutover.sh`) produced exactly one
intermittent failure, isolated to the pre-existing Notification Context
Contract's own, separate 5c bounded-wait (confirmation-notification
observability) — not any shipping-specific assertion. Root-caused as a
timing margin, not a correctness defect: 5c's own `10×500ms` (5s) budget had
never been widened when Scenario 1's confirm-bounded-wait was widened for
the now-longer chain, so an order that used most of its `20×750ms` confirm
budget left 5c's unrelated, unwidened window too tight. **Closed immediately
after S8, before S9:**

```
Captured — git log, commit 04aaf86 (de-flake, after S8, before S9)

test(equivalence): widen Notification 5c bounded-wait budget (10x500->20x750)
for the longer orchestrated chain

De-flake found in S8 cutover: 5c's confirmation-notification poll kept the
original 5s budget while Scenario 1/5b were widened in S2; under the longer
orchestrated chain (...->shipment.dispatched->CONFIRMED->notification) it
flaked ~1-in-5. Budget-only change (now matches 5b/1c); assertion byte-for-byte
unchanged, still RED on a never-arriving notification. Verified green across 4
cutover runs + baseline.
```

Of the five full-suite cutover runs performed in the CUTOVER.md evidence
trail, four were completely clean; this was the one intermittent exception,
and every shipping-specific assertion in that same run passed — the same
category of collateral, pre-existing-folder gap payment's own CUTOVER.md
documented for a different folder after ch.23.

**S9's decommission gate** is the last Opus sign-off this trace covers, and
the property it checks is the one H3's mitigation chain was always protecting
against — a stock leak or a double dispatch once the in-process path is gone
for good:

```
Captured — git log, commit 19575f0 (S9, Opus-gated)

Orchestrated is now the only shipping path: removed shipping.mode, the in-process
dispatch branch, and the monolith shipping module (ShippingController/Service/
Shipment/ShipmentStatus/ShipmentDto/Repository). onPaymentCaptured now
unconditionally PENDING->AWAITING_SHIPMENT; the saga + onShipmentDispatched/
onShipmentFailed drive the rest. GET :8080/api/shipments -> 404. ...
strangler.shipping.enabled=true is now the committed proxy default
(reversibility closed on the read side).

Single path / no double-dispatch (Opus-verified); compensation intact
(onShipmentFailed per-sku Release, net-zero); CONFIRMED only via shipment.dispatched.
... mvn verify green (monolith 33 + proxy 8). Opus-gated GO.
```

## Operate: both flags flipped, reversibility proven, then decommissioned

Operate is where the saga stopped being a side-by-side comparison and started
actually answering `/api/shipments` traffic. The two flags and their states:

```
Captured — examples/06-shipping-service/CUTOVER.md, the two flags

| Flag                          | Location                              | Committed default | Cutover override |
|--------------------------------|----------------------------------------|--------------------|-------------------|
| shipping.mode                  | monolith application.yml (SHIPPING_MODE)| inprocess         | orchestrated      |
| strangler.shipping.enabled     | proxy application.properties           | false              | true              |
```

With both overrides applied at runtime (no committed default changed yet),
the full suite ran green across the seam (summarized under Verify above), and
reversibility was demonstrated before anything was made permanent: both
overrides removed, a direct sanity checkout confirmed the in-process contract
was back (`202 Accepted` / `PENDING`, one bounded-wait poll later
`CONFIRMED`, now just the `payment.captured` hop — no shipping-saga hop), and
a full re-run produced **112/112 assertions green, 0 failed, 5.4s** (vs.
~13–18s in the cutover state) — Scenario 4's gate correctly reported
"PENDING at baseline" again. A benign, documented observation from this
window: the shipping service's own `payment.captured` consumer has no
knowledge of the monolith's flag and keeps running its saga to completion for
every order during the reversibility window, persisting an unread "shadow"
`Shipment` the monolith's status-guarded reactions never act on — the guard
alone (not a separate mode check) keeps that reaction permanently dormant.
Re-run inside the scripted demo: **114/114, 0 failed, 7s** — proof that
flipping both flags back is a config change and a restart, nothing more,
right up until decommission.

S9 is where that reversibility window closed. The monolith's
in-process `ShippingController`/`ShippingService`/`Shipment`/`ShipmentStatus`/
`ShipmentDto`/`ShipmentRepository` and the `shipping.mode` flag were removed
entirely (`GET :8080/api/shipments` now returns `404`); `strangler.shipping.enabled=true`
became the **committed** proxy default, the same closing move
Review/Notification/Inventory/Payment each made at their own decommission
step; and the in-process `shippingService.dispatch(...)` call was removed
from `OrderSagaListener#onPaymentCaptured` entirely — the order now
unconditionally moves `PENDING`→`AWAITING_SHIPMENT`, and the saga plus its
two reaction consumers drive everything after.

## Reconcile: the ledger, and what is still open

`examples/00-monolith/SMELLS.md`'s smell table carries the closing entry for
this extraction, extending the same row ch.23 began:

```
Captured — examples/00-monolith/SMELLS.md, SMELL[ch.22] excerpt (shipping clause)

Realized for shipping too in r07/ch.24 S9 (DRQ-056/058/060/061) -- the same
ACID -> ACD story cashed in for this context. The in-process shipping
dispatch no longer rides any checkout/payment transaction at all ...
Fulfilment is now owned end-to-end by the shipping service's Camel Saga EIP
coordinator ... The smell is therefore NOT struck through above: it is cured
for payment and shipping, not yet for order, which is exactly the resume
boundary ch.26 picks up.
```

Unlike ch.23's own trace — captured before DRQ-047 through DRQ-055 had been
copied into `_plans/decisions.md`'s accepted table — this extraction's ledger
was already current at the time of this capture: `decisions.md` carries
DRQ-056 through DRQ-065 as **accepted**, each row naming the step that
realized it (S1 frame through S8 cutover). What remains open at the
point this trace was captured, recorded here rather than assumed closed:

- **S10 (the shipping equivalence gate in GitHub Actions, red-then-green,
  plus the cross-service cascade fix for sibling gates)** has not run yet —
  `git log` shows no `ci(r07.x)` shipping-gate commit, and `.github/workflows/code-ci.yml`
  does not yet reference the shipping service. S12's own dependency is only
  on "S9 evidence," not S10, so this is in scope for a later step, not a gap
  in this document.
- **S11 (the ch.24 diagrams)** has not run yet either —
  `assets/diagrams/README.md`'s catalogue has no ch.24 rows at the time of
  this capture (its newest entries are ch.23's). S11 runs in parallel with
  S12, not before it, so this is expected, not a defect.
- **S14 (reconcile)** has not run yet — `_plans/reconciliation.md`'s most
  recent resume-boundary section is still payment's (for ch.24), with no
  ch.24 entry of its own yet. This trace draws its ledger evidence from
  `decisions.md` directly rather than from a reconciliation pass that has not
  happened.

The resume boundary the plan itself records: with shipping extracted, **both
saga styles now exist on one codebase** — choreographed (payment) and
orchestrated (shipping, via the Camel Saga EIP) — and five of six contexts
are out of the monolith. Only the order god-aggregate remains, and ch.26 (the
last, hardest extraction) picks it up: CQRS, a GraphQL gateway, and the final
decommission of the monolith, inheriting the order-context reaction machinery
(`OrderSagaListener`) that now hosts reactions for both coordinators at once.

---

## The condensed callout (for ch.24 — S13 to embed verbatim or adapt)

> **ADLC in Action** — This extraction ran the identical Frame → Map → Plan →
> Generate → Verify → Operate → Reconcile loop Chapter 23 demonstrated for
> Payment, at a different shape. Frame fixed the orchestration
> decision (DRQ-056) before anything was built — a bounded saga on
> `payment.captured`, coordinated by a Camel Saga EIP route, with a whole-flow
> re-expression and a direct shipping-calls-inventory shortcut both considered
> and rejected — plus nine more decisions (DRQ-057 through DRQ-065) covering
> the coordinator choice (`InMemorySagaService`, no new infra container), the
> event topology, the saga's step shape and compensation, delegated
> cross-context compensation, two new order states, deterministic failure
> injection, the two-phase service, idempotency, and two-flag reversibility.
> Map named four reasons this rung is harder than choreography: a
> coordinator now has to exist, its compensation is coordinator-initiated and
> crosses service boundaries, the order's `CONFIRMED` moves one hop later, and
> a new failure-bearing terminal outcome had to be proven net-zero. Plan laid
> out fourteen steps across two parallel lanes with Opus gates at nearly every
> rung, naming the orchestrated compensation silently failing or diverging as
> the single biggest risk before a line of saga code existed. Generate
> produced the Camel Saga EIP orchestrator (`.saga()/.compensation()/.option()/.timeout()`,
> `InMemorySagaService`), the monolith's new order lifecycle
> (`AWAITING_SHIPMENT`/`SHIPPING_FAILED`), and a proxy route that again did not
> need the translator the plan had anticipated — under quarkus-agent,
> lgtm-quarkus, lgtm-camel, and camel-mcp tooling, following
> `migrate-spring-to-quarkus` for the lifted read surface. Verify is this
> chapter's sharpest negative checks and its real stock numbers —
> 496→495→496, 478→477→478 — plus a CLI-limitation finding (newman's
> `--env-var` cannot set a collection-scoped variable) found, diagnosed, and
> worked around before the cutover evidence could even begin, and a
> downstream timing flake found and closed immediately after. Operate is the
> two-flag cutover `CUTOVER.md` records end to end, reversibility proven at
> 112/112 then 114/114, and the committed-default flip at decommission.
> Reconcile is `SMELLS.md` marking Smell[ch.22] cured for payment **and**
> shipping — not yet for order — with both saga styles now standing side by
> side on one codebase, and ch.26's order+CQRS+GraphQL extraction as the named
> resume boundary.

---

*Verification status: every figure quoted above is real, dated evidence
already committed to this repository — `examples/06-shipping-service/CUTOVER.md`
(the cutover, reversibility, negative-check, and CLI-limitation timeline),
`examples/06-shipping-service/MIGRATION.md` (the Phase A/B metrics, the
atomicity design, and the two implementation gotchas), `examples/00-monolith/SMELLS.md`
(the SMELL[ch.22] shipping clause), `_plans/decisions.md` (DRQ-056…065,
accepted), and the commits cited by hash above (`70bceab`, `2a81bdc`,
`da82287`, `050685a`, `4950070`, `0f0b345`, `64e23e4`, `973bc19`, `eceedc1`,
`04aaf86`, `19575f0`). What remains open at the time of writing: S10 (the
shipping equivalence gate in GitHub Actions, red-then-green, plus the
cross-service cascade fix) has not yet landed — no `ci(r07.x)` shipping-gate
commit exists and `code-ci.yml` does not yet reference the shipping service;
S11 (the ch.24 diagrams) has not yet landed — `assets/diagrams/README.md`
carries no ch.24 rows yet; and S14 (reconcile) has not yet run —
`_plans/reconciliation.md` has no ch.24 resume-boundary section of its own
yet. All three are tracked, not assumed, and this document should be
re-checked once S10/S11/S14 close them.*
