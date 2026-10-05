---
title: "ADLC in Action — the payment choreographed-saga trace (ch.23 source material)"
description: "Captured, narrated evidence (DRQ-025) for the ch.23 Payment extraction, walking the Frame -> Map -> Plan -> Generate -> Verify -> Operate -> Reconcile loop against the real payment-plan.md steps, commits, and CUTOVER.md/MIGRATION.md numbers. This is a companion artifact, not a chapter; it is the source the ch.23 \"ADLC in Action\" callout is drawn from, mirroring ch.07's worked-loop format and the ch.17/ch.19 callouts."
published: false
---

> This is not a chapter. It is the pre-captured trace `_plans/iterations/payment-plan.md`
> S12 asks for: the evidence, phase by phase, behind ch.23's eventual "ADLC in
> Action" callout (S13). Every quoted block below is real, dated output already
> sitting in this repository's own files and commit history — `examples/05-payment-service/CUTOVER.md`,
> `examples/05-payment-service/MIGRATION.md`, `examples/00-monolith/SMELLS.md`,
> and the `git log` of the `r06-payment-extraction` work — never re-run live to
> produce this document (DRQ-025). It mirrors the per-phase structure
> `_docs/07-adlc-safety-net.md` established and the condensed callout template
> `_docs/17-extraction-2-notification-service.md` / `_docs/19-cdc-and-extraction-3-inventory.md`
> use.

## Frame: nine decisions, written down before a line of code existed

Payment's Frame phase is `_plans/iterations/payment-plan.md` itself (S1) — the
Opus Plan artifact — and it fixed the one fact the whole extraction turns on
before any service was scaffolded: checkout was about to stop answering its
own question synchronously.

```
Captured — _plans/iterations/payment-plan.md, DRQ-047 (the load-bearing decision)

DECISION: POST /api/orders becomes asynchronous. It returns 202 Accepted with
the order persisted in status PENDING and a Location header; the order then
reaches a terminal state (CONFIRMED | PAYMENT_DECLINED) eventually, via the
choreography, and clients (and the equivalence suite) observe that terminal
state by polling GET /api/orders/{id}.
```

That one row is why every later phase in this trace exists. `201-with-PENDING`
and "keep the synchronous `402`" were both considered and rejected in the same
plan document, in writing, before S4 scaffolded anything — a `201` that might
silently flip to a decline later is a response that claims more than it knows
at response time, and the plan names that explicitly as the reason `202` was
chosen instead. Eight more decisions were framed alongside it, covering the
rest of the saga's shape:

```
Captured — _plans/iterations/payment-plan.md, DRQ-048 through DRQ-055 (condensed)

DRQ-048 — Choreographed topology & event contract: order.placed (produced by
  the monolith) -> payment service captures -> payment.captured |
  payment.declined (produced by the payment service). No central orchestrator.
DRQ-049 — Compensation via choreography, re-plumbing DRQ-042: the inventory
  Release moves from placeOrder's in-line catch to the order-saga's reaction
  to payment.declined.
DRQ-050 — Saga state lives on OrderStatus: PENDING -> CONFIRMED |
  PAYMENT_DECLINED; orderId is the saga correlation key.
DRQ-051 — Idempotency: the payment consumer dedupes by orderId (unique
  constraint); the order-saga reaction guards the status transition and
  issues Release only on the first payment.declined.
DRQ-052 — Payment service: two-phase read surface (Phase A spring-compat ->
  Phase B idiomatic); the event consumer/producer is idiomatic from day one
  (no Spring original to lift).
DRQ-053 — The payment service emits via its OWN transactional outbox,
  mirroring DRQ-034.
DRQ-054 — Reversibility via two flags: monolith payment.mode (default
  synchronous), proxy strangler.payment.enabled (default false).
DRQ-055 — Equivalence under a choreographed saga: the suite asserts the
  terminal business outcome via bounded-wait, tolerant of 201|202 at POST,
  guarded by a negative check.
```

## Map: why Payment is a harder rung than Inventory

Map's job is reconnaissance before Generate, and here the reconnaissance is
already written into the plan's framing section. Inventory (ch.19) was
engineered to leave checkout's synchronous contract untouched —
Reserve stayed a blocking gRPC call inside `placeOrder`, so `201`/`402`
never changed and Scenario 1-3 were left byte-for-byte unedited (DRQ-046).
Payment is the opposite case, and the plan names three concrete reasons it is
harder, each of which this trace's later phases return to:

