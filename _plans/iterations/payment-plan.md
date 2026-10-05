---
title: "Payment Extraction Plan — ch.23 (the fourth strangler extraction, CHOREOGRAPHED SAGA)"
description: "Concrete, ordered, executable step plan for the Payment service extraction: turn the synchronous in-process payment capture inside checkout into an event-driven CHOREOGRAPHED saga (order.placed → payment.captured | payment.declined → order reacts), realizing the ACID→ACD story (SMELL[ch.22]) the book has been building toward. The hard part is that a choreographed saga changes the externally-observable checkout contract (checkout can no longer return 201-CONFIRMED / 402-DECLINED synchronously, because payment now happens AFTER the request returns), and the behavior-equivalence suite's Scenario 1 (CONFIRMED) and Scenario 3 (payment-declined ⇒ inventory net-zero) must be adapted to the eventually-consistent reality WITHOUT weakening them, via the bounded-wait + negative-check discipline (DRQ-037). The ch.19 synchronous compensating Release (DRQ-042) is re-plumbed to fire from the choreography (a payment.declined reaction), not an in-line catch. Mirrors the notification (ch.17) and inventory (ch.19) extraction templates."
status: "execution plan — planning only; nothing built, scaffolded, or pushed until the user approves"
iteration: r06
chapter: 23
depends_on:
  - _plans/build-plan.md            # §E row 4 (payment = choreographed saga, order.placed→payment.captured), §G (equivalence gate; saga happy+failure tests), two-phase DRQ-029, non-trivial example DRQ-032
  - _plans/decisions.md             # DRQ-034 (outbox), DRQ-037 (bounded-wait + negative-check), DRQ-042 (inventory Reserve/Release compensation — re-plumbed here), DRQ-035 (idiomatic consumer-from-start)
  - _plans/iterations/inventory-plan.md       # the proven extraction template this mirrors (and whose DRQ-042 catch-compensation this converts to choreography)
  - _plans/iterations/notification-plan.md    # the event-driven / outbox / bounded-wait template
  - examples/00-monolith real code            # order/OrderService#placeOrder (synchronous charge), payment/*, common/exception/PaymentDeclinedException, common/outbox/*, common/Topics (PAYMENT_CAPTURED reserved)
references:
  - "~/Dev/datamesh-reference-arch-quarkus/examples/payment-service (DRQ-032: idiomatic Quarkus choreographed-saga — SmallRye Reactive Messaging consumer/producer, Panache, payment outbox)"
  - "~/Dev/datamesh-reference-arch-quarkus/_docs/13-orchestration-styles.md (choreography vs orchestration framing; ch.24 shipping uses the orchestrated Saga EIP as the contrast)"
---

# ch.23 — Payment Service Extraction (execution plan — CHOREOGRAPHED SAGA)

> **PLANNING ONLY.** This file is the Opus "Plan" phase (ADLC §F.1) and must be
> **user-approved before any code is written**. Nothing is built, scaffolded, or
> pushed until approval.
>
> **Branch for all work:** `r06-payment-extraction` (off the current default branch).
> **Total steps:** 14 (S1 … S14).
> **Relay tiering (DRQ-004):** every step is *executed* by **Sonnet**; steps marked
> **[Opus gate]** additionally require an **Opus validation** pass before their
> checkpoint commit. Steps marked **[Opus gate — equivalence gate]** are the ADLC
> Verify human sign-off points (§F.1).

## Why this extraction is harder than inventory (the framing)
Inventory (ch.19) was deliberately engineered to **preserve the synchronous checkout
contract**: Reserve stayed a synchronous gRPC call *inside* `placeOrder`, so checkout
still returned `201 CONFIRMED` (or `402` on decline) synchronously, and the equivalence
suite's Scenario 1–3 were left **byte-for-byte unedited** (DRQ-046). Payment is the
opposite. Today `order.OrderService#placeOrder` calls `paymentService.charge(...)`
**synchronously, in-process, inside the checkout `@Transactional`** (lines 162–168):
a decline throws `PaymentDeclinedException` → the controller maps it to **`402`** and the
surrounding `try/catch` issues the compensating inventory `Release` (DRQ-042) **in-line**.
Converting this to a **choreographed saga** (build-plan §E row 4) is where the ACID→ACD
story (SMELL[ch.22]) finally lands, and it is harder for three reasons:

1. **It changes the externally-observable contract.** Once payment is consumed from an
   event *after* the HTTP response returns, checkout **cannot** report `CONFIRMED`/`402`
   synchronously. The POST must return a *pending* order and the real outcome
   (`CONFIRMED` | `PAYMENT_DECLINED`) arrives **eventually** over the choreography. This is
   the first extraction that moves a **user-visible, critical-path, failure-bearing**
   outcome off the synchronous request — the thing inventory was careful *not* to do.
2. **The equivalence suite's two load-bearing payment scenarios must be re-expressed
   without being weakened.** Scenario 1 (expects `CONFIRMED`) and Scenario 3 (expects a
   decline **and** inventory not left decremented) are the *reason this chapter exists*.
   They must pass against the still-synchronous monolith at baseline **and** against the
   async saga after cutover, using DRQ-037's **bounded-wait + negative-check** discipline —
   asserting the **business outcome** (confirmed/declined, payment captured/not, stock
   net-zero) rather than the now-changed HTTP mechanics.
3. **Compensation moves from a catch block to the choreography.** ch.19's inventory
   `Release` fired from an in-line `catch (RuntimeException)` in `placeOrder` (DRQ-042).
   In the saga, the decline is no longer an exception on the request thread — it is a
   `payment.declined` **event**, and the inventory-release compensation must be triggered
   by **reacting to that event**, not by catching an exception. Scenario 3 (payment-declined
   ⇒ inventory released ⇒ stock **net-zero**) is **THE crux and the biggest risk**: the
   compensation now spans two asynchronous hops and must still converge.

## The sync→async checkout contract decision (DRQ-047, stated plainly)
**DECISION: `POST /api/orders` becomes asynchronous. It returns `202 Accepted` with the
order persisted in status `PENDING` and a `Location` header; the order then reaches a
terminal state (`CONFIRMED` | `PAYMENT_DECLINED`) *eventually*, via the choreography, and
clients (and the equivalence suite) observe that terminal state by polling
`GET /api/orders/{id}`.**

- **Why 202, not "keep 201 with a PENDING status":** payment is no longer on the request's
  synchronous path, so the monolith-era outcomes — `201 CONFIRMED` and the synchronous
  `402 PAYMENT_DECLINED` — are **not knowable at response time**. Returning `201 Created`
  with a body that *looks* confirmed (or might silently become declined) would be a
  dishonest contract that hides the ACID→ACD change the chapter is about. `202 Accepted`
  is the correct HTTP semantic for "checkout accepted; outcome pending," and the order
  resource **is** still created synchronously (`PENDING`, with a `Location`) so it is
  immediately pollable. `201-with-PENDING` was considered and **rejected** for that
  dishonesty; the alternative is noted in-chapter as the "minimal-diff" option teams
  sometimes choose and why we didn't.