1. **The externally-observable contract changes.** Once payment is consumed
   from an event after the HTTP response returns, checkout cannot report
   `CONFIRMED`/`402` synchronously — this is the first extraction that moves a
   user-visible, failure-bearing outcome off the request thread.
2. **The two critical payment assertions in the equivalence suite must be
   re-expressed without being weakened** — Scenario 1 (`CONFIRMED`) and
   Scenario 3 (decline + inventory net-zero) are the reason the chapter
   exists, and a suite that relaxes "expect `402`" into "accept anything"
   would make Scenario 3 vacuous.
3. **Compensation moves from a `catch` block to the choreography.** ch.19's
   inventory `Release` fired from an in-line `catch (RuntimeException)`; in the
   saga the decline is a `payment.declined` event with no request-thread catch
   to lean on, and the Release must be triggered by reacting to it across two
   asynchronous hops.

The plan names these H1-H4 and states a mitigation for each before Generate
begins — H1/H2 landed in S2 (the equivalence-suite contract), H3 (the crux)
in S6/S8, H4 (the monolith's first Kafka *consumer*) in S6.

## Plan: fourteen steps, two parallel lanes, five Opus gates

With Frame and Map settled, Plan is where the step sequence became a
commitment a human signed off on before Generate touched a file. The approved
sequence (`_plans/iterations/payment-plan.md`, the parallelism diagram) ran:

```
Captured — _plans/iterations/payment-plan.md, the parallelism overview

S1 (frame + branch + decisions, SEQUENTIAL)
S2 equivalence suite: async checkout contract; baseline vs monolith   [Opus gate]
 |- S3 event contract (shared)                    (parallel after S1)
 \- S4 payment svc scaffold + Phase A (lane N)     (parallel)          [Opus gate]
S5 Phase B idiomatic + Kafka consume/capture/emit via outbox (SEQ)     [Opus gate]
S6 monolith choreography wiring (lane M, SEQ)                         [Opus gate]
S7 strangler proxy: payment flag + /api/payments route
S8 CUTOVER: both flags; equivalence green; reversibility; negative check [Opus gate]
S9 DECOMMISSION monolith synchronous payment path                     [Opus gate]
S10 Code-CI: payment equivalence gate, red-then-green                 [Opus gate]
 |- S11 ch.23 diagrams            (PARALLEL after S5)
 \- S12 ch.23 ADLC trace          (PARALLEL after S9 evidence exists)  <- this document
S13 ch.23 authored to the bar (SEQ)                                   [Opus gate]
S14 reconcile + status + exit (SEQ, last)                             [Opus gate]
```

Five steps carry an explicit Opus gate before their checkpoint commit — S2,
S4, S5, S6, S8, S9 (six, not five, once S9's decommission gate is counted) —
and none of them began execution until this document was approved. The
highest-named risk in the plan's own "Biggest risks" section is the
choreographed decline-compensation silently failing or diverging (H3); the
mitigation named there — baseline Scenario 3 green against the monolith
first, idempotent reactions, a forced-decline proof at cutover, red-then-green
in CI — is exactly the sequence Verify below shows actually happening.

## Generate: the two-phase service, the event contract, and the monolith's first consumer

Generate is where the plan became code, run across S3 through S7.

**S3** defined the choreography's vocabulary first, before either side of it
existed: `PAYMENT_DECLINED` added to `common/Topics.java` (`PAYMENT_CAPTURED`
was already reserved) and matching `PaymentCaptured`/`PaymentDeclined` JSON
payload records authored on both the producer and consumer sides
(commit `cfbb83e`, `feat(r06): payment event contract — PAYMENT_DECLINED
topic + PaymentCaptured/Declined payloads`).

**S4 (Phase A)** scaffolded `examples/05-payment-service` on its own schema
from day one — unlike Review, which stayed in the monolith's shared schema
through Phase A — lifting the read surface via Quarkiverse Spring-compat and
decomposing the `Payment` entity's FK to a plain `orderId` value (commit
`0c99f2d`, `feat(r06): scaffold payment service — Phase A lift, own schema
(:8085), orderId FK-decomposed`).

**S5 (Phase B)** is this extraction's hardest Generate pass — not a pure
refactor like Review's, because there was no Spring original for the
choreography core to lift from:

```
Captured — git log, commit c782f65 (S5, Phase B + choreography core)

refactor(r06): payment Phase B idiomatic + choreography consumer

Phase B removes the Quarkiverse spring-compat lift and makes payment-service
idiomatic Quarkus: JAX-RS PaymentResource (+ @ServerExceptionMapper), Panache
PaymentRepository, plain @ApplicationScoped service. Grep-proven: no
quarkus-spring-* deps, no org.springframework imports.

Adds the choreography core (DRQ-047/051): @Incoming("order-placed")
OrderPlacedConsumer -> charge -> persist Payment + write PaymentOutboxEvent in
one @Transactional method; PaymentOutboxRelay (@Scheduled) publishes to
payment.captured / payment.declined via Emitter, ack-before-stamp at-least-once.
Idempotent by orderId (check-then-act + uq_payments_order_id). charge() now
returns a DECLINED Payment instead of throwing (choreography needs the row).
Own outbox schema via V3__payment_outbox.sql.

Forward-compat gap flagged in MIGRATION.md: monolith's order.placed carries no
payment method yet (S6's job); consumer falls back to CARD-UNSPECIFIED.

18 tests green; end-to-end verified against live podman Kafka+Postgres
(captured + declined paths, redelivery idempotency).
```

That "forward-compat gap" line is not a footnote — it is S5 naming, in its own
commit message, a limitation it is leaving for S6 to close rather
than quietly working around. `MIGRATION.md` records the measured cost of that
real capability (a live Kafka consumer, two producers, a scheduled poller),
not an idiomatic rewrite of the same surface:

```
Captured — examples/05-payment-service/MIGRATION.md, before/after metrics

| Build                                                          | Startup | RSS     | Features |
|------------------------------------------------------------------|-------:|-------:|---------:|
| Phase A — JVM, Spring-compat                                      | 1.494s | ~303MB |       15 |
| Phase B — JVM, idiomatic + Kafka consumer/producer + outbox relay | 1.833s | ~338MB |       16 |
```

Startup is ~23% slower and RSS ~12% higher Phase A to Phase B — the opposite
direction from Review's pure-refactor wash — because Phase B is carrying a
live Kafka consumer, two producers, and a scheduled relay, not just a
vocabulary change. `MIGRATION.md` calls this "the cost of the choreography
actually running, not a regression to be optimized away," and separately
documents exactly how the `CARD-UNSPECIFIED` forward-compat gap was verified
live: on first boot against the real stack, the new consumer group replayed
the repository's entire pre-existing `order.placed` history (~160 events,
none carrying a payment method) and captured every one of them with the
documented placeholder, zero errors — the gap behaving exactly as flagged,
not silently.