- **What stays synchronous:** inventory **Reserve** stays a synchronous gRPC call at
  checkout time (ch.19, unchanged), so **Scenario 2 (out-of-stock) still returns `409`
  synchronously at POST** — the reserve happens *before* the order.placed event is emitted.
  Only the payment outcome goes async. This is the honest split: sync where the caller must
  know immediately (is there stock?), async where the saga owns it (did payment capture?).
- **The synchronous `402` disappears.** Both a future-confirm and a future-decline now
  return the *same* initial `202 + PENDING`; they diverge only in the eventually-consistent
  terminal status. The decline is surfaced as `GET /api/orders/{id}` → `PAYMENT_DECLINED`,
  not an HTTP `402` on the POST. The equivalence suite is adapted accordingly (S2 / DRQ-055).

## What this extraction delivers (from build-plan §E row 4, §G, DRQ-032)
1. A new **Quarkus payment service** at **`examples/05-payment-service`** (:8085) that
   **owns its own schema/database**, exposes the lifted REST **`/api/payments`** read
   surface, and participates in the saga via **SmallRye Reactive Messaging / Kafka**:
   it **consumes `order.placed`**, performs the capture (honoring the same deterministic
   `DECLINE`-in-method demo rule the monolith uses), and **emits `payment.captured` or
   `payment.declined`** reliably via its own **transactional outbox** (mirroring DRQ-034).
   Adapted from datamesh's `payment-service` with attribution (DRQ-032).
2. The **monolith's order context becomes a choreographed-saga participant**: in
   choreographed mode `placeOrder` stops calling `paymentService.charge`, persists the
   order `PENDING`, emits `order.placed` (already via the existing outbox), and returns
   `202`; new **order-saga reaction consumers** react to `payment.captured`
   (→ `order.confirm()` + dispatch shipping) and `payment.declined` (→ mark
   `PAYMENT_DECLINED` **and trigger the compensating inventory `Release` via the
   choreography**, replacing the ch.19 in-line catch).
3. The **Camel strangler proxy** gains `strangler.payment.enabled` routing `/api/payments`
   to the new service, with a `PaymentAclRoute` / message-translator (payment wire
   vocabulary → `PaymentDto`) — the ACL at the seam.
4. The **behavior-equivalence suite is adapted for the async checkout contract and stays
   honest**: Scenario 1 (bounded-wait to `CONFIRMED`), Scenario 3 (bounded-wait to
   `PAYMENT_DECLINED` **and** bounded-wait inventory **net-zero** via choreographed
   Release), Scenario 2 unchanged (synchronous `409`), plus a new **Payment Context
   Contract** folder for `/api/payments`. A **negative check** (stop the payment consumer
   ⇒ the order is stuck `PENDING` forever ⇒ the bounded-wait assertion goes RED) proves the
   choreography is genuinely exercised.
5. Tests at every tier + the equivalence gate extended in CI (Postgres + Kafka + payment
   service + inventory gRPC service + monolith), red-then-green via disabling the
   compensation reaction.

## Decisions seeded by this plan (append to `_plans/decisions.md`, next free IDs after DRQ-046)
- **DRQ-047 — Checkout becomes asynchronous: `POST /api/orders` → `202 Accepted` + `PENDING`,
  terminal state reached via the choreography and observed by polling
  `GET /api/orders/{id}`.** Rationale as stated plainly above; `201-with-PENDING` and
  "keep the synchronous 402" both considered and rejected as dishonest once payment leaves
  the request path. The order resource is still created synchronously with a `Location`.
- **DRQ-048 — Choreographed topology & event contract.** Events and ownership:
  `order.placed` (**produced by** the monolith order context via the existing outbox,
  DRQ-034; **consumed by** the payment service) → payment service captures →
  `payment.captured` | `payment.declined` (**produced by** the payment service via its own
  outbox; **consumed by** the monolith order-saga reaction). Kafka topics: `order.placed`
  (exists), `payment.captured` (topic name already reserved in `common/Topics.java`),
  **`payment.declined`** (new — add to `Topics`). JSON serialization (DRQ-038; Avro/Apicurio
  still deferred to ch.28). No central orchestrator — each service reacts to events
  (choreography); ch.24 shipping provides the **orchestrated** contrast (Camel Saga EIP).
- **DRQ-049 — Compensation via choreography (re-plumbs DRQ-042).** The inventory `Release`
  that ch.19 fired from an in-line `catch` in `placeOrder` is moved to the **order-saga's
  reaction to `payment.declined`**: on consuming `payment.declined`, the order context marks
  the order `PAYMENT_DECLINED` and issues the compensating gRPC `Release` for every sku the
  order reserved. The ch.19 synchronous catch-block compensation is **removed** in
  choreographed mode. (The order context already holds the reserved-lines snapshot per order
  via `OrderItem` sku/qty, so the release set is recoverable from the persisted order.)
- **DRQ-050 — Saga state & terminal states on the order aggregate.** The order's saga state
  is tracked by `OrderStatus`: `PENDING` (created, awaiting payment) →
  `CONFIRMED` (on `payment.captured`) | `PAYMENT_DECLINED` (on `payment.declined`, after
  compensation). `OrderStatus` already has all three values; no schema change beyond
  persisting `PENDING` as the real initial checkout state. The `orderId` is the saga
  correlation key carried on every event.
- **DRQ-051 — Idempotency & at-least-once (reuses DRQ-034/DRQ-037).** Both the outbox relays
  and Kafka are at-least-once, so both consumers must be idempotent: the **payment consumer**
  dedupes by `orderId` (a unique constraint — at most one captured payment per order — so a
  redelivered `order.placed` does not double-charge), and the **order-saga reaction** guards
  the status transition (a redelivered `payment.captured`/`payment.declined` is a no-op once
  the order already left `PENDING`, and the compensating `Release` is only issued on the
  *first* `payment.declined`). Documented limitation (no full saga ledger / idempotency key
  on Release yet — consistent with DRQ-042's honest-limitation stance; full saga infra is
  not in scope).
- **DRQ-052 — Payment service: two-phase read surface; event consumer/producer idiomatic
  from the start (honest reading of DRQ-029/DRQ-035).** `/api/payments`
  (`PaymentController`/`PaymentService.getById,listByOrderId`/`Payment`+repo) follows
  DRQ-029 Phase A (spring-compat lift) → Phase B (idiomatic Quarkus REST + Panache). The
  SmallRye Reactive Messaging consumer/producer and the capture logic have no Spring
  original to lift, so they are authored **idiomatic from day one** (as notification's
  consumer was, DRQ-035).
- **DRQ-053 — The payment service emits via its own transactional outbox (mirrors DRQ-034).**
  To emit `payment.captured`/`payment.declined` atomically with persisting the `Payment`
  row, the payment service writes the outcome **and** an outbox row in one local transaction;
  a relay publishes to Kafka. Same at-least-once guarantee and idempotent-consumer
  requirement (DRQ-051). This keeps the choreography's "no dual-write" discipline consistent
  with ch.17 and avoids the lost-event failure mode.
- **DRQ-054 — Reversibility via two flags.** Call/write side: monolith
  `payment.mode = synchronous|choreographed` (default `synchronous` = today's in-line
  `charge` + `201`/`402`). Read side: proxy `strangler.payment.enabled` (default `false`).
  Cutover flips both together; reversible until decommission (mirrors Review/Notification/
  Inventory). Note: because flipping to `choreographed` changes the POST status
  (`201`→`202`), reversibility is proven by flipping **back** and re-asserting the
  synchronous contract.
- **DRQ-055 — Equivalence under a choreographed saga (extends DRQ-037 to the checkout path).**
  Unlike inventory (DRQ-046, checkout stayed synchronous), the checkout folders **must**
  change because the contract changed. The suite is re-expressed to assert the **business
  outcome via bounded-wait**, tolerant of the POST returning `201` (monolith) *or* `202`
  (saga): Scenario 1 bounded-waits `GET /api/orders/{id}` to `CONFIRMED`; Scenario 3
  bounded-waits to `PAYMENT_DECLINED` **and** bounded-waits inventory back to **net-zero**;
  Scenario 2 stays a synchronous `409` (reserve is still synchronous). Against the
  monolith the polls return immediately (already terminal); against the saga they converge
  after the choreography. A **negative check** (stop the payment consumer ⇒ the order never
  leaves `PENDING`) proves the pipeline — the DRQ-037 discipline, now on the checkout path.

## Standing constraints applied to every step
Scope discipline (nothing here that an r06 ch.23 deliverable doesn't require — no
orchestrated Saga EIP [that is ch.24/shipping], no CQRS/GraphQL [ch.26], no Apicurio/Avro
[ch.28], no full saga ledger/state-machine beyond what Scenario 1/3 require); conceptual
coherence; security-by-design (OWASP/CIS — secrets hygiene, no creds in git, Kafka creds
from env/Bitwarden, secure-by-default Camel dynamic-URI allow-list, no request-derived
targets); **SIMPLE git only — `git -C <dir> …`, never `cd && git`**; **never push beyond
`github.com/patterncatalyst/modernizing-enterprise-applications` without explicit user
permission**; Conventional Commits (`feat`/`fix`/`docs`/`chore`/`refactor`/`ci`/`test`/
`site` + scopes `rNN.x`, `§NN`, service names); **NO attribution trailers**. A subagent does
**not** inherit a loaded skill — each executor prompt must explicitly invoke/read the named
skill.