**S6** gave the monolith its first Kafka *consumer* (H4) and re-plumbed the
ch.19 compensating `Release` from a catch block to an event reaction
(DRQ-049) — covered in full under Verify below, since its checkpoint commit
*is* an Opus sign-off.

**S7** wired the strangler proxy's payment flag and route, and found a result
the plan had not assumed: the payment service's `PaymentDto` shape already
matched the monolith's read contract byte-for-byte, so no message-translator
was needed at this seam.

```
Captured — git log, commit dbc4748 (S7)

feat(strangler): payment flag + /api/payments route (transparent reverse
proxy; identical PaymentDto contract, no translator per ACL-honesty
precedent)
```

The plan's own prose had anticipated a `PaymentAclRoute` translator (by
analogy with Review/Inventory's seams); S7 checked that assumption against
the real shapes on both sides and recorded, in the commit itself, that a
translator would have been dead code — a content-based route is the full ACL
this particular seam needs, same discipline as Review's `/reviews`-prefix
lesson from ch.07 (match the full path, don't assume a translation step that
isn't required).

## Verify: two judgment gates, a found-and-fixed suite gap, and the crux proven with real numbers

Verify is where this extraction's hardest claim — that a decline converges to
net-zero stock across two asynchronous hops with no request-thread catch —
gets checked against running code rather than asserted in prose.

**S2's baseline** (commit `12a4acf`) ran the adapted Scenario 1/Scenario 3
bounded-wait assertions against the still-synchronous monolith first,
precisely so the adaptation could be proven non-vacuous before any
choreography existed to hide behind: against a synchronous backend every
bounded-wait poll has to resolve on its first attempt, or the adapted
assertion is already broken before Generate even starts.

**S6's Opus gate** confirmed the hardest consistency property in the whole
extraction — that no order ever confirms without a `payment.captured`, that
both saga reactions are idempotent, and that the compensation introduced here
and the ch.19 catch-block compensation it replaces cannot ever double-fire:

```
Captured — git log, commit ccf08eb (S6, Opus-gated)

New OrderSagaListener (the monolith's first Kafka consumer, H4): on
payment.captured -> confirm + dispatch shipping; on payment.declined ->
PAYMENT_DECLINED + compensating gRPC Release for every reserved sku
(DRQ-049, replacing the ch.19 in-line catch for the decline path). Both
reactions idempotent via a PENDING-status guard; Release fires at most once.
No double-compensation: the in-line catch only fires on reserve failure
(before the order is committed-PENDING), so it and the listener can never
release the same order's stock.

Opus-gated GO: all S6 acceptance criteria met (H1/H3/H4), mvn verify green
(38 tests), live e2e confirmed CONFIRMED-via-captured and PAYMENT_DECLINED +
net-zero-stock-via-choreographed-Release.
```

**S8's equivalence gate** is where H3 — the crux — gets proven with numbers,
not an assertion count. With both flags flipped (`payment.mode=choreographed`,
`strangler.payment.enabled=true`) and the full suite run through the proxy
three times for stability:

```
Captured — examples/05-payment-service/CUTOVER.md, forced-decline stock trace

| Step                                                      | SKU-WIDGET-001 | Order status      |
|-------------------------------------------------------------|-------------:|-------------------|
| Before checkout                                              |           96 | --                |
| Immediately after POST (synchronous gRPC Reserve committed)  |           95 | PENDING (202)     |
| After the bounded-wait observes the decline (Release fired)  |           96 | PAYMENT_DECLINED  |

Net-zero: 96 -> 95 -> 96. Reconfirmed a second time: 88 -> 87 -> 88. Reconfirmed
a third time in the scripted full run: 72 -> 71 -> 72.
```

The bounded-wait polls that produced those numbers were not instant: Scenario
1 retried 6 attempts (~3s) before observing `CONFIRMED`, and Scenario 3
retried 8 attempts (~4s) before observing `PAYMENT_DECLINED` — real
`order.placed` -> capture -> `payment.captured`/`declined` -> saga-reaction
latency, not an in-process hit. The same CUTOVER.md run also closed the loop
on routing, the exact check Review's CUTOVER.md established the need for:
`GET /api/payments?orderId=167` through the proxy returned the payment
service's row byte-for-byte, while the same query against the monolith
directly (`:8080`) returned an empty array — proof traffic was
reaching `examples/05-payment-service`, not falling through.

**The negative check (DRQ-055, H2)** is the sharper of the two checks S8 ran,
because a green suite alone cannot distinguish a working choreography from a
suite that would pass no matter what. The payment service's consumer process
was killed, a normal checkout placed through the proxy, and Scenario 1
re-run:

```
Captured — examples/05-payment-service/CUTOVER.md, negative check, consumer down

newman run mea.postman_collection.json --folder "Scenario 1 — Happy-Path Checkout"

1. AssertionError  Order reaches CONFIRMED within the bounded-wait budget (10 x 500ms)
                   order 177 status after 10 attempt(s): expected 'PENDING' to deeply equal 'CONFIRMED'

RED — newman exit code 1, 1 of 23 assertions failed.
```

With the consumer restarted, the same folder on a fresh checkout went
**GREEN — 20/20 assertions, newman exit code 0** within seconds, Kafka's
at-least-once redelivery draining the backlog exactly as predicted. A second,
unplanned finding surfaced while automating this exact check: two scripted
re-verification attempts reproduced a RED that a manual walkthrough of the
identical sequence did not. Root-caused by reading Postgres and the
payment-service log directly rather than guessing: the demo script's process
stop helper was `SIGKILL`ing the consumer 2 seconds after `SIGTERM`, before
the Kafka client could send its `LeaveGroupRequest` — leaving a stale
consumer-group member that stalled the NEXT instance's partition assignment
past the suite's 5-second bounded-wait budget. The fix lived entirely in
`demos/demo-payment-cutover.sh` (poll for exit before `SIGKILL`, up to 10s) —
no service code changed — and is recorded in `CUTOVER.md` as "a genuinely
useful, general lesson for anyone scripting restarts of a Kafka-consuming
service," not a defect in `OrderPlacedConsumer`, `PaymentOutboxRelay`, or the
choreography itself.

**A gap was also found and recorded rather than silently absorbed**: the
first cutover pass discovered that `tooling/newman/mea.postman_collection.json`
had no "Payment Context Contract" folder as S2's own acceptance criteria
called for, and that an unrelated Notification folder (authored before this
extraction existed) still asserted the retired synchronous `201`/`CONFIRMED`
contract on its own checkout call. Both gaps are visible in every S8 run as
exactly 2 failing assertions, documented in `CUTOVER.md` with the root cause
and the proof that the payment choreography itself was unaffected (the
Notification folder's *own* confirmation check, step 5b, passed every time).
**S2b closed both, after S8 and before S9:**

```
Captured — git log, commit 28e62ab (S2b, suite-gap fix, after S8, before S9)

test(equivalence): close S2 gaps — add Payment Context Contract folder +
adapt Notification checkout to bounded-wait (201|202 tolerant, strict
CONFIRMED); green at synchronous baseline (99 assertions, 0 failed)
```

**S9's decommission gate** is the last Opus sign-off this trace covers, and
the property it checks is the one H3's mitigation chain was always protecting
against — a stock leak or a double compensation once the synchronous path is
gone for good:

```
Captured — git log, commit d053fc2 (S9, Opus-gated)

Compensation correctly split (no stock leak, no double-compensation): the
in-line catch still compensates PRE-HANDOFF reserve/save/outbox failures
(e.g. a multi-line order whose later line is out of stock -- pinned by
placeOrder_reserveFailsOnSecondLine_compensatesFirstLine, which also asserts
no order.placed row was written); POST-HANDOFF payment declines compensate via
OrderSagaListener.onPaymentDeclined -> gRPC Release. The two are disjoint by
construction (catch fires only when order.placed was never committed; the
reaction only for a committed-PENDING order).

Opus-gated GO, zero defects. mvn verify green (monolith + proxy);
SixContextsSmokeTest adapted to 202/PENDING + order.placed outbox row +
/api/payments 404.
```

## Operate: both flags flipped, reversibility proven, then decommissioned

Operate is where the saga stopped being a side-by-side comparison and started
actually answering `/api/payments` traffic. The two flags and their states:

```
Captured — examples/05-payment-service/CUTOVER.md, the two flags

| Flag                          | Location                              | Committed default | Cutover override |
|--------------------------------|----------------------------------------|--------------------|-------------------|
| payment.mode                   | monolith application.yml (PAYMENT_MODE)| synchronous        | choreographed     |
| strangler.payment.enabled      | proxy application.properties           | false              | true              |
```

With both overrides applied at runtime (no committed default changed yet),
the full suite ran green across the seam (summarized under Verify above), and
reversibility was demonstrated before anything was made permanent: both
overrides removed, a direct sanity checkout confirmed `201 Created` /
`CONFIRMED` at POST time with no polling needed, and a full re-run produced
**79/79 assertions green, 0 failed, every bounded-wait poll resolving on its
first attempt (0 retries)** — exactly the synchronous-backend shape DRQ-037/
DRQ-055 predict, and the proof that flipping both flags back is a config
change and a restart, nothing more, right up until decommission.

S9 is where that reversibility window closed. The monolith's
in-process `PaymentController`/`PaymentService`/`Payment`/`PaymentRepository`
and the `payment.mode` flag were removed entirely (`GET :8080/api/payments`
now returns `404`); `strangler.payment.enabled=true` became the **committed**
proxy default, the same closing move Review/Notification/Inventory each made
at their own decommission step; and the ch.19 in-line catch compensation was
narrowed to the one case it can still legitimately fire for — a pre-handoff
reserve or outbox failure, never a post-handoff payment decline, which is now
exclusively the saga reaction's job.

## Reconcile: the ledger, and what is still open

`examples/00-monolith/SMELLS.md`'s smell table carries the closing entry for
this extraction, not a separate changelog:

```
Captured — examples/00-monolith/SMELLS.md, SMELL[ch.22] excerpt

Realized for payment in r06/ch.23 S9 (DRQ-047/DRQ-049) -- SMELL[ch.22]'s
ACID -> ACD story cashed in for this context. OrderService#placeOrder no
longer calls payment at all ... What Postgres used to give "for free" --
roll back the inventory decrement when payment declines, inside one local
transaction -- is now rebuilt EXPLICITLY, asynchronously, cross-process ...
The smell is therefore NOT struck through above: it is cured for payment,
not yet for shipping/order, which is exactly the resume boundary ch.24/ch.26
pick up.
```

Two things remain open at the point this trace was captured, and
are recorded here rather than assumed closed:

- **DRQ-047 through DRQ-055 are written and accepted in `_plans/iterations/payment-plan.md`
  (this trace's Frame section quotes them directly) but have not yet been
  copied into `_plans/decisions.md`'s own accepted-decisions table** — that
  merge, plus the version-matrix update, is payment-plan S14's job
  (Reconcile), which runs after S13 authors the chapter. `decisions.md`'s
  last committed row at the time of this trace is DRQ-046 (Inventory).
- **S10 (the payment equivalence gate in GitHub Actions, red-then-green)** has
  not run yet at the time of this trace — `S12`'s own dependency is only on
  "S9 evidence," not S10, so this is in scope for a later step, not a gap in
  this document.

The resume boundary the plan itself records: this choreographed saga is the
deliberate contrast ch.24 (shipping) builds its **orchestrated** saga against,
via the Camel Saga EIP; the event topology, the payment service's outbox-emit
pattern, and the order context's reaction-consumer-plus-compensation machinery
are the foundations shipping extends next.

---

## The condensed callout (for ch.23 — S13 to embed verbatim or adapt)

> **ADLC in Action** — This extraction ran the identical Frame → Map → Plan →
> Generate → Verify → Operate → Reconcile loop Chapter 19 demonstrated for
> Inventory, at its hardest rung yet. Frame fixed nine decisions up front in
> `decisions.md` (DRQ-047 through DRQ-055 — the synchronous-to-asynchronous
> checkout contract, the choreographed event topology, compensation re-plumbed
> from a catch block to a `payment.declined` reaction, saga state on the order
> aggregate, idempotency by `orderId`, the payment service's two-phase read
> surface with an idiomatic-from-day-one consumer/producer, its own
> transactional outbox, two-flag reversibility, and the bounded-wait
> equivalence discipline extended to the checkout path). Plan laid out
> fourteen steps across two parallel lanes — the event contract and the new
> service's scaffold — with six Opus validation gates named before a line of
> consumer or outbox code existed, most pointedly at the cutover step where a
> forced decline had to converge to net-zero stock across two asynchronous
> hops with no request-thread catch to lean on. Generate produced the service,
> the event contract, the monolith's first Kafka consumer, and a proxy route
> that turned out not to need the translator the plan had assumed, under
> quarkus-agent and lgtm-quarkus tooling, following `migrate-spring-to-quarkus`
> for the lifted read surface. Verify is this chapter's negative check and its
> real stock numbers — 96→95→96, 88→87→88, 72→71→72 — plus a suite gap found
> and closed before decommission rather than left standing. Operate is the
> two-flag cutover `CUTOVER.md` records end to end, reversibility proven at
> 79/79 with zero retries, and the committed-default flip at decommission.
> Reconcile is `SMELLS.md` marking Smell[ch.22] cured for payment — not yet
> for shipping or order — with the orchestrated-saga contrast in Chapter 24 as
> the named resume boundary.

---

*Verification status: every figure quoted above is real, dated evidence
already committed to this repository — `examples/05-payment-service/CUTOVER.md`
(the cutover, reversibility, and negative-check timeline, including the
scripted-restart bug found and fixed in `demos/demo-payment-cutover.sh`),
`examples/05-payment-service/MIGRATION.md` (the Phase A/B metrics and the
`CARD-UNSPECIFIED` forward-compat gap), `examples/00-monolith/SMELLS.md`
(the SMELL[ch.22] entry), and the commits cited by hash above
(`cfbb83e`, `0c99f2d`, `c782f65`, `ccf08eb`, `dbc4748`, `12a4acf`, `38cbdf0`,
`28e62ab`, `d053fc2`). What remains open at the time of writing: DRQ-047
through DRQ-055 are accepted in `_plans/iterations/payment-plan.md` but not
yet copied into `_plans/decisions.md`'s accepted table (payment-plan S14's
job), and the payment equivalence gate has not yet landed in GitHub Actions
(S10, out of this trace's S9-evidence scope). Both are tracked, not assumed,
and this document should be re-checked once S14 closes them.*