## Parallelism overview
```
S1 (frame + branch + decisions, SEQUENTIAL, must be first)
S2 equivalence suite: adapt checkout for the ASYNC contract; baseline green vs monolith   [Opus gate]  ← THE crux
 ├─ S3 event contract: payment.captured/declined topics + JSON schemas (shared) ──┐  (parallel after S1)
 └─ S4 payment svc scaffold + Phase A read-surface lift (own schema) ─────────────┘  (lane N, parallel) [Opus gate]
S5 payment svc Phase B idiomatic + Kafka consume(order.placed)+capture+emit via outbox, measured (SEQ after S3+S4) [Opus gate]  ← HARD PART
S6 monolith choreography wiring: payment.mode flag; 202+PENDING; order-saga reactions (captured→confirm+ship; declined→decline+Release) (lane M, SEQ after S5) [Opus gate]  ← HARD PARTS
S7 strangler proxy: payment flag + /api/payments route + PaymentAclRoute translator (after S3+S5, may overlap S6)
S8 CUTOVER: flip both flags; equivalence green across the seam (bounded-wait); reversibility; negative check (SEQ after S6+S7) [Opus gate — equivalence gate]  ← HARD PARTS
S9 DECOMMISSION monolith synchronous payment path + remove DRQ-042 catch compensation (SEQ after S8) [Opus gate]
S10 Code-CI: payment equivalence gate (Kafka + payment svc + inventory svc + monolith) red-then-green (SEQ after S9) [Opus gate]
 ├─ S11 ch.23 diagram(s) ───┐   (PARALLEL after S5)
 └─ S12 ch.23 ADLC trace ───┘   (PARALLEL after S9 evidence exists)
S13 ch.23 authored to the bar (SEQ after S9 + S11 + S12)   [Opus gate — 2k + footer]
S14 reconcile + status + exit (SEQ, last)                  [Opus gate]
```
**Single-writer / serialize (§K):** the monolith reactor `pom.xml` + `OrderService` +
`application.yml` + Flyway migrations (S6, S9 — lane M, one writer, serialized);
`examples/01-strangler-proxy/` `application.properties` + `StranglerProxyRoute.java` + the
new `PaymentAclRoute.java` (S7, S8); `common/Topics.java` + the shared event-contract JSON
(S3 — single contract writer); `tooling/newman/mea.postman_collection.json` (S2 — versioned
with the monolith, R8); `.github/workflows/code-ci.yml` (S10); the three `_plans/*` ledgers
(S1, S14). `compose.yaml`/`.env` already run Kafka (ch.17) — **no new infra container is
needed** (contrast inventory's Debezium/Connect), so S-steps only *consume* the existing
broker.

---

## THE HARD PARTS (called out explicitly, per the brief)

**H1 — The sync→async checkout contract change (S2/S6/S8, DRQ-047).** Checkout stops being
a request that returns the final answer. `placeOrder` must return `202 + PENDING` and the
outcome must arrive over Kafka. Every client assumption that "the POST tells me if the order
is confirmed" breaks. Mitigation: the decision is made explicit and taught (DRQ-047), the
POST still creates a pollable resource with a `Location`, and the equivalence suite is
re-expressed around the *business outcome* via bounded-wait (DRQ-055). The risk is a silent,
un-taught contract break; the mitigation is to make it the chapter's whole point.

**H2 — Keeping the behavior-equivalence suite honest across the sync→async change (S2,
DRQ-055).** This is the subtlest step and why S2 is `[Opus gate]`. The suite must (a) still
baseline-green against the *synchronous* monolith **before** anything is built, and
(b) green against the async saga **after** cutover, **without weakening** the two load-bearing
payment assertions. The honest technique: assert the **terminal order status + side-effects
(payment captured/not, stock net-zero)** via **bounded-wait polling**, tolerant of a `201`
*or* `202` POST, instead of asserting the (now-changed) synchronous HTTP mechanics. The trap
(CUTOVER.md §2 false-positive, which bit Review) is a suite that stays green while the
choreography does nothing — guarded by the **negative check** (stop the payment consumer ⇒
order stuck `PENDING` ⇒ RED). Danger: relaxing "expect 402" into "accept anything" would
make Scenario 3 vacuous; the mitigation is that the *terminal* `PAYMENT_DECLINED` + the
*net-zero stock* are asserted strictly, only the transport is relaxed.

**H3 — Scenario 3 compensation via choreography (S6/S8, DRQ-049 — THE crux & biggest risk).**
The decline is now a `payment.declined` event, and the inventory `Release` must be triggered
by reacting to it, across two async hops (payment emits → order reacts → gRPC Release), with
no request-thread `catch` to lean on. If the reaction is missed, not idempotent, or the
Release fails, stock stays decremented after a decline — Scenario 3 goes RED (good) or drifts
silently (the thing we most fear). Mitigation: S2 baselines Scenario 3 green vs the monolith;
the order-saga reaction is idempotent (DRQ-051) and issues Release only on the first
`payment.declined`; S8 forces a decline and bounded-waits stock back to net-zero; S10 proves
it red-then-green by disabling the reaction.

**H4 — The monolith becomes a Kafka *consumer* (S6).** Until now the monolith only *produced*
(the outbox relay). In choreographed mode the order context must **consume**
`payment.captured`/`payment.declined` and drive the order lifecycle + compensation + shipping
dispatch off them — a genuinely new capability in the "before" artifact, gated behind
`payment.mode` so the synchronous baseline is untouched when the flag is off. Mitigation:
consumers are idempotent and transactional; `payment.mode=synchronous` keeps the entire new
path dormant until cutover.

---

## S1 — Frame, branch, decisions  *(SEQUENTIAL — must be first)*
- **(b) Goal / DoD:** The ch.23 outcome + acceptance criteria are framed; the working branch
  exists; **DRQ-047…055** are appended to `decisions.md` (esp. the sync→async contract
  decision DRQ-047 and the choreographed-compensation decision DRQ-049); a `build-plan.md`
  status row for r06/ch.23 is added as "in progress"; the version matrix gains forward rows
  (payment-service `quarkus-messaging-kafka` version, reuse of the existing Kafka broker tag).
- **(c) Creates/touches:** `_plans/decisions.md` (DRQ-047…055), `_plans/build-plan.md`
  (status row + matrix), checkout branch `r06-payment-extraction`. No code.
- **(d) Skills/MCP:** none (ledger authoring). `git -C <dir> checkout -b`.
- **(e) Deps / parallel:** none; SEQUENTIAL, gates everything.
- **(f) Collision risk:** `_plans/*` single-writer — this and S14 are the only ledger writers.
- **(g) Acceptance:** branch is `r06-payment-extraction`; DRQ-047…055 present; the
  sync→async contract (202+PENDING), the topology/event contract, the choreographed
  compensation, the idempotency, and the reversibility decisions written.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(r06.x): frame ch.23 payment choreographed-saga extraction; seed DRQ-047..055 and working branch`

## S2 — Adapt the equivalence suite for the ASYNC checkout contract (baseline green vs monolith)  *(SEQUENTIAL, after S1)*  **[Opus gate]**  — **HARD PARTS H1/H2 — THE crux**
- **(b) Goal / DoD:** Re-express the checkout folders for the eventually-consistent contract
  **without weakening them** (DRQ-055): **Scenario 1** — POST tolerant of `201|202`, then a
  **bounded-wait poll** of `GET /api/orders/{id}` asserting it reaches **`CONFIRMED`** (plus
  the unchanged total/items and the Scenario-1 stock-decrement check). **Scenario 3** — POST
  tolerant of `402|202`, then a bounded-wait poll asserting the order reaches
  **`PAYMENT_DECLINED`** **and** a bounded-wait poll asserting inventory returns to
  **net-zero** (stock restored by the choreographed Release). **Scenario 2** — **left
  synchronous** (reserve is still synchronous ⇒ `409` at POST). Add a new **"Payment Context
  Contract"** folder asserting `/api/payments?orderId=` and `/api/payments/{id}` shapes.
  Capture everything **green against the still-synchronous monolith** (polls return
  immediately because the order is already terminal) — this is the baseline that proves the
  adapted assertions did not become vacuous.
- **(c) Creates/touches:** `tooling/newman/mea.postman_collection.json` (edit Scenario 1 & 3;
  add Payment Context Contract folder; Scenario 2 untouched),
  `tooling/newman/payment-service.postman_environment.json` (forward-ref env on :8085).
  **Collection versioned with the monolith (R8).**
- **(d) Skills/MCP:** Newman (reuse the notification bounded-wait pattern from Scenario 5 /
  DRQ-037). Runs against the monolith on :8080.
- **(e) Deps / parallel:** after S1; SEQUENTIAL (must baseline before any behavior changes).
- **(f) Collision risk:** low (own `tooling/`), but it is the equivalence contract — ripples
  to every later run; the honesty of the whole extraction rests here.
- **(g) Acceptance:** the adapted collection is **green against the unmodified synchronous
  monolith** (Scenario 1 → CONFIRMED, Scenario 3 → PAYMENT_DECLINED + stock net-zero,
  Scenario 2 → 409). **[Opus gate]:** Opus confirms the adapted assertions still strictly
  assert the terminal status + side-effects (not just "any 2xx"), that the bounded-wait
  tolerance of `201|202` / `402|202` does **not** weaken the decline/net-zero checks, and
  that a vacuous-green is impossible (e.g. a bounded-wait that would pass on a stuck PENDING
  is rejected).
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `test(equivalence): adapt checkout scenarios for async choreographed contract (bounded-wait), add Payment Context Contract; green vs monolith baseline`

## S3 — Event contract: `payment.captured` / `payment.declined` topics + JSON schemas  *(PARALLEL, after S1)*
- **(b) Goal / DoD:** Define the saga's event vocabulary (DRQ-048): add **`PAYMENT_DECLINED`**
  to `common/Topics.java` (`PAYMENT_CAPTURED` already reserved), and author the JSON payload
  record(s) for both outcomes (`orderId`, `paymentId`, `amountCents`, `method`, `status`,
  `occurredAt`) — the shared wire contract the payment service produces and the order-saga
  reaction consumes. JSON per DRQ-038 (Avro/Apicurio still ch.28). Document producer/consumer
  for each topic (order→payment→order).
- **(c) Creates/touches:** `examples/00-monolith/.../common/Topics.java` (add
  `PAYMENT_DECLINED`), a small shared `PaymentOutcomeEvent` record (consumed by the order
  reaction) + its mirror in the payment service, a short `_plans/research/` or in-service
  `EVENTS.md` documenting the contract. **Single writer of the contract.**
- **(d) Skills/MCP:** none new (plain records + topic constants).
- **(e) Deps / parallel:** after S1; **PARALLEL with S4**. Feeds S5, S6, S7.
- **(f) Collision risk:** `Topics.java` is in lane M's file set — this step is its only r06
  writer; coordinate so S6 does not also edit it.
- **(g) Acceptance:** `PAYMENT_DECLINED` topic constant present; both event payloads defined
  with matching fields on producer (payment svc) and consumer (order) sides; producer/consumer
  mapping documented.
- **(h) Tier:** Sonnet. No Opus gate (proven in S5/S6).
- **(i) Checkpoint commit:** `feat(payment): event contract — payment.captured/payment.declined topics + JSON payloads (the choreography vocabulary)`

## S4 — Payment service: scaffold + Phase A read-surface lift (own schema)  *(PARALLEL lane N, after S1)*  **[Opus gate]**
- **(b) Goal / DoD:** A new Quarkus module `examples/05-payment-service` (:8085) that **owns
  its own schema/database** (its own `payments` table + Flyway). The monolith's Spring read
  surface is **lifted via Quarkiverse Spring-compat** (`quarkus-spring-web`/`-di`/
  `-data-jpa`): `PaymentController` → `/api/payments` + `/api/payments/{id}`,
  `PaymentService.getById/listByOrderId`, `Payment` entity + repo. **The `Payment` entity's
  `@ManyToOne Order` FK (SMELL[ch.18]) becomes a plain `orderId` value** (event-carried
  reference, no cross-DB FK — the payment-side FK decomposition, lighter than inventory's
  because it is same-direction). No consumer/producer yet (S5). Owned store starts empty and
  is filled forward by the saga — **no CDC backfill needed** (contrast inventory/DRQ-040),
  documented as a deliberate difference (payment rows are created forward, not migrated).
- **(c) Creates/touches:** `examples/05-payment-service/**` (pom, `application.properties` on
  :8085 with its own datasource + Flyway, controller/service/entity/repo, tests),
  `src/main/docker/*`. Isolated subtree.
- **(d) Skills/MCP:** **quarkus-agent** — `quarkus_skills` against the monolith dir to discover
  + follow **`migrate-spring-to-quarkus`** (do NOT self-plan the migration);
  `quarkus_create`/`quarkus_start`/`quarkus_searchDocs`; **lgtm-quarkus**.
- **(e) Deps / parallel:** scaffold after S1; **PARALLEL with S3**. Isolated dir.
- **(f) Collision risk:** low — isolated under `examples/05-payment-service/`.
- **(g) Acceptance:** service boots on :8085 against its own DB; `/api/payments` read contract
  matches the monolith's `PaymentDto` shape; Spring-compat extensions present (Phase A);
  `Payment` holds `orderId` as a value (no cross-context FK); unit tests green. **[Opus gate]:**
  Opus confirms it owns its schema (no reach into the shared monolith table), the read
  contract matches, and the FK is decomposed to a value reference.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(payment): Phase A — lift /api/payments onto Quarkus via Spring-compat; own schema; orderId value reference (FK decomposed)`

## S5 — Payment service: Phase B idiomatic + Kafka consume/capture/emit via outbox, measured  *(SEQUENTIAL, after S3 + S4)*  **[Opus gate]**  — **HARD PART (the choreographed core)**
- **(b) Goal / DoD:** Refactor the read surface off the compat shim to **idiomatic Quarkus**
  (Quarkus REST + Panache, native CDI) — mirroring prior Phase B — and author the **net-new
  choreography core (idiomatic-from-start, DRQ-052):** a **SmallRye Reactive Messaging
  consumer** of `order.placed` that performs the capture (honoring the deterministic
  `DECLINE`-in-method demo rule), persists the `Payment` row, and — **atomically, via the
  payment service's own transactional outbox (DRQ-053)** — writes a `payment.captured` or
  `payment.declined` outbox row that a relay publishes to Kafka. **Idempotent by `orderId`**
  (unique constraint ⇒ a redelivered `order.placed` does not double-charge, DRQ-051). Capture
  **measured before/after** (startup/RSS/native) as a `MIGRATION.md`. **Adapt from datamesh
  `payment-service` with attribution (DRQ-032).**
- **(c) Creates/touches:** `examples/05-payment-service/**` — pom (remove spring-compat; add
  `quarkus-messaging-kafka`, `quarkus-hibernate-orm-panache`, `quarkus-rest(+jackson)`,
  `quarkus-smallrye-health`), `application.properties` (incoming `order.placed` + outgoing
  channels), the capture consumer, the payment outbox + relay, Panache refactor,
  `MIGRATION.md`, Citrus/`@QuarkusTest` + Dev Services tests (incl. a capture-then-emit and a
  decline-then-emit round-trip, and an idempotent-redelivery test).
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills messaging,kafka,panache`;
  `quarkus_searchDocs`), **lgtm-quarkus**; optionally **lgtm-camel** if a Camel route is used
  for the outbox relay; Dev Services for Kafka + PG in tests.
- **(e) Deps / parallel:** after S3 (event contract) + S4 (service). SEQUENTIAL.
- **(f) Collision risk:** low (same isolated module).
- **(g) Acceptance:** consuming an `order.placed` for a normal method emits exactly one
  `payment.captured` and persists a CAPTURED `Payment`; a `DECLINE` method emits exactly one
  `payment.declined` and persists (or records) a DECLINED outcome **without** capturing funds;
  a redelivered `order.placed` does **not** double-charge (idempotent); `/api/payments` read
  contract unchanged; before/after metrics captured (real runs). **[Opus gate]:** Opus
  verifies the outbox makes emit atomic with the DB write (no lost/dual-write event),
  idempotency holds under redelivery, and the decline path emits rather than throwing into
  the void.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(payment): Phase B — idiomatic Quarkus + SmallRye consume(order.placed)/capture/emit(captured|declined) via transactional outbox (idempotent, measured)`

## S6 — Monolith choreography wiring: payment.mode flag, 202+PENDING, order-saga reactions  *(lane M, SEQUENTIAL after S5)*  **[Opus gate]**  — **HARD PARTS H1/H3/H4**
- **(b) Goal / DoD:** In `examples/00-monolith`, add `payment.mode = synchronous|choreographed`
  (default `synchronous` = today's in-line `charge` + `201`/`402`, unchanged baseline). In
  **choreographed** mode: `placeOrder` **stops calling `paymentService.charge`**, persists the
  order `PENDING`, emits `order.placed` (already via the existing outbox), and the controller
  returns **`202 Accepted` + `Location`** (DRQ-047). Add **order-saga reaction consumers**
  (the monolith's first Kafka *consumer*, H4): on **`payment.captured`** →
  `order.confirm()` (→ `CONFIRMED`) + **dispatch shipping** (shipping stays in the monolith
  until ch.24, but is now triggered by the event, not the request thread); on
  **`payment.declined`** → mark `PAYMENT_DECLINED` **and issue the compensating gRPC
  `Release`** for every sku the order reserved (DRQ-049 — replacing the ch.19 in-line catch).
  Both reactions **idempotent** (status-transition guard; Release only on first decline,
  DRQ-051). The ch.19 synchronous catch-block compensation is **bypassed** in choreographed
  mode (removed entirely at S9).
- **(c) Creates/touches:** `examples/00-monolith/pom.xml` (+ spring-kafka consumer config),
  `order/OrderService.java` (choreographed branch: no charge, persist PENDING; extract the
  reaction logic), a new `order/OrderSagaListener.java` (Kafka consumers for
  `payment.captured`/`payment.declined`), `order/OrderController.java` (return `202` in
  choreographed mode), `application.yml` (`payment.mode`, consumer group/topics). Does **not**
  touch the payment service or proxy dirs. Lane M single writer.
- **(d) Skills/MCP:** Spring authored manually (per §K); **quarkus-agent** `quarkus_searchDocs`
  only if needed; verify against the live S5 service + Kafka.
- **(e) Deps / parallel:** after S5 (needs the payment service emitting events). SEQUENTIAL in
  lane M.
- **(f) Collision risk:** **HIGH** — lane M sole writer of the monolith reactor root +
  `OrderService` + `OrderController` + `application.yml` in r06; serialized with S9.
- **(g) Acceptance:** with `payment.mode=synchronous` the full suite stays green (zero
  regression — still `201`/`402`). With `payment.mode=choreographed`: POST → `202` + `PENDING`;
  on `payment.captured` the order becomes `CONFIRMED` + shipping dispatched; on
  `payment.declined` the order becomes `PAYMENT_DECLINED` **and** the compensating Release
  restores stock (net-zero); redelivered events are no-ops. **[Opus gate]:** Opus confirms
  the compensation fires on `payment.declined` for **every** reserved sku, the reactions are
  idempotent, no order is confirmed without a `payment.captured`, and a decline leaves stock
  net-zero via the choreography (H3).
- **(h) Tier:** Sonnet execute; **Opus validate** (checkout-path change + saga consistency).
- **(i) Checkpoint commit:** `feat(monolith): choreographed checkout — 202+PENDING, order-saga reactions (captured->confirm+ship, declined->decline+compensating Release); flag-gated, default synchronous`

## S7 — Strangler proxy: payment flag + /api/payments route + PaymentAclRoute  *(SEQUENTIAL, after S3 + S5; may overlap S6)*
- **(b) Goal / DoD:** Add to `StranglerProxyRoute` a `strangler.payment.enabled` flag
  (default **false** → monolith) + `strangler.payment.base-url` (:8085), content-based routing
  on the **full `/api/payments`** path prefix (heeding the Review `/reviews`-prefix bug), and a
  real `PaymentAclRoute` / message-translator (payment service wire vocabulary → `PaymentDto`)
  — the ACL at the read seam. Secure-by-default: only the fixed operator-configured target.
- **(c) Creates/touches:** `examples/01-strangler-proxy/.../StranglerProxyRoute.java` (append
  payment flag/route), a `PaymentAclRoute.java` + translator, `application.properties` (flag +
  base-url). Single writer of the proxy.
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (`camel_route_context`,
  `camel_validate_route`, `camel_render_route_diagram`).
- **(e) Deps / parallel:** after S3 (contract) + S5 (service). May overlap S6.
- **(f) Collision risk:** med — single writer of proxy route + properties (shares the file with
  Review/Notification/Inventory flags; append, don't rewrite).
- **(g) Acceptance:** with the flag **false**, `/api/payments` still reaches the monolith and
  the full suite is green through :8888; `camel_validate_route` clean; a Citrus route test
  proves the full `/api/payments` prefix match and that the translator produces `PaymentDto`.
- **(h) Tier:** Sonnet. No Opus gate (proof is S8).
- **(i) Checkpoint commit:** `feat(strangler): payment flag + /api/payments route; wire PaymentAclRoute (ACL translator)`

## S8 — CUTOVER: flip both flags; equivalence green across the seam; reversibility; negative check  *(SEQUENTIAL, after S6 + S7)*  **[Opus gate — equivalence gate]**  — **HARD PARTS H1/H3**
- **(b) Goal / DoD:** Flip **both** flags together (DRQ-054): monolith
  `payment.mode=choreographed` (checkout returns `202`, payment captured over the saga) +
  proxy `strangler.payment.enabled=true` (reads served by the payment service). Run the **full
  equivalence suite through the proxy** (:8888): **Scenario 1 bounded-waits to `CONFIRMED`**,
  **Scenario 3 bounded-waits to `PAYMENT_DECLINED` + inventory net-zero via the choreographed
  Release** (the crux, H3), **Scenario 2 stays synchronous `409`**, plus the Payment Context
  Contract folder. Demonstrate **reversibility** (flip both back → synchronous in-line charge,
  `201`/`402` restored, still green). Run the **negative check**: stop the payment consumer ⇒
  the order is stuck `PENDING` ⇒ Scenario 1's bounded-wait goes **RED** (proves the
  choreography is genuinely exercised, DRQ-055); and force a decline to confirm the Release
  restored stock.
- **(c) Creates/touches:** flag config only (`payment.mode`, `strangler.payment.enabled`); a
  `demos/demo-payment-cutover.sh`; evidence appended to a new
  `examples/05-payment-service/CUTOVER.md`.
- **(d) Skills/MCP:** **camel-mcp** (`camel_runtime_*` to confirm routing), Newman re-run,
  Kafka consumer-lag/topic inspection to show the saga flow.
- **(e) Deps / parallel:** after S6 + S7. SEQUENTIAL.
- **(f) Collision risk:** touches both flag locations; no code rewrite.
- **(g) Acceptance:** suite green through the proxy in the cutover state (Scenario 1 →
  CONFIRMED via saga, Scenario 3 → PAYMENT_DECLINED + stock net-zero via choreographed
  Release, Scenario 2 → 409, Payment read folder); reversibility demonstrated; **negative
  check RED** when the payment consumer is down. **[Opus gate — equivalence gate]:** human/Opus
  signs off that the eventually-consistent scenarios are genuinely satisfied across the seam —
  especially that a decline ends with stock net-zero via the choreography, not a residual
  decrement.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(payment): flag-gated cutover to choreographed saga; equivalence green (bounded-wait CONFIRMED/DECLINED + net-zero), reversibility + negative check`

## S9 — DECOMMISSION the monolith synchronous payment path  *(SEQUENTIAL, after S8)*  **[Opus gate]**
- **(b) Goal / DoD:** Make the payment service the **sole owner** of payment: remove the
  monolith's `PaymentController`/`PaymentService`/`Payment`/`PaymentRepository` and the
  `payment.mode` flag (choreographed becomes the only path); the monolith no longer serves
  `/api/payments` and no longer charges in-process. **Remove the ch.19 in-line catch
  compensation** from `placeOrder` now that `payment.declined` drives the Release
  (DRQ-049) — the `try/catch` compensation is fully replaced by the choreographed reaction.
  Keep `PaymentDeclinedException` only if still referenced by the synchronous baseline
  tests being retired; otherwise remove it. Update `SMELLS.md` (**SMELL[ch.22] ACID→ACD
  realized for payment** — the one in-process transaction no longer spans the payment
  context) and `SixContextsSmokeTest`. `strangler.payment.enabled=true` becomes the committed
  default.
- **(c) Creates/touches:** `examples/00-monolith/**` (remove payment module + flag + in-line
  catch compensation; a Flyway migration if the monolith `payments` table is retired —
  recommend keep-and-stop-writing as write-only history, same treatment as
  reviews/notifications/inventory_items), `SMELLS.md`, `SixContextsSmokeTest.java`,
  `examples/01-strangler-proxy/application.properties` (committed default true). Lane M + proxy.
- **(d) Skills/MCP:** none new; Newman re-run; `mvn -f examples/00-monolith clean verify`.
- **(e) Deps / parallel:** after S8. SEQUENTIAL.
- **(f) Collision risk:** monolith (lane M) + proxy — single writer each. (A decommission `rm`
  may be blocked by the permission classifier — see r02-status; expect a human-action step if
  a delete is refused.)
- **(g) Acceptance:** `GET :8080/api/payments` → 404; checkout still captures via the saga
  (payment service owns the charge); the in-line catch compensation is gone (only the
  `payment.declined` reaction compensates); monolith `clean verify` green; full suite green
  through the proxy; `SMELLS.md` SMELL[ch.22] marked realized for payment with evidence.
  **[Opus gate]:** decommission reviewed; no orphaned in-process charge path; no double
  compensation (catch + reaction).
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(payment): decommission monolith payment module + in-line catch compensation; payment service is sole owner; ACID->ACD realized (SMELL[ch.22])`

## S10 — Code-CI: payment equivalence gate (Kafka + payment svc + inventory svc + monolith) red-then-green  *(SEQUENTIAL, after S9)*  **[Opus gate]**
- **(b) Goal / DoD:** Add a `payment-equivalence-gate` job to `.github/workflows/code-ci.yml`
  (sibling to review/notification/inventory gates) that brings up Postgres + Kafka + the
  **payment service** + the **inventory gRPC service** (checkout still reserves over gRPC) +
  the monolith, then runs the **full** behavior-equivalence suite through the proxy — including
  the async Scenario 1/3 with bounded-wait — and **fails on non-zero Newman exit**. Prove the
  gate truly gates with a **red-then-green**: disable the `payment.declined`→Release reaction
  (so a decline leaves stock decremented) ⇒ Scenario 3 net-zero goes RED; restore ⇒ green.
  Also record the consumer-down negative check (order stuck PENDING ⇒ Scenario 1 RED).
- **(c) Creates/touches:** `.github/workflows/code-ci.yml` (extend; do not fork a workflow).
  Isolated.
- **(d) Skills/MCP:** **lgtm-github** (GitHub Actions conventions). Reuses S2 suite +
  S5/S6 artifacts.
- **(e) Deps / parallel:** after S9. SEQUENTIAL.
- **(f) Collision risk:** low — single workflow file.
- **(g) Acceptance:** workflow green on push/PR with the choreographed saga exercised
  end-to-end; the deliberate break (no compensation reaction) makes Scenario 3 RED (recorded),
  then green. **[Opus gate]:** Opus confirms the job genuinely exercises the async
  confirm + decline-compensation across the seam (not a green-only race — bounded-waits with
  real timeouts), and that Kafka/the consumer is healthy before the suite runs.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `ci(r06.x): payment equivalence gate — choreographed saga, confirm + decline-compensation (red-then-green)`

## S11 — ch.23 diagram(s)  *(PARALLEL, after S5)*
- **(b) Goal / DoD:** Paired **SVG + `.excalidraw`** figures: (1) the **choreographed-saga
  sequence** (POST→202/PENDING; order.placed → payment captures → payment.captured|declined →
  order reaction → CONFIRMED+ship | PAYMENT_DECLINED+Release); (2) the **sync→async checkout
  contract before/after** (201-CONFIRMED/402 vs 202-PENDING-then-poll); (3) the
  **compensation-via-choreography** flow (decline event triggers the inventory Release that
  ch.19 did in a catch). House style, catalogued.
- **(c) Creates/touches:** `assets/diagrams/23-*.svg` + `.excalidraw`; append rows to
  `assets/diagrams/README.md` (serialize this catalogue — one writer).
- **(d) Skills/MCP:** **lgtm-diagram-generator**.
- **(e) Deps / parallel:** after S5 (saga shape known); PARALLEL with S12.
- **(f) Collision risk:** low per-SVG; serialize `assets/diagrams/README.md`.
- **(g) Acceptance:** SVGs render; `.excalidraw` sources present; catalogue updated.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(§23): choreographed-saga sequence, sync->async contract, and compensation-via-choreography diagrams (SVG + excalidraw)`

## S12 — ch.23 ADLC trace capture  *(PARALLEL, after S9 evidence exists)*
- **(b) Goal / DoD:** Capture the **"ADLC in Action"** trace for ch.23 as pre-captured,
  narrated tool output (DRQ-025): the `migrate-spring-to-quarkus` run (S4), the SmallRye
  consumer/outbox validation (S5), the camel-mcp ACL route validation (S7), the equivalence-
  gate + negative-check + decline-compensation proof (S8), both human gates, and the
  DRQ-047…055 entries. The `_plans/` ledger is Exhibit A.
- **(c) Creates/touches:** captured transcripts under `_docs/_adlc-traces/` (or the ch.23
  companion), reusing the established callout template.
- **(d) Skills/MCP:** **lgtm-tutorial** (callout format); evidence sourced from S4–S10 runs
  (captured, not re-run live).
- **(e) Deps / parallel:** needs S9 evidence; PARALLEL with S11.
- **(f) Collision risk:** low; coordinate callout reuse with S13.
- **(g) Acceptance:** a complete Frame→Map→Plan→Generate→Verify→Operate→Reconcile trace exists
  as checked-in narrated output; both gates + equivalence + negative-check + the
  decline-compensation proof visible.
- **(h) Tier:** Sonnet. Opus gate folded into S13.
- **(i) Checkpoint commit:** `docs(§23): ADLC-in-Action trace for the payment choreographed-saga extraction`

## S13 — ch.23 authored to the full bar  *(SEQUENTIAL, after S9 + S11 + S12)*  **[Opus gate — 2k + footer]**
- **(b) Goal / DoD:** Chapter 23 ("Distributed Transactions, the Saga & Extraction 4 —
  Payment (choreographed)", per build-plan §B.2) authored to the full bar: **≥2000 words excl. code/diagrams**, progressive, referencing the runnable
  `examples/05-payment-service/` (part 7, "Coordinating Across Services") + the monolith
  order-saga reactions + the wired ACL route;
  the S11 diagrams embedded; a real **"ADLC in Action" callout** (S12); a **verification-status
  footer** naming the tests/demos run (equivalence suite incl. async Scenario 1/3 across the
  seam, the capture/decline/idempotent round-trip tests, the negative check, Citrus route
  test, Dev Services, native). Must teach: the **ACID→ACD** realization (SMELL[ch.22] paid
  off), **choreography vs orchestration** (forward-ref ch.24 shipping's Saga EIP), the
  **sync→async checkout contract change** and why `202` (DRQ-047), the **event topology**
  (DRQ-048), **compensation-via-choreography** replacing the ch.19 catch (DRQ-049, back-ref
  DRQ-042), **idempotency/at-least-once** (DRQ-051), and **how the equivalence suite stayed
  honest across the contract change** (DRQ-055). Only *adds* a `_docs` file (no
  `_config.yml`/`_parts/` edits).
- **(c) Creates/touches:** `_docs/23-choreographed-saga-payment.md` (front matter: `title`,
  `order: 23`, `part: "Coordinating Across Services"` (matches `_parts/07-coordinating-across-services.md`),
  `description`, `duration`).
- **(d) Skills/MCP:** **lgtm-tutorial** (authoring + static validation) + **lgtm-jekyll**
  (build/word-count).
- **(e) Deps / parallel:** after S9 (behavior final), S11 (diagrams), S12 (callout). SEQUENTIAL.
- **(f) Collision risk:** low (single new `_docs` file).
- **(g) Acceptance:** lgtm-jekyll/lgtm-tutorial validation green: **word count ≥2000**,
  example-dir present, verification footer present, links/diagrams resolve. **[Opus gate]:**
  Opus confirms the 2k-with-running-code bar, that the sync→async + choreography + compensation
  teaching is honest, and the callout is authentic.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `docs(§23): author Choreographed Saga & Extraction 4 — Payment to the full bar`

## S14 — Reconcile, status, exit validation  *(SEQUENTIAL, last)*  **[Opus gate]**
- **(b) Goal / DoD:** Append r06/ch.23 outcomes to the ledger: `reconciliation.md` updated
  (artifact→source drift incl. the datamesh payment-service adaptation; zero unexplained
  drift, R4); `build-plan.md` status row for ch.23 marked DONE; `decisions.md` version matrix
  updated (payment-service `quarkus-messaging-kafka` version, reused Kafka broker tag). The
  ch.23 EXIT CHECKLIST (below) verified; clean resume boundary for the **shipping orchestrated
  saga (ch.24)** recorded — this choreography is the contrast ch.24 builds the orchestrated
  Saga EIP against.
- **(c) Creates/touches:** `_plans/reconciliation.md`, `_plans/build-plan.md` (status),
  `_plans/decisions.md` (matrix). Serialize all three.
- **(d) Skills/MCP:** none new; lgtm-jekyll full-site validation; re-run the equivalence suite
  once.
- **(e) Deps / parallel:** after all. SEQUENTIAL.
- **(f) Collision risk:** the three `_plans/*` are single-writer — only S1 and S14 write them
  in r06.
- **(g) Acceptance:** exit checklist all-green; matrix updated; status current. **[Opus gate]:**
  Opus signs off the whole extraction.
- **(i) Checkpoint commit:** `docs(r06.x): reconcile ch.23, update version matrix, mark payment choreographed-saga extraction DONE`

---

## ch.23 EXIT CHECKLIST (equivalence green across the async seam + ACID→ACD realized for payment)
- [ ] **Sync→async contract decided & applied (DRQ-047):** `POST /api/orders` returns
      `202 + PENDING` with a `Location`; terminal `CONFIRMED`/`PAYMENT_DECLINED` reached via
      the choreography and observed by polling `GET /api/orders/{id}`. The synchronous `402`
      is gone, replaced by an eventual `PAYMENT_DECLINED`.
- [ ] **Equivalence green across the seam — honestly:** the adapted collection passes through
      the proxy — **Scenario 1 (bounded-wait → CONFIRMED), Scenario 3 (bounded-wait →
      PAYMENT_DECLINED + inventory net-zero via choreographed Release), Scenario 2
      (synchronous 409 unchanged)** — plus the Payment Context Contract folder; the adapted
      assertions still strictly assert the terminal status + side-effects (not vacuous).
- [ ] **Scenario 3 compensation via choreography proven (DRQ-049):** a forced decline ends
      with stock **net-zero**, driven by the `payment.declined` reaction's Release — **not** an
      in-line catch; the ch.19 catch compensation is removed.
- [ ] **Payment owns its data & the capture over Kafka:** the Quarkus service owns its schema,
      consumes `order.placed`, captures, and emits `payment.captured`/`payment.declined` via
      its own transactional outbox; it is the sole owner after decommission (`GET :8080/api/payments`
      → 404).
- [ ] **ACID→ACD realized for payment (SMELL[ch.22]):** the one in-process checkout
      transaction no longer spans the payment context; the saga + compensation rebuilt the
      cross-context consistency explicitly. SMELLS.md updated with evidence.
- [ ] **Idempotency / at-least-once handled (DRQ-051):** redelivered `order.placed` does not
      double-charge; redelivered `payment.captured`/`payment.declined` are no-ops; Release
      only on the first decline.
- [ ] **Two-phase honored (DRQ-052):** read surface Phase A (spring-compat) → Phase B
      (idiomatic), measured; consumer/producer idiomatic-from-start.
- [ ] **Reversibility shown (DRQ-054)** before decommission (both flags off → synchronous
      in-line charge, `201`/`402` restored, green).
- [ ] **Negative check proven (DRQ-055):** payment consumer down ⇒ order stuck `PENDING` ⇒
      Scenario 1 bounded-wait RED; decline-with-no-reaction ⇒ Scenario 3 net-zero RED.
- [ ] **Code-CI green:** the payment equivalence gate exercises the choreographed saga
      end-to-end in GitHub Actions (red-then-green via disabling the compensation reaction).
- [ ] **ch.23 authored ≥2000 words**, runnable example, embedded diagrams, real "ADLC in
      Action" callout, verification-status footer.
- [ ] **Ledger reconciled:** `decisions.md` DRQ-047…055 accepted + version matrix updated;
      this plan's steps and exit checklist marked DONE.

## Biggest risks
1. **(Highest) The choreographed decline-compensation silently fails or diverges (H3/DRQ-049).**
   The decline is now an event across two async hops with no request-thread catch; if the
   `payment.declined` reaction is missed, not idempotent, or the Release fails, stock stays
   decremented after a decline and Scenario 3 goes RED — or (worse) drifts silently in
   production-shaped runs. Mitigation: baseline Scenario 3 green vs the monolith (S2);
   idempotent reactions issuing Release only on first decline (S6/DRQ-051); S8 forces a decline
   and bounded-waits stock to net-zero; S10 proves it red-then-green by disabling the reaction.
2. **A dishonest equivalence suite hides the contract change (H2/DRQ-055).** Relaxing "expect
   402"/"expect 201" into "accept anything" to make the suite pass would make Scenario 1/3
   vacuous and let the saga do nothing while the gate stays green (the CUTOVER.md §2 trap).
   Mitigation: the *terminal* status + side-effects (payment captured/not, stock net-zero) are
   asserted strictly via bounded-wait; only the transport is relaxed; the negative check
   (consumer down ⇒ stuck PENDING ⇒ RED) proves non-vacuity; S2 is an explicit `[Opus gate]`.
3. **Lost or duplicated events break the saga (DRQ-051/DRQ-053).** At-least-once Kafka + two
   outboxes mean events can be redelivered or (without the outbox) lost on a dual-write crash.
   Mitigation: the payment service emits via its own transactional outbox (atomic with the DB
   write); both consumers are idempotent by `orderId`.
4. **The monolith-as-consumer introduces regression in the synchronous baseline (H4).** Adding
   Kafka consumers + the `202` path to the order god-service risks breaking the still-synchronous
   default. Mitigation: everything new is gated behind `payment.mode=choreographed`; with the
   flag off the full suite stays byte-for-byte green (asserted in S6).

## Resume boundary for ch.24 (shipping — orchestrated saga)
ch.24 resumes from the `build-plan.md` status table. This **choreographed** saga is the
deliberate contrast ch.24 builds against: shipping completes the chain using an
**orchestrated** saga via the **Camel Saga EIP** (DataMesh `_docs/13-orchestration-styles.md`).
The event topology, the payment service's outbox-emit pattern, and the order context's
reaction-consumer + compensation machinery are the foundations shipping extends; ch.26 later
extracts the order aggregate itself (CQRS/GraphQL) and decommissions the monolith. ch.28
replaces this seam's JSON events with Apicurio-registered Avro/Protobuf, cross-referenced
from here.
