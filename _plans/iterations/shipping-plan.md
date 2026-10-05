---
title: "Shipping Extraction Plan — ch.24 (the fifth strangler extraction, ORCHESTRATED SAGA via the Camel Saga EIP)"
description: "Concrete, ordered, executable step plan for the Shipping service extraction: take the shipping dispatch that today fires in-process from the monolith's `OrderSagaListener#onPaymentCaptured` and re-express fulfilment as an ORCHESTRATED saga — a Camel Saga EIP route, co-located in a new Quarkus shipping service, that acts as the central COORDINATOR: it consumes `payment.captured`, explicitly sequences the dispatch steps, registers compensating actions, and on failure (or timeout) drives a coordinator-initiated rollback (cancel shipment + emit `shipment.failed` ⇒ the order context marks the order `SHIPPING_FAILED` and issues the compensating inventory `Release`, net-zero stock). This is the deliberate CONTRAST to ch.23: payment was CHOREOGRAPHED (no coordinator; services react to events, DRQ-048/049); shipping centralizes the control flow in one place you can read top-to-bottom. The hard part is building a genuine, runnable Saga-EIP coordinator whose compensation spans service boundaries and *provably* fires (the net-zero negative check), while keeping the behavior-equivalence suite honest across the now-even-longer async chain and moving the order's `CONFIRMED` transition one hop later (payment.captured → shipment.dispatched) without regressing the synchronous baseline. Mirrors the payment (ch.23) extraction template."
status: "execution plan — planning only; nothing built, scaffolded, or pushed until the user approves"
iteration: r07
chapter: 24
depends_on:
  - _plans/build-plan.md            # §E row 5 (shipping = orchestrated saga via Camel Saga EIP), §G (equivalence gate; saga happy+failure tests), two-phase DRQ-029, non-trivial example DRQ-032, §K single-writer
  - _plans/decisions.md             # DRQ-034 (outbox), DRQ-037 (bounded-wait + negative-check), DRQ-042 (inventory Reserve/Release compensation — reused by the saga's compensating leg), DRQ-047 (async checkout 202/PENDING), DRQ-048 (choreographed topology — the contrast), DRQ-049 (compensation-via-choreography — the contrast), DRQ-055 (equivalence under a saga)
  - _plans/iterations/payment-plan.md  # the proven extraction template this mirrors (choreographed saga); shipping is its orchestrated contrast
  - _plans/reconciliation.md        # ch.23 resume boundary: event topology, outbox-emit pattern, OrderSagaListener reaction machinery that shipping extends
  - examples/00-monolith real code  # shipping/{Shipment,ShipmentStatus,ShipmentDto,ShipmentRepository,ShippingService,ShippingController}, order/OrderSagaListener#onPaymentCaptured (the in-process dispatch to replace), common/Topics (SHIPMENT_DISPATCHED reserved), common/outbox/*
references:
  - "~/Dev/datamesh-reference-arch-quarkus/_docs/13-orchestration-styles.md (DRQ-032: choreography vs. two orchestration shapes over the same shipping/order domain — the Camel route is the canonical orchestration leg; also notes LRA-less in-JVM coordination)"
  - "~/Dev/datamesh-reference-arch-quarkus/examples/shipping-service (DRQ-032: idiomatic Quarkus shipping service — own Postgres `shipment` table, reacts to the saga leg; adapted here to a Camel Saga EIP orchestrator rather than datamesh's pure choreography)"
  - "Camel Saga EIP (camel-mcp `camel_catalog_eip_doc saga`): `.saga().propagation(...).completionMode(AUTO|MANUAL).timeout(...).compensation(uri).completion(uri).option(name, expr)`; coordinator = `CamelSagaService` (InMemorySagaService | LRASagaService); camel `saga` component is Stable"
---

# ch.24 — Shipping Service Extraction (execution plan — ORCHESTRATED SAGA via the Camel Saga EIP)

> **PLANNING ONLY.** This file is the Opus "Plan" phase (ADLC §F.1) and must be
> **user-approved before any code is written**. Nothing is built, scaffolded, or
> pushed until approval.
>
> **Branch for all work:** `r07-shipping-extraction` (off the current default branch).
> **Total steps:** 14 (S1 … S14).
> **Relay tiering (DRQ-004):** every step is *executed* by **Sonnet**; steps marked
> **[Opus gate]** additionally require an **Opus validation** pass before their
> checkpoint commit. Steps marked **[Opus gate — equivalence gate]** are the ADLC
> Verify human sign-off points (§F.1).

## Decisions CONFIRMED (orchestrator, 2026-10-05 — resolved before S1)
The four flagged decisions are resolved as follows (all taking the recommended default, each
aligned with an already-endorsed project principle — scope discipline, no speculative infra,
documented-limitation framing):

1. **Iteration label / branch — CONFIRMED `iteration: r07`; order+gateway becomes r08.**
   BUT the *actual* working branch is the long-lived **`r02-walking-skeleton`** (every prior
   round incl. payment/r06 ran there and merged via PR; there were never per-round branches —
   `payment-plan.md`'s `r06-payment-extraction` was aspirational). So: commit scopes use the
   `r07.x` / `§24` labels, work stays on `r02-walking-skeleton`, and the r07 PR merges to `main`
   at the end (as payment did via PR #4). No new branch is cut. `build-plan.md` §J updated at S1
   to reflect one-extraction-per-iteration (r06 payment, r07 shipping, r08 order).
2. **Saga scope (DRQ-056) — CONFIRMED option (a): bounded shipping saga** triggered on
   `payment.captured`. Option (b) (whole-flow orchestration) rejected — it would tear out the
   finished choreographed payment saga (DRQ-048/049) and erase the choreography-vs-orchestration
   contrast the book teaches on one codebase, and pull in the order aggregate (ch.26).
3. **Coordinator (DRQ-057) — CONFIRMED `InMemorySagaService`** (camel-quarkus-saga), co-located,
   no new infra container (matches ch.23's "no new container"). `LRASagaService`/Narayana deferred
   as the distributed/crash-durable alternative; its in-JVM non-persistence is a documented,
   honest limitation (parallel to ch.23's "no saga ledger").
4. **Intermediate order state (DRQ-061) — CONFIRMED add `AWAITING_SHIPMENT`** (`PENDING` →
   `AWAITING_SHIPMENT` on `payment.captured` → `CONFIRMED` on `shipment.dispatched` |
   `SHIPPING_FAILED` on `shipment.failed`). The external POST contract (DRQ-047) is unchanged;
   the equivalence suite asserts only the *terminal* state.

## Why this extraction is different from payment (the framing)
Payment (ch.23) proved a **choreographed** saga: `order.placed` → the payment service captures
and emits `payment.captured`|`payment.declined` → the monolith's `OrderSagaListener` *reacts*.
**There is no coordinator** — each service knows only its own rule ("when event X arrives, do Y,
emit Z"), and the compensation on decline is itself a *reaction* to `payment.declined` (DRQ-049).
The cost, taught honestly in ch.23, is that no single place describes "what happens when an order
is placed"; you must go find every subscriber.

Shipping is the deliberate **orchestrated** contrast (build-plan §E row 5, §C "Saga — Orchestrated
(D) → ch.24 → yes (Camel Saga EIP)"). An **orchestrated** saga introduces a **named coordinator**
that explicitly sequences the steps and **registers compensating actions**, so the whole fulfilment
flow is readable top-to-bottom in one artifact. Concretely harder/different than payment for four
reasons:

1. **There is now a coordinator, and it lives in the shipping service.** The control flow is not
   spread across independent `@Incoming` handlers; it is a single **Camel Saga EIP route**
   (`.saga()...`) in `examples/06-shipping-service` that consumes `payment.captured`, sequences
   *dispatch → book carrier → emit `shipment.dispatched`*, and declares a single `.compensation(...)`
   (plus `.option(...)` to carry `orderId` and `.timeout(...)` to auto-compensate a stuck saga).
   Writing a genuine, runnable Saga-EIP coordinator — picking the `CamelSagaService`, saving
   correlation data into the compensation callback, getting `completionMode(AUTO)` + abort
   semantics right — is net-new machinery ch.23 never needed.
2. **Compensation is coordinator-initiated and spans service boundaries.** In ch.23 the
   inventory `Release` fired because the order context *reacted* to `payment.declined`. Here the
   **coordinator decides** to compensate (on an exception or timeout in the saga body) and invokes
   its registered compensating action, which must undo work in *other* contexts: cancel the (local)
   shipment, and drive the order context to mark the order `SHIPPING_FAILED` **and** release the
   inventory that checkout reserved — net-zero stock. The coordinator is in shipping; the inventory
   data (the reserved-line snapshot) lives in the order context — so the compensation is delegated
   across the seam, not performed in one place. This is **THE crux and the biggest risk** (H3).
3. **The order's `CONFIRMED` transition moves one hop later.** Today `onPaymentCaptured`
   *immediately* confirms the order and dispatches in-process. Once shipping owns fulfilment, the
   order reaches `CONFIRMED` only when `shipment.dispatched` arrives, and gains a new terminal
   `SHIPPING_FAILED` (and recommended intermediate `AWAITING_SHIPMENT`). The externally-observable
   async contract (DRQ-047) is unchanged, but the happy-path chain grows one Kafka hop, so the
   equivalence suite's Scenario 1 bounded-wait must tolerate a longer convergence.
4. **A new failure-bearing terminal outcome must be proven, net-zero.** Payment's crux was
   Scenario 3 (decline ⇒ stock net-zero). Shipping's crux is a **new shipping-failure scenario**
   (forced `SHIP-FAIL` ⇒ order `SHIPPING_FAILED` ⇒ stock net-zero), driven entirely by the
   **orchestrated** compensation. The negative check is sharper than payment's: disable the Camel
   Saga `.compensation(...)` and the forced failure must leave stock decremented / the order not
   failed ⇒ RED.

## The orchestration decision, stated plainly (with rejected alternatives)
**DECISION (DRQ-056): Shipping is extracted as a new, bounded ORCHESTRATED saga, triggered on
`payment.captured`, whose COORDINATOR is a Camel Saga EIP route co-located in the new Quarkus
shipping service.** It sequences the fulfilment steps and registers the compensating action; on
success it emits `shipment.dispatched` (→ order `CONFIRMED`), on abort/timeout it compensates
(cancel shipment + emit `shipment.failed` → order `SHIPPING_FAILED` + inventory `Release`).

- **Rejected — (b) re-express the whole order→payment→shipping flow as one orchestrated saga.**
  This would require tearing out the *already-DONE, already-accepted* choreographed payment saga
  (DRQ-048/049, ch.23 S14 reconciled) and re-plumbing it under a central orchestrator — undoing
  shipped work, violating scope discipline, and **erasing the very contrast the book is teaching**
  (the whole point of ch.23 vs ch.24 is that *the same codebase* shows choreography **and**
  orchestration side by side, per build-plan §B.1 Part 7 and datamesh `13-orchestration-styles.md`).
  It would also drag the order aggregate into a premature redesign that belongs to **ch.26**
  (order+CQRS, the last/hardest extraction). Rejected.
- **Rejected — shipping-calls-inventory-gRPC-directly for the release (DRQ-060).** Tempting for a
  "pure" orchestrator, but the **reserved-line snapshot lives on the order aggregate's `OrderItem`s**
  (DRQ-043), not in shipping; shipping does not own that data and must not reach into it. The
  compensating `Release` is therefore **delegated**: the coordinator's compensation emits
  `shipment.failed`, and the order context (which already issues the ch.23 compensating `Release`
  via `RemoteInventoryClient`, DRQ-042/049) performs it. The coordinator still *owns the decision
  and ordering* to compensate — that is what makes it orchestration — it just issues the
  compensation as a command/event to the participant that owns the data. Rejected the direct call;
  kept the coordinator-owns-the-decision property.
- **Rejected — `LRASagaService`/`camel-lra` for r07 (DRQ-057).** A distributed LRA coordinator
  (Narayana LRA) is the production-grade, crash-durable choice, but it is **a new infra container**
  — exactly the kind of speculative infrastructure ch.23 was careful to avoid (DRQ-048: "no new
  infra container"). `InMemorySagaService` is in-JVM, co-located, runnable today, and sufficient
  to teach the Saga EIP shape; its non-persistence is a documented honest limitation (parallel to
  ch.23's "no full saga ledger" and Avro→ch.28). LRA is noted as the distributed alternative,
  cross-referenced to ch.25 (resilience) / ch.28 (contracts).

## What this extraction delivers (from build-plan §E row 5, §G, DRQ-032)
1. A new **Quarkus shipping service** at **`examples/06-shipping-service`** (:8086) that **owns its
   own schema/database** (its own `shipment` table + Flyway), exposes the lifted REST
   **`/api/shipments`** read surface, and hosts the **Camel Saga EIP orchestrator**: it **consumes
   `payment.captured`** (SmallRye Reactive Messaging / Kafka), runs the saga route (enrich from the
   order context → dispatch → book carrier → emit), and **emits `shipment.dispatched` or
   `shipment.failed`** reliably via its **own transactional outbox** (mirroring DRQ-053). Adapted
   from datamesh's `shipping-service` with attribution, but re-shaped from pure choreography to a
   Camel Saga coordinator (DRQ-032).
2. The **monolith's order context stops dispatching shipping in-process**: a
   `shipping.mode = inprocess|orchestrated` flag (default `inprocess` = today's
   `OrderSagaListener#onPaymentCaptured` → `order.confirm()` + `shippingService.dispatch(...)`,
   unchanged baseline). In **orchestrated** mode `onPaymentCaptured` stops confirming+dispatching
   (moves the order to `AWAITING_SHIPMENT`), and **new order-saga reactions** handle
   `shipment.dispatched` (→ `order.confirm()` / `CONFIRMED`) and `shipment.failed` (→
   `SHIPPING_FAILED` **and** the compensating gRPC `Release` for every reserved sku — reusing the
   ch.23 machinery). New `OrderStatus.SHIPPING_FAILED` (+ `AWAITING_SHIPMENT`).
3. The **Camel strangler proxy** gains `strangler.shipping.enabled` routing `/api/shipments` to the
   new service (base-url :8086). **ACL honesty (DRQ-065, following the inventory/payment precedent):**
   `ShipmentDto` is byte-for-byte portable (`id, orderId:Long, address, status, createdAt`), so the
   branch is a **transparent reverse proxy**, *no* `ShippingAclRoute` translator — building one would
   be the "speculative-infrastructure trap" the proxy's own javadoc already warns against. (If, at
   execution, the extracted DTO genuinely diverges, *then* a translator is added and documented;
   default is transparent.)
4. The **behavior-equivalence suite is extended for the orchestrated saga and stays honest**:
   **Scenario 1** happy-path bounded-waits to `CONFIRMED` across the now-longer chain
   (order.placed → payment.captured → shipping saga → shipment.dispatched → CONFIRMED); a **new
   shipping-failure scenario** (forced `SHIP-FAIL`) bounded-waits the order to **`SHIPPING_FAILED`**
   **and** bounded-waits inventory back to **net-zero** (the orchestrated compensation); **Scenario 2**
   (out-of-stock `409`) and **Scenario 3** (payment-declined `PAYMENT_DECLINED` + net-zero) are
   **unchanged** (payment decline short-circuits before shipping runs); plus a new **Shipping Context
   Contract** folder for `/api/shipments`. A **negative check** (disable the Camel Saga
   `.compensation(...)` ⇒ a forced `SHIP-FAIL` leaves stock decremented / the order not failed ⇒
   RED) proves the orchestrated compensation genuinely fires.
5. Tests at every tier + a **shipping equivalence gate** in CI (Postgres + Kafka + shipping service +
   payment service + inventory gRPC service + monolith), red-then-green by disabling the saga
   compensation; plus the **cross-service cascade** (every checkout-driven gate now also needs the
   shipping service up, because checkout's terminal `CONFIRMED` depends on it — learned from ch.23's
   S10b).

## Decisions seeded by this plan (append to `_plans/decisions.md`, next free IDs after DRQ-055)
- **DRQ-056 — Shipping is a bounded ORCHESTRATED saga triggered on `payment.captured`, coordinated
  by a Camel Saga EIP route in the new shipping service.** Rationale and rejected alternative
  (whole-flow re-expression) as stated plainly above. The deliberate contrast to DRQ-048/049's
  choreography; realizes build-plan §E row 5 and §C "Saga — Orchestrated (Camel Saga EIP)".
- **DRQ-057 — Coordinator = `InMemorySagaService` (camel-quarkus-saga), co-located in the shipping
  service; no new infra container.** `LRASagaService`/`camel-lra` + a standalone Narayana LRA
  coordinator considered and **deferred** (it is new infra, contra DRQ-048's "no new container"
  discipline). Documented honest limitation: in-memory saga state is **not crash-durable** (a
  coordinator restart mid-saga loses in-flight state) — acceptable for the teaching example, with
  LRA named as the distributed/production alternative (cross-ref ch.25/ch.28). JSON serialization
  on the wire (DRQ-038; Avro/Apicurio still ch.28).
- **DRQ-058 — Orchestrated topology & event contract.** `payment.captured` (produced by the payment
  service, DRQ-048; now ALSO consumed by the shipping service's saga, in addition to the monolith's
  order reaction) → shipping saga → `shipment.dispatched` (**wires the already-reserved
  `SHIPMENT_DISPATCHED` topic in `common/Topics.java`**; consumed by the monolith order context →
  `CONFIRMED`) | **`shipment.failed`** (new topic — add `SHIPMENT_FAILED` to `Topics.java`; consumed
  by the monolith → `SHIPPING_FAILED` + compensating `Release`). The order's `CONFIRMED` transition
  **moves from `payment.captured` (ch.23) to `shipment.dispatched` (here)**.
- **DRQ-059 — Saga steps & compensating action (the Camel Saga EIP shape).** The saga route:
  `.saga().propagation(REQUIRES_NEW).completionMode(AUTO).timeout(...).compensation("direct:ship-compensate")
  .option("orderId", ...)` then *enrich order details* (address/correlation from the order read
  surface) → *dispatch shipment* (persist `Shipment` DISPATCHED, local) → *book carrier* (the
  deterministic `SHIP-FAIL` injection point, DRQ-062) → *emit `shipment.dispatched`* (via the outbox).
  `direct:ship-compensate` (invoked by the coordinator on any abort/timeout): *cancel shipment*
  (`Shipment`→CANCELLED if created) + *emit `shipment.failed`* (via the outbox). This is
  **orchestrated** compensation (coordinator-decided, reverse-ordered) vs ch.23's **choreographed**
  compensation (DRQ-049, each service reacts). `.option(...)` carries the correlation key into the
  compensation callback; `.timeout(...)` auto-compensates a stuck saga.
- **DRQ-060 — Compensation delegation across contexts.** The compensating inventory `Release` is
  **not** issued by the shipping service (it does not own the reserved-line snapshot, DRQ-043).
  Instead the saga's compensation emits `shipment.failed`, and the **order context** marks the order
  `SHIPPING_FAILED` and issues the compensating gRPC `Release` for every reserved sku — reusing the
  exact ch.23 `OrderSagaListener` + `RemoteInventoryClient` machinery (DRQ-042/049). The coordinator
  still owns the *decision and ordering* to compensate (the orchestration property); the participant
  that owns the data performs the undo. Rejected: shipping calling inventory gRPC directly.
- **DRQ-061 — New order states: `SHIPPING_FAILED` (terminal) + `AWAITING_SHIPMENT` (intermediate).**
  In orchestrated mode: `PENDING` → (`payment.captured`) `AWAITING_SHIPMENT` → (`shipment.dispatched`)
  `CONFIRMED` | (`shipment.failed`) `SHIPPING_FAILED`. `orderId` remains the saga correlation key.
  The external async contract (DRQ-047) is unchanged; the suite asserts only the *terminal* state.
  (If the user prefers, `AWAITING_SHIPMENT` can be dropped and the order stays `PENDING` until
  `shipment.dispatched` — see "Decisions needing confirmation" #4.)
- **DRQ-062 — Deterministic `SHIP-FAIL` failure injection (mirrors payment's `CARD-DECLINE`).** A
  sentinel in the order (recommended: a `SHIP-FAIL` marker in the shipping address, or a sentinel
  sku) makes the saga's *book carrier* step throw deterministically, so the compensation path is
  reproducibly exercised by the equivalence suite and the CI red-then-green. The injection fires
  **after** the `Shipment` row is persisted so that *both* compensations (cancel shipment **and**
  emit `shipment.failed`→release) are exercised.
- **DRQ-063 — Shipping service: two-phase read surface; saga/consumer/producer idiomatic from the
  start; own schema; FK decomposed; forward-filled; own transactional outbox.** `/api/shipments`
  (`ShipmentController`/`ShippingService.getById,listByOrderId`/`Shipment`+repo) follows DRQ-029
  Phase A (spring-compat lift) → Phase B (idiomatic Quarkus REST + Panache). The `Shipment` entity's
  `@ManyToOne Order` FK (SMELL[ch.18]) becomes a plain **`orderId` value** (no cross-DB FK). The
  Camel Saga route, the `payment.captured` consumer, and the outbox producer have no Spring original
  to lift, so they are **idiomatic from day one** (honest reading of DRQ-029/035, as notification's
  consumer and payment's capture were). The owned store is **forward-filled by the saga** — **no CDC
  backfill** (contrast inventory/DRQ-040; justified exactly as payment/DRQ-052: shipments are created
  forward at dispatch time, not migrated). Emits via its **own transactional outbox**
  (`ShipmentOutboxEvent`/`ShipmentOutboxRelay`, mirroring DRQ-053) so `shipment.dispatched`/
  `shipment.failed` are written atomically with the `Shipment` state change (no dual-write).
- **DRQ-064 — Idempotency & at-least-once (reuses DRQ-034/037/051).** The saga consumer dedupes by
  `orderId` (a unique constraint — at most one shipment per order, so a redelivered `payment.captured`
  does not create a second shipment or run the saga twice); the order-context reactions guard the
  status transition (a redelivered `shipment.dispatched`/`shipment.failed` is a no-op once the order
  has left `AWAITING_SHIPMENT`, and the compensating `Release` is issued only on the first
  `shipment.failed`). Honest limitation (reaffirming DRQ-051/057): no full saga ledger / no
  idempotency key on `Release`; InMemorySagaService state is non-durable.
- **DRQ-065 — Reversibility via two flags; equivalence under an orchestrated saga; ACL honesty.**
  Write side: monolith `shipping.mode = inprocess|orchestrated` (default `inprocess` = today's
  in-process confirm+dispatch). Read side: proxy `strangler.shipping.enabled` (default `false`).
  Cutover flips both together; reversible until decommission (mirrors Review/Notification/Inventory/
  Payment). **Equivalence (extends DRQ-055):** Scenario 1 bounded-waits `CONFIRMED` across the longer
  chain; a **new shipping-failure scenario** bounded-waits `SHIPPING_FAILED` **and** inventory
  net-zero; Scenario 2/3 unchanged; a **Shipping Context Contract** folder asserts `/api/shipments`.
  A **negative check** (disable the Camel Saga `.compensation(...)` ⇒ forced `SHIP-FAIL` leaves stock
  decremented / order not failed ⇒ RED) proves the orchestrated compensation is genuinely exercised.
  **ACL:** `ShipmentDto` is portable unchanged ⇒ transparent reverse proxy, no translator (the
  honest default; a translator only if the DTO diverges at execution).

## Standing constraints applied to every step
Scope discipline (nothing here that an r07 ch.24 deliverable doesn't require — no order/CQRS/GraphQL
extraction [that is ch.26], no LRA/Narayana coordinator container [deferred, DRQ-057], no
Apicurio/Avro [ch.28], no full saga ledger/state-machine beyond what the two saga scenarios require,
no speculative `ShippingAclRoute` unless the DTO diverges); conceptual coherence (the coordinator is
one readable artifact); security-by-design (OWASP/CIS — secrets hygiene, no creds in git, Kafka creds
from env/Bitwarden, secure-by-default Camel dynamic-URI allow-list, no request-derived routing
targets); **SIMPLE git only — `git -C <dir> …`, never `cd && git`**; **never push beyond
`github.com/patterncatalyst/modernizing-enterprise-applications` without explicit user permission**;
Conventional Commits (`feat`/`fix`/`docs`/`chore`/`refactor`/`ci`/`test`/`site` + scopes `rNN.x`,
`§NN`, service names); **NO attribution trailers**. A subagent does **not** inherit a loaded skill —
each executor prompt must explicitly invoke/read the named skill. Base package is
`dev.patterncatalyst.*` (monolith `dev.patterncatalyst.monolith`, services
`dev.patterncatalyst.<service>`).

## Parallelism overview
```
S1 (frame + branch + decisions, SEQUENTIAL, must be first)
S2 equivalence suite: extend for the ORCHESTRATED saga (Scenario 1 longer bounded-wait;
   NEW shipping-failure scenario → SHIPPING_FAILED + net-zero; Shipping Context Contract);
   baseline green vs monolith (in-process shipping)                                   [Opus gate]  ← THE crux
 ├─ S3 event contract: SHIPMENT_FAILED topic + wire SHIPMENT_DISPATCHED + JSON schemas (shared) ─┐  (parallel after S1)
 └─ S4 shipping svc scaffold + Phase A read-surface lift (own schema, FK→orderId value) ─────────┘  (lane N, parallel) [Opus gate]
S5 shipping svc Phase B idiomatic + Camel Saga EIP orchestrator (InMemorySagaService): consume
   payment.captured → enrich → dispatch → book carrier → emit via outbox; compensation leg; idempotent;
   measured (SEQ after S3+S4)                                                          [Opus gate]  ← HARD PART (the orchestrated core)
S6 monolith wiring: shipping.mode flag; AWAITING_SHIPMENT/SHIPPING_FAILED; onPaymentCaptured stops
   in-process dispatch; order-saga reactions (shipment.dispatched→confirm; shipment.failed→fail+Release)
   (lane M, SEQ after S5)                                                              [Opus gate]  ← HARD PARTS
S7 strangler proxy: shipping flag + /api/shipments route (transparent — ACL honesty) (after S3+S5, may overlap S6)
S8 CUTOVER: flip both flags; equivalence green across the seam (bounded-wait CONFIRMED + shipping-failure
   SHIPPING_FAILED + net-zero); reversibility; negative check (SEQ after S6+S7) [Opus gate — equivalence gate]  ← HARD PARTS
S9 DECOMMISSION monolith in-process shipping path (ShippingController/Service/Shipment/repo + the
   in-process dispatch in onPaymentCaptured + shipping.mode flag) (SEQ after S8)       [Opus gate]
S10 Code-CI: shipping equivalence gate (Kafka + shipping + payment + inventory + monolith) red-then-green;
    + cross-service cascade into sibling gates (SEQ after S9)                          [Opus gate]
 ├─ S11 ch.24 diagram(s) ───┐   (PARALLEL after S5)
 └─ S12 ch.24 ADLC trace ───┘   (PARALLEL after S9 evidence exists)
S13 ch.24 authored to the bar (SEQ after S9 + S11 + S12)   [Opus gate — 2k + footer]
S14 reconcile + status + exit (SEQ, last)                  [Opus gate]
```
**Single-writer / serialize (§K):** the monolith reactor `pom.xml` + `OrderSagaListener` +
`OrderService`/`OrderStatus` + `application.yml` + Flyway migrations (S6, S9 — lane M, one writer,
serialized); `examples/01-strangler-proxy/` `application.properties` + `StranglerProxyRoute.java`
(S7, S8 — append the shipping branch, don't rewrite); `common/Topics.java` + the shared event-contract
JSON (S3 — single contract writer; coordinate so S6 does not also edit `Topics.java`);
`tooling/newman/mea.postman_collection.json` (S2 — versioned with the monolith, R8);
`.github/workflows/code-ci.yml` (S10); `assets/diagrams/README.md` catalogue (S11); the three
`_plans/*` ledgers (S1, S14). `compose.yaml`/`.env` already run Kafka (ch.17) and the inventory gRPC
server (ch.19) — **no new infra container is needed** (InMemorySagaService is in-JVM; LRA deferred),
so S-steps only *consume* the existing broker + add the shipping service process.

---

## THE HARD PARTS (called out explicitly, per the brief)

**H1 — Building a genuine, runnable Camel Saga EIP coordinator (S5, DRQ-056/057/059).** This is the
net-new machinery payment never needed. The saga route must: register the `CamelSagaService`
(`InMemorySagaService`) as a CDI bean the `.saga()` DSL looks up; carry correlation data into the
compensation via `.option("orderId", ...)`; declare `.compensation("direct:ship-compensate")` and a
`.timeout(...)`; and get `completionMode(AUTO)` abort semantics right so that **any** exception (or
timeout) in the saga body reliably triggers the compensation exactly once. Mitigation: ground the DSL
in `camel_catalog_eip_doc saga` (confirmed: `propagation/completionMode/timeout/compensation/
completion/option`), validate with `camel_validate_route`, and unit-test the abort path
(MockEndpoint/AdviceWith) before any end-to-end run. The risk is a coordinator that *looks* wired but
never actually invokes compensation on failure — caught by H3's net-zero negative check.

**H2 — Keeping the behavior-equivalence suite honest across an even-longer async chain + a new
terminal outcome (S2, DRQ-065).** Why S2 is `[Opus gate]`. The suite must (a) still baseline-green
against the *in-process* monolith **before** anything is built, and (b) green against the orchestrated
saga **after** cutover, **without weakening** assertions. Two traps: (i) Scenario 1 now converges one
hop later, so a too-short bounded-wait budget goes falsely RED (tune attempts/delay, don't relax the
terminal assertion); (ii) the **new shipping-failure scenario** must assert the *terminal*
`SHIPPING_FAILED` **and** net-zero stock strictly — relaxing it into "accept anything" would make it
vacuous (the CUTOVER.md §2 false-positive that bit Review). Mitigation: assert terminal status +
net-zero via bounded-wait; the negative check (disable compensation ⇒ RED) proves non-vacuity;
baseline strictly against the in-process monolith first.

**H3 — Coordinator-initiated, cross-context compensation that provably fires, net-zero (S5/S6/S8,
DRQ-059/060 — THE crux & biggest risk).** On a forced `SHIP-FAIL`, the coordinator must drive:
cancel the shipment (local) **and** — across the seam — order `SHIPPING_FAILED` **and** inventory
`Release` for every reserved sku, converging to net-zero stock. The compensation is delegated
(shipment.failed → order context issues the `Release`), so it spans the shipping→Kafka→order→gRPC
path with no request thread to lean on. If the compensation is missed, mis-ordered, not idempotent,
or the `Release` fails, stock stays decremented after a shipping failure — the new scenario goes RED
(good) or drifts silently (the thing we most fear). Mitigation: S2 baselines the scenario green vs
the (in-process) monolith; the order reaction is idempotent and issues `Release` only on the first
`shipment.failed` (DRQ-064); S8 forces `SHIP-FAIL` and bounded-waits stock to net-zero; S10 proves it
red-then-green by disabling the saga `.compensation(...)`.

**H4 — Moving the order's `CONFIRMED` one hop later + the monolith stops dispatching in-process,
without regressing the synchronous/default baseline (S6, DRQ-058/061).** `onPaymentCaptured` currently
confirms + dispatches in-process; in orchestrated mode it must stop doing both and instead the order
is driven by `shipment.dispatched`/`shipment.failed` reactions, with new `AWAITING_SHIPMENT`/
`SHIPPING_FAILED` states. The monolith must become a consumer of two *new* topics while the
`shipping.mode=inprocess` default keeps the entire new path dormant (byte-for-byte green baseline).
Mitigation: everything new gated behind `shipping.mode=orchestrated`; reactions idempotent and
status-guarded; with the flag off the full suite stays green (asserted in S6).

---

## S1 — Frame, branch, decisions  *(SEQUENTIAL — must be first)*
- **(b) Goal / DoD:** The ch.24 outcome + acceptance criteria are framed; the working branch exists;
  **DRQ-056…065** are appended to `decisions.md` (esp. the orchestration-scope decision DRQ-056, the
  coordinator choice DRQ-057, and the delegated-compensation decision DRQ-060); a `build-plan.md`
  status row for r07/ch.24 is set to "in progress" (and §E row 5 updated from "not started"); the
  version matrix gains forward rows (shipping-service `camel-quarkus-saga` + `quarkus-messaging-kafka`
  versions, reuse of the existing Kafka broker tag). The four "Decisions needing confirmation" are
  surfaced to the user for sign-off before S2 begins.
- **(c) Creates/touches:** `_plans/decisions.md` (DRQ-056…065 + matrix rows), `_plans/build-plan.md`
  (status row + §E row 5 status), checkout branch `r07-shipping-extraction`. No code.
- **(d) Skills/MCP:** none (ledger authoring). `git -C <dir> checkout -b`.
- **(e) Deps / parallel:** none; SEQUENTIAL, gates everything.
- **(f) Collision risk:** `_plans/*` single-writer — this and S14 are the only ledger writers.
- **(g) Acceptance:** branch is `r07-shipping-extraction`; DRQ-056…065 present; the orchestration
  scope/coordinator/topology/compensation/state/failure-injection/service/idempotency/reversibility/
  equivalence decisions written; the four confirmations flagged for the user.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(r07.x): frame ch.24 shipping orchestrated-saga extraction; seed DRQ-056..065 and working branch`

## S2 — Extend the equivalence suite for the ORCHESTRATED saga (baseline green vs monolith)  *(SEQUENTIAL, after S1)*  **[Opus gate]**  — **HARD PARTS H2/H3 — THE crux**
- **(b) Goal / DoD:** Extend the checkout folders for the longer chain and the new terminal outcome
  **without weakening them** (DRQ-065): **Scenario 1** — keep the `201|202`-tolerant POST, then a
  **bounded-wait poll** of `GET /api/orders/{id}` to **`CONFIRMED`**, with the attempt/delay budget
  **widened** to cover the extra Kafka hop (order.placed→payment.captured→shipping saga→
  shipment.dispatched→CONFIRMED) — tune the budget, do **not** relax the terminal assertion. Add a
  **new "Scenario 4 — Shipping-Failure"** folder: place an order carrying the deterministic
  `SHIP-FAIL` sentinel (DRQ-062), then a bounded-wait poll asserting the order reaches
  **`SHIPPING_FAILED`** **and** a bounded-wait poll asserting inventory returns to **net-zero**
  (stock restored by the orchestrated compensation). **Scenario 2** (out-of-stock `409`) and
  **Scenario 3** (payment-declined `PAYMENT_DECLINED` + net-zero) are **left unchanged** (payment
  decline short-circuits before shipping). Add a new **"Shipping Context Contract"** folder asserting
  `/api/shipments?orderId=` (array of `ShipmentDto`) and `/api/shipments/{id}` shapes + a `404` on
  unknown id. Capture everything **green against the still-in-process monolith** (Scenario 1 confirms
  via the in-process dispatch; the shipping-failure scenario, baselined, must *also* behave
  honestly — the in-process monolith currently has no shipping-failure path, so this folder is added
  as an *expected-after-cutover* assertion and marked pending/`disabled` at baseline, then enabled at
  S8; document this baseline treatment explicitly so the baseline is not falsely green on an
  unexercised path).
- **(c) Creates/touches:** `tooling/newman/mea.postman_collection.json` (widen Scenario 1 bounded-wait
  budget; add "Scenario 4 — Shipping-Failure" + "Shipping Context Contract" folders after the Payment
  Context Contract folder; Scenario 2/3 untouched),
  `tooling/newman/shipping-service.postman_environment.json` (forward-ref env on :8086).
  **Collection versioned with the monolith (R8).** Reuse the existing `setNextRequest` self-rerun
  bounded-wait idiom (Scenario 1c/3c).
- **(d) Skills/MCP:** Newman (reuse the bounded-wait pattern from Scenario 1c/3c / DRQ-037/055). Runs
  against the monolith on :8080 (via the proxy :8888 for read folders).
- **(e) Deps / parallel:** after S1; SEQUENTIAL (must baseline before any behavior changes).
- **(f) Collision risk:** low (own `tooling/`), but it is the equivalence contract — ripples to every
  later run; the honesty of the whole extraction rests here.
- **(g) Acceptance:** the extended collection is **green against the unmodified in-process monolith**
  (Scenario 1 → CONFIRMED; Scenario 2 → 409; Scenario 3 → PAYMENT_DECLINED + net-zero), with the new
  shipping-failure + Shipping Context Contract folders in place and honestly marked
  pending-until-cutover. **[Opus gate]:** Opus confirms the shipping-failure assertions strictly
  assert terminal `SHIPPING_FAILED` + net-zero (not "any 2xx"), that the widened Scenario 1 budget did
  not relax its terminal assertion, and that the baseline is not falsely green on an unexercised path.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `test(equivalence): extend checkout for orchestrated shipping saga (longer bounded-wait; new SHIPPING_FAILED + net-zero scenario; Shipping Context Contract); green vs monolith baseline`

## S3 — Event contract: wire `SHIPMENT_DISPATCHED` + add `SHIPMENT_FAILED` topic + JSON schemas  *(PARALLEL, after S1)*
- **(b) Goal / DoD:** Define the saga's event vocabulary (DRQ-058): the already-reserved
  `SHIPMENT_DISPATCHED = "shipment.dispatched"` in `common/Topics.java` is now **wired** (producer =
  shipping service, consumer = monolith order context), and a new **`SHIPMENT_FAILED =
  "shipment.failed"`** constant is added. Author the JSON payload record(s) for both outcomes
  (`orderId`, `shipmentId`, `address`, `status`, `occurredAt`, and for the failure a `reason`) — the
  shared wire contract the shipping service produces and the order context consumes. JSON per DRQ-038
  (Avro/Apicurio still ch.28). Document producer/consumer for each topic
  (payment.captured→shipping→{shipment.dispatched,shipment.failed}→order).
- **(c) Creates/touches:** `examples/00-monolith/.../common/Topics.java` (add `SHIPMENT_FAILED`),
  a small shared `ShipmentEvent`/`ShipmentDispatched`/`ShipmentFailed` record (consumed by the order
  reactions) + its field-for-field mirror in the shipping service (no shared code, mirroring the
  payment `PaymentCaptured`/`PaymentDeclined` precedent), a short in-service `EVENTS.md` documenting
  the contract. **Single writer of the contract + Topics.java.**
- **(d) Skills/MCP:** none new (plain records + topic constants).
- **(e) Deps / parallel:** after S1; **PARALLEL with S4**. Feeds S5, S6, S7.
- **(f) Collision risk:** `Topics.java` is in lane M's file set — this step is its only r07 writer;
  coordinate so S6 does not also edit it.
- **(g) Acceptance:** `SHIPMENT_FAILED` constant present; `SHIPMENT_DISPATCHED` documented as now-wired;
  both event payloads defined with matching fields on producer (shipping svc) and consumer (order)
  sides; producer/consumer mapping documented.
- **(h) Tier:** Sonnet. No Opus gate (proven in S5/S6).
- **(i) Checkpoint commit:** `feat(shipping): event contract — wire shipment.dispatched + add shipment.failed topic + JSON payloads (the orchestration vocabulary)`

## S4 — Shipping service: scaffold + Phase A read-surface lift (own schema)  *(PARALLEL lane N, after S1)*  **[Opus gate]**
- **(b) Goal / DoD:** A new Quarkus module `examples/06-shipping-service` (:8086) that **owns its own
  schema/database** (its own `shipment` table + Flyway). The monolith's Spring read surface is
  **lifted via Quarkiverse Spring-compat** (`quarkus-spring-web`/`-di`/`-data-jpa`):
  `ShippingController` → `/api/shipments` + `/api/shipments/{id}`,
  `ShippingService.getById/listByOrderId`, `Shipment` entity + repo (`findAllByOrderId`). **The
  `Shipment` entity's `@ManyToOne Order` FK (SMELL[ch.18]) becomes a plain `orderId` value** (no
  cross-DB FK — the FK decomposition, same move payment's entity made, DRQ-063). `ShipmentStatus` is
  extended from the monolith's single `DISPATCHED` to add **`CANCELLED`** (needed by the saga
  compensation; and a `PENDING`/`FAILED` as the model requires). No saga/consumer/producer yet (S5).
  Owned store starts empty and is filled forward by the saga — **no CDC backfill** (contrast
  inventory/DRQ-040), documented as deliberate (shipments are created forward, not migrated).
- **(c) Creates/touches:** `examples/06-shipping-service/**` (pom, `application.properties` on :8086
  with its own datasource + Flyway, controller/service/entity/repo, tests), `src/main/docker/*`.
  Isolated subtree.
- **(d) Skills/MCP:** **quarkus-agent** — `quarkus_skills` against the monolith dir to discover +
  follow **`migrate-spring-to-quarkus`** (do NOT self-plan the migration);
  `quarkus_create`/`quarkus_start`/`quarkus_searchDocs`; **lgtm-quarkus**.
- **(e) Deps / parallel:** scaffold after S1; **PARALLEL with S3**. Isolated dir.
- **(f) Collision risk:** low — isolated under `examples/06-shipping-service/`.
- **(g) Acceptance:** service boots on :8086 against its own DB; `/api/shipments` read contract matches
  the monolith's `ShipmentDto` shape byte-for-byte; Spring-compat extensions present (Phase A);
  `Shipment` holds `orderId` as a value (no cross-context FK); unit tests green. **[Opus gate]:** Opus
  confirms it owns its schema (no reach into the shared monolith table), the read contract matches, and
  the FK is decomposed to a value reference.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(shipping): Phase A — lift /api/shipments onto Quarkus via Spring-compat; own schema; orderId value reference (FK decomposed)`

## S5 — Shipping service: Phase B idiomatic + Camel Saga EIP orchestrator, measured  *(SEQUENTIAL, after S3 + S4)*  **[Opus gate]**  — **HARD PART (the orchestrated core)**
- **(b) Goal / DoD:** Refactor the read surface off the compat shim to **idiomatic Quarkus** (Quarkus
  REST + Panache, native CDI) — mirroring prior Phase B — and author the **net-new orchestrated core
  (idiomatic-from-start, DRQ-063):** register an **`InMemorySagaService`** CDI bean (DRQ-057;
  `camel-quarkus-saga`), a **SmallRye Reactive Messaging consumer** of `payment.captured` that hands
  off to the **Camel Saga EIP route** (DRQ-059): `.saga().propagation(REQUIRES_NEW)
  .completionMode(AUTO).timeout(...).compensation("direct:ship-compensate").option("orderId", ...)` →
  *enrich* (fetch the order's shipping address/correlation from the order read surface,
  `GET :8080/api/orders/{id}` — confirm at execution whether `order.placed` already carries the
  address and prefer that if so) → *dispatch shipment* (persist `Shipment` DISPATCHED) → *book carrier*
  (the deterministic `SHIP-FAIL` throw point, DRQ-062) → *emit `shipment.dispatched`* via the
  **own transactional outbox** (`ShipmentOutboxEvent`/`ShipmentOutboxRelay`, DRQ-053-style). The
  compensation route `direct:ship-compensate` (coordinator-invoked on abort/timeout): *cancel shipment*
  (`Shipment`→CANCELLED) + *emit `shipment.failed`* via the outbox. **Idempotent by `orderId`** (unique
  constraint ⇒ a redelivered `payment.captured` does not create a second shipment or re-run the saga,
  DRQ-064). Capture **measured before/after** (startup/RSS/native) as a `MIGRATION.md`. **Adapt from
  datamesh `shipping-service` with attribution (DRQ-032), re-shaped from choreography to a Saga EIP
  coordinator.**
- **(c) Creates/touches:** `examples/06-shipping-service/**` — pom (remove spring-compat; add
  `camel-quarkus-saga`, `quarkus-messaging-kafka`, `quarkus-hibernate-orm-panache`,
  `quarkus-rest(+jackson)`, a REST client for the order enrichment, `quarkus-scheduler` for the outbox
  relay, `quarkus-smallrye-health`), `application.properties` (incoming `payment.captured` + outgoing
  `shipment.dispatched`/`shipment.failed` channels + order-service base-url), the saga route + the
  `payment.captured` consumer, the shipment outbox + relay, Panache refactor, `MIGRATION.md`,
  Citrus/`@QuarkusTest` + Dev Services tests (a happy-path dispatch-then-emit round-trip, a
  `SHIP-FAIL` abort-then-compensate round-trip asserting cancel+shipment.failed, an idempotent-
  redelivery test, and an AdviceWith/MockEndpoint unit test of the saga abort path).
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (`camel_catalog_eip_doc saga`,
  `camel_route_context`, `camel_validate_route`, `camel_render_route_diagram`); **quarkus-agent**
  (`quarkus_skills messaging,kafka,panache`; `quarkus_searchDocs`), **lgtm-quarkus**; Dev Services for
  Kafka + PG in tests.
- **(e) Deps / parallel:** after S3 (event contract) + S4 (service). SEQUENTIAL.
- **(f) Collision risk:** low (same isolated module).
- **(g) Acceptance:** consuming a normal `payment.captured` runs the saga to completion — persists a
  DISPATCHED `Shipment` and emits exactly one `shipment.dispatched`; a `SHIP-FAIL` order **aborts the
  saga** ⇒ the coordinator invokes `direct:ship-compensate` ⇒ the shipment is CANCELLED and exactly one
  `shipment.failed` is emitted (no `shipment.dispatched`); a redelivered `payment.captured` does **not**
  create a second shipment (idempotent); `camel_validate_route` clean; `/api/shipments` read contract
  unchanged; before/after metrics captured (real runs). **[Opus gate]:** Opus verifies the saga
  coordinator genuinely invokes compensation on *any* abort/timeout exactly once, the outbox makes
  each emit atomic with the DB write (no lost/dual-write event), idempotency holds under redelivery,
  and the compensation path emits `shipment.failed` rather than failing silently.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(shipping): Phase B — idiomatic Quarkus + Camel Saga EIP orchestrator (InMemorySagaService): consume(payment.captured)/dispatch/compensate/emit via transactional outbox (idempotent, measured)`

## S6 — Monolith wiring: shipping.mode flag, order states, order-saga reactions  *(lane M, SEQUENTIAL after S5)*  **[Opus gate]**  — **HARD PARTS H3/H4**
- **(b) Goal / DoD:** In `examples/00-monolith`, add `shipping.mode = inprocess|orchestrated` (default
  `inprocess` = today's `OrderSagaListener#onPaymentCaptured` → `order.confirm()` +
  `shippingService.dispatch(order, order.getShippingAddress())`, unchanged baseline). In
  **orchestrated** mode: `onPaymentCaptured` **stops confirming + dispatching** and instead transitions
  the order `PENDING`→**`AWAITING_SHIPMENT`** (DRQ-061). Add **order-saga reaction consumers** for the
  two new topics: on **`shipment.dispatched`** → `order.confirm()` (→ `CONFIRMED`); on
  **`shipment.failed`** → mark **`SHIPPING_FAILED`** **and issue the compensating gRPC `Release`** for
  every sku the order reserved (DRQ-060 — reusing the exact ch.23 `RemoteInventoryClient` machinery).
  Add `OrderStatus.SHIPPING_FAILED` (+ `AWAITING_SHIPMENT`). Both reactions **idempotent** (status-
  transition guard; `Release` only on first `shipment.failed`, DRQ-064). The in-process
  `shippingService.dispatch(...)` call is **bypassed** in orchestrated mode (removed entirely at S9).
- **(c) Creates/touches:** `examples/00-monolith/pom.xml` (+ spring-kafka consumer config for the two
  new topics), `order/OrderSagaListener.java` (gate `onPaymentCaptured` by `shipping.mode`; add
  `onShipmentDispatched`/`onShipmentFailed` `@KafkaListener`s), `order/OrderStatus.java`
  (+`AWAITING_SHIPMENT`, `SHIPPING_FAILED`), `order/Order.java` (state transitions), `application.yml`
  (`shipping.mode`, consumer group/topics), a Flyway migration only if `OrderStatus` is persisted as a
  constrained enum. Does **not** touch the shipping service or proxy dirs. Lane M single writer.
- **(d) Skills/MCP:** Spring authored manually (per §K); **quarkus-agent** `quarkus_searchDocs` only if
  needed; verify against the live S5 service + Kafka.
- **(e) Deps / parallel:** after S5 (needs the shipping service running the saga). SEQUENTIAL in lane M.
- **(f) Collision risk:** **HIGH** — lane M sole writer of the monolith reactor root + `OrderSagaListener`
  + `OrderStatus`/`Order` + `application.yml` in r07; serialized with S9.
- **(g) Acceptance:** with `shipping.mode=inprocess` the full suite stays green (zero regression — order
  still CONFIRMED + dispatched in-process on `payment.captured`). With `shipping.mode=orchestrated`:
  `payment.captured` → order `AWAITING_SHIPMENT`; `shipment.dispatched` → `CONFIRMED`; `shipment.failed`
  → `SHIPPING_FAILED` **and** the compensating `Release` restores stock (net-zero); redelivered events
  are no-ops. **[Opus gate]:** Opus confirms the compensation fires on `shipment.failed` for **every**
  reserved sku, the reactions are idempotent, no order is confirmed without a `shipment.dispatched`, and
  a forced shipping failure leaves stock net-zero (H3), with the `inprocess` baseline untouched (H4).
- **(h) Tier:** Sonnet execute; **Opus validate** (order-lifecycle change + cross-context compensation).
- **(i) Checkpoint commit:** `feat(monolith): orchestrated shipping mode — AWAITING_SHIPMENT/SHIPPING_FAILED, order-saga reactions (shipment.dispatched->confirm, shipment.failed->fail+compensating Release); flag-gated, default inprocess`

## S7 — Strangler proxy: shipping flag + /api/shipments route (transparent — ACL honesty)  *(SEQUENTIAL, after S3 + S5; may overlap S6)*
- **(b) Goal / DoD:** Append to `StranglerProxyRoute` a `strangler.shipping.enabled` flag (default
  **false** → monolith) + `strangler.shipping.base-url` (:8086), content-based routing on the **full
  `/api/shipments`** path prefix (heeding the Review `/reviews`-prefix bug), reverse-proxied
  transparently (`bridgeEndpoint=true&throwExceptionOnFailure=false`), with a `TARGET_SHIPPING`
  property constant. **ACL honesty (DRQ-065):** `ShipmentDto` is byte-for-byte portable, so **no
  `ShippingAclRoute`/translator** — follow the existing honest transparent-proxy precedent (the
  proxy's javadoc already argues a no-op translator is a speculative-infrastructure trap). Only if the
  extracted DTO genuinely diverges at execution is a translator added and documented. Secure-by-default:
  only the fixed operator-configured target.
- **(c) Creates/touches:** `examples/01-strangler-proxy/.../StranglerProxyRoute.java` (append shipping
  flag/branch to the existing `.choice()` — do not rewrite), `application.properties` (flag + base-url).
  Single writer of the proxy. Tests mirroring the existing pattern
  (`ShippingFlagOnProfile`/`ShippingFlagOffProfile` + `ShippingRouteFlagOnTest`/`...OffTest` +
  `StubBackendServer`).
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (`camel_route_context`, `camel_validate_route`,
  `camel_render_route_diagram`).
- **(e) Deps / parallel:** after S3 (contract) + S5 (service). May overlap S6.
- **(f) Collision risk:** med — single writer of proxy route + properties (shares the file with
  Review/Notification/Inventory/Payment flags; append, don't rewrite).
- **(g) Acceptance:** with the flag **false**, `/api/shipments` still reaches the monolith and the full
  suite is green through :8888; `camel_validate_route` clean; a Citrus/route test proves the full
  `/api/shipments` prefix match and transparent pass-through (and documents the no-translator decision).
- **(h) Tier:** Sonnet. No Opus gate (proof is S8).
- **(i) Checkpoint commit:** `feat(strangler): shipping flag + /api/shipments route (transparent reverse proxy; ACL honesty — no translator, DTO identical)`

## S8 — CUTOVER: flip both flags; equivalence green across the seam; reversibility; negative check  *(SEQUENTIAL, after S6 + S7)*  **[Opus gate — equivalence gate]**  — **HARD PARTS H2/H3**
- **(b) Goal / DoD:** Flip **both** flags together (DRQ-065): monolith `shipping.mode=orchestrated`
  (fulfilment owned by the shipping service's saga) + proxy `strangler.shipping.enabled=true` (reads
  served by the shipping service). Enable the S2 shipping-failure + Shipping Context Contract folders.
  Run the **full equivalence suite through the proxy** (:8888): **Scenario 1 bounded-waits to
  `CONFIRMED`** across the longer chain, **Scenario 4 (shipping-failure) bounded-waits to
  `SHIPPING_FAILED` + inventory net-zero via the orchestrated compensation** (the crux, H3),
  **Scenario 2/3 unchanged**, plus the Shipping Context Contract folder. Demonstrate **reversibility**
  (flip both back → in-process confirm+dispatch on `payment.captured`, still green). Run the **negative
  check**: disable the Camel Saga `.compensation(...)` ⇒ a forced `SHIP-FAIL` leaves stock decremented
  and the order not `SHIPPING_FAILED` ⇒ Scenario 4 goes **RED** (proves the orchestrated compensation
  is genuinely exercised, DRQ-065); and confirm a stopped shipping consumer ⇒ order stuck
  `AWAITING_SHIPMENT` ⇒ Scenario 1 bounded-wait RED.
- **(c) Creates/touches:** flag config only (`shipping.mode`, `strangler.shipping.enabled`); a
  `demos/demo-shipping-cutover.sh`; evidence appended to a new `examples/06-shipping-service/CUTOVER.md`.
- **(d) Skills/MCP:** **camel-mcp** (`camel_runtime_*` to confirm routing + the saga route topology),
  Newman re-run, Kafka consumer-lag/topic inspection to show the saga flow.
- **(e) Deps / parallel:** after S6 + S7. SEQUENTIAL.
- **(f) Collision risk:** touches both flag locations; no code rewrite.
- **(g) Acceptance:** suite green through the proxy in the cutover state (Scenario 1 → CONFIRMED via the
  saga, Scenario 4 → SHIPPING_FAILED + stock net-zero via the orchestrated compensation, Scenario 2/3
  unchanged, Shipping read folder); reversibility demonstrated; **negative check RED** when the saga
  compensation is disabled (and when the shipping consumer is down). **[Opus gate — equivalence gate]:**
  human/Opus signs off that the orchestrated scenarios are genuinely satisfied across the seam —
  especially that a shipping failure ends with stock net-zero via the coordinator-initiated
  compensation, not a residual decrement, and that the happy path reaches CONFIRMED only after
  `shipment.dispatched`.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(shipping): flag-gated cutover to orchestrated saga; equivalence green (bounded-wait CONFIRMED + SHIPPING_FAILED + net-zero), reversibility + negative check`

## S9 — DECOMMISSION the monolith in-process shipping path  *(SEQUENTIAL, after S8)*  **[Opus gate]**
- **(b) Goal / DoD:** Make the shipping service the **sole owner** of shipping: remove the monolith's
  `ShippingController`/`ShippingService`/`Shipment`/`ShipmentStatus`/`ShipmentDto`/`ShipmentRepository`
  and the `shipping.mode` flag (orchestrated becomes the only path); the monolith no longer serves
  `/api/shipments` and no longer dispatches in-process. **Remove the in-process
  `shippingService.dispatch(...)` call** from `OrderSagaListener#onPaymentCaptured` (now the order
  simply moves to `AWAITING_SHIPMENT` and the saga drives the rest). Update `SMELLS.md` — **SMELL[ch.22]
  ACID→ACD now realized for shipping** too (the in-process shipping dispatch no longer rides any
  checkout/payment transaction; strike or annotate smell #3's shipping clause), and smell #1's
  `shipments→orders` cross-context FK row (the FK is decomposed and the table retired from the shared
  schema — recommend keep-and-stop-writing as write-only history, same treatment as
  reviews/notifications/inventory_items/payments). Update `SixContextsSmokeTest`.
  `strangler.shipping.enabled=true` becomes the committed default.
- **(c) Creates/touches:** `examples/00-monolith/**` (remove shipping module + flag + in-process
  dispatch call; a Flyway migration if the monolith `shipments` table is retired — recommend
  keep-and-stop-writing), `SMELLS.md`, `SixContextsSmokeTest.java`,
  `examples/01-strangler-proxy/application.properties` (committed default true). Lane M + proxy.
- **(d) Skills/MCP:** none new; Newman re-run; `mvn -f examples/00-monolith clean verify`.
- **(e) Deps / parallel:** after S8. SEQUENTIAL.
- **(f) Collision risk:** monolith (lane M) + proxy — single writer each. (A decommission `rm` may be
  blocked by the permission classifier — expect a human-action step if a delete is refused.)
- **(g) Acceptance:** `GET :8080/api/shipments` → 404; checkout still dispatches via the saga (shipping
  service owns fulfilment); the in-process dispatch is gone (only the saga + its compensation drive
  shipping); monolith `clean verify` green; full suite green through the proxy; `SMELLS.md` SMELL[ch.22]
  marked realized for shipping with evidence. **[Opus gate]:** decommission reviewed; no orphaned
  in-process dispatch path; no double path (in-process + saga).
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(shipping): decommission monolith shipping module + in-process dispatch; shipping service is sole owner; ACID->ACD realized for shipping (SMELL[ch.22])`

## S10 — Code-CI: shipping equivalence gate (Kafka + shipping + payment + inventory + monolith) red-then-green  *(SEQUENTIAL, after S9)*  **[Opus gate]**
- **(b) Goal / DoD:** Add a `shipping-equivalence-gate` job to `.github/workflows/code-ci.yml` (sibling
  to review/notification/inventory/payment gates) that brings up Postgres + Kafka + the **shipping
  service** + the **payment service** + the **inventory gRPC service** + the monolith + the strangler
  proxy, then runs the **full** behavior-equivalence suite through the proxy — including the longer
  Scenario 1 bounded-wait and the new Scenario 4 (shipping-failure) — and **fails on non-zero Newman
  exit**. Prove the gate truly gates with a **red-then-green**: disable the Camel Saga
  `.compensation(...)` (so a `SHIP-FAIL` leaves stock decremented) ⇒ Scenario 4 net-zero goes RED;
  restore ⇒ green. Also record the consumer-down negative check (order stuck `AWAITING_SHIPMENT` ⇒
  Scenario 1 RED). **Cross-service cascade (learned from ch.23 S10b):** every checkout-driven gate
  (`payment-equivalence-gate`, and any sibling running the full suite) now also needs the **shipping
  service** up, because checkout's terminal `CONFIRMED` now depends on `shipment.dispatched`; extend
  those jobs' topology (add the shipping service + a consumer-group-stabilization wait) so they do not
  silently 500 / hang on the longer chain.
- **(c) Creates/touches:** `.github/workflows/code-ci.yml` (add the shipping gate + extend the sibling
  full-suite gate topology; do not fork a workflow). Isolated.
- **(d) Skills/MCP:** **lgtm-github** (GitHub Actions conventions). Reuses S2 suite + S5/S6 artifacts.
- **(e) Deps / parallel:** after S9. SEQUENTIAL.
- **(f) Collision risk:** low — single workflow file.
- **(g) Acceptance:** workflow green on push/PR with the orchestrated saga exercised end-to-end; the
  deliberate break (no saga compensation) makes Scenario 4 RED (recorded), then green; the sibling
  gates still green with the shipping service added (no cascade regression). **[Opus gate]:** Opus
  confirms the job genuinely exercises the orchestrated confirm + shipping-failure compensation across
  the seam (not a green-only race — bounded-waits with real timeouts), Kafka/the saga consumer is
  healthy before the suite runs, and the cross-service cascade is handled.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `ci(r07.x): shipping equivalence gate — orchestrated saga, confirm + shipping-failure compensation (red-then-green); extend sibling gates for the shipping dependency`

## S11 — ch.24 diagram(s)  *(PARALLEL, after S5)*
- **(b) Goal / DoD:** Paired **SVG + `.excalidraw`** figures: (1) the **orchestrated-saga sequence**
  (payment.captured → shipping saga: enrich → dispatch → book carrier → shipment.dispatched → order
  CONFIRMED; abort → compensate: cancel shipment + shipment.failed → order SHIPPING_FAILED + Release);
  (2) the **orchestration-vs-choreography contrast** (ch.23's no-coordinator reactions vs ch.24's one
  Camel Saga coordinator that owns the sequence — side by side, echoing datamesh `13-orchestration-
  styles.md`); (3) the **Camel Saga compensation flow** (the `.saga()/.compensation()/.option()/
  .timeout()` shape and the coordinator-initiated reverse-order rollback). House style, catalogued.
  Follow the existing descriptive-name convention (e.g. `shipping-orchestrated-saga-sequence`,
  `orchestration-vs-choreography`, `shipping-saga-compensation-flow`) — no numeric chapter prefix
  (matching the ch.23 deviation note), tagged `ch.24` in the catalogue's chapter column.
- **(c) Creates/touches:** `assets/diagrams/shipping-*.svg` + `.excalidraw` (+ `.py` spec importing
  `_lib.py`); append rows to `assets/diagrams/README.md` (serialize this catalogue — one writer).
- **(d) Skills/MCP:** **lgtm-diagram-generator**; optionally `camel_render_route_diagram` as a reference
  for the saga route shape.
- **(e) Deps / parallel:** after S5 (saga shape known); PARALLEL with S12.
- **(f) Collision risk:** low per-SVG; serialize `assets/diagrams/README.md`.
- **(g) Acceptance:** SVGs render; `.excalidraw` sources present; catalogue updated with ch.24 rows.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(§24): orchestrated-saga sequence, orchestration-vs-choreography, and Camel Saga compensation diagrams (SVG + excalidraw)`

## S12 — ch.24 ADLC trace capture  *(PARALLEL, after S9 evidence exists)*
- **(b) Goal / DoD:** Capture the **"ADLC in Action"** trace for ch.24 as pre-captured, narrated tool
  output (DRQ-025): the `migrate-spring-to-quarkus` run (S4), the Camel Saga route validation
  (`camel_validate_route` / `camel_catalog_eip_doc saga`, S5), the SmallRye consumer + outbox + saga
  abort-path validation (S5), the equivalence-gate + negative-check + shipping-failure compensation
  proof (S8), both human gates, and the DRQ-056…065 entries. The `_plans/` ledger is Exhibit A.
- **(c) Creates/touches:** captured transcripts under `_docs/_adlc-traces/` (e.g.
  `24-shipping-orchestrated-saga-trace.md`), reusing the established callout template.
- **(d) Skills/MCP:** **lgtm-tutorial** (callout format); evidence sourced from S4–S10 runs (captured,
  not re-run live).
- **(e) Deps / parallel:** needs S9 evidence; PARALLEL with S11.
- **(f) Collision risk:** low; coordinate callout reuse with S13.
- **(g) Acceptance:** a complete Frame→Map→Plan→Generate→Verify→Operate→Reconcile trace exists as
  checked-in narrated output; both gates + equivalence + negative-check + the shipping-failure
  compensation proof visible.
- **(h) Tier:** Sonnet. Opus gate folded into S13.
- **(i) Checkpoint commit:** `docs(§24): ADLC-in-Action trace for the shipping orchestrated-saga extraction`

## S13 — ch.24 authored to the full bar  *(SEQUENTIAL, after S9 + S11 + S12)*  **[Opus gate — 2k + footer]**
- **(b) Goal / DoD:** Chapter 24 ("Extraction 5 — Shipping Service (Orchestrated Saga)", per
  build-plan §B.2, Part 7 "Coordinating Across Services") authored to the full bar: **≥2000 words excl.
  code/diagrams**, progressive, referencing the runnable `examples/06-shipping-service/` (the Camel Saga
  EIP orchestrator) + the monolith order-saga reactions + the wired transparent proxy route; the S11
  diagrams embedded; a real **"ADLC in Action" callout** (S12); a **verification-status footer** naming
  the tests/demos run (equivalence suite incl. the longer Scenario 1 + the new Scenario 4 across the
  seam, the dispatch/compensate/idempotent round-trip tests, the saga abort-path unit test, the
  negative check, Citrus route test, Dev Services, native). Must teach: **choreography vs orchestration
  made concrete on one codebase** (back-ref ch.23's DRQ-048/049 choreography; the coordinator now lives
  in the shipping service, readable top-to-bottom), the **Camel Saga EIP** shape
  (`.saga()/compensation/option/timeout`, `InMemorySagaService` and why not LRA yet, DRQ-056/057/059),
  **coordinator-initiated cross-context compensation** (DRQ-059/060), the **new order lifecycle**
  (AWAITING_SHIPMENT/SHIPPING_FAILED, CONFIRMED-on-dispatched, DRQ-058/061), **ACID→ACD realized for
  shipping** (SMELL[ch.22] cashed in), **idempotency/at-least-once** (DRQ-064), and **how the
  equivalence suite stayed honest across the longer chain + new terminal outcome** (DRQ-065). Fill in
  the existing stub `_docs/24-extraction-5-shipping-service.md` (do NOT create a second `_docs/24-*.md`
  — the stub already holds `order: 24`). Only *adds/edits* that `_docs` file (no `_config.yml`/`_parts/`
  edits).
- **(c) Creates/touches:** `_docs/24-extraction-5-shipping-service.md` (replace the "Coming soon" body;
  keep front matter `title`/`order: 24`/`part: "Coordinating Across Services"`/`description`; add
  `duration`).
- **(d) Skills/MCP:** **lgtm-tutorial** (authoring + static validation) + **lgtm-jekyll** (build/word-count).
- **(e) Deps / parallel:** after S9 (behavior final), S11 (diagrams), S12 (callout). SEQUENTIAL.
- **(f) Collision risk:** low (single existing `_docs` file edited in place).
- **(g) Acceptance:** lgtm-jekyll/lgtm-tutorial validation green: **word count ≥2000**, example-dir
  present, verification footer present, links/diagrams resolve, no duplicate `order: 24`. **[Opus gate]:**
  Opus confirms the 2k-with-running-code bar, that the orchestration-vs-choreography + Saga-EIP +
  cross-context-compensation teaching is honest, and the callout is authentic.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `docs(§24): author Extraction 5 — Shipping (orchestrated saga, Camel Saga EIP) to the full bar`

## S14 — Reconcile, status, exit validation  *(SEQUENTIAL, last)*  **[Opus gate]**
- **(b) Goal / DoD:** Append r07/ch.24 outcomes to the ledger: `reconciliation.md` updated (artifact→
  source drift incl. the datamesh shipping-service adaptation — faithfully note the choreography→Saga-EIP
  re-shape as the named divergence; zero unexplained drift, R4); `build-plan.md` §E row 5 marked DONE +
  the §J/status row for ch.24; `decisions.md` version matrix updated (shipping-service
  `camel-quarkus-saga` + `quarkus-messaging-kafka` versions, reused Kafka broker tag). The ch.24 EXIT
  CHECKLIST (below) verified; clean resume boundary for the **order + GraphQL gateway extraction
  (ch.26 — the last/hardest, CQRS, monolith decommissioned)** recorded.
- **(c) Creates/touches:** `_plans/reconciliation.md`, `_plans/build-plan.md` (status), `_plans/decisions.md`
  (matrix). Serialize all three.
- **(d) Skills/MCP:** none new; lgtm-jekyll full-site validation; re-run the equivalence suite once.
- **(e) Deps / parallel:** after all. SEQUENTIAL.
- **(f) Collision risk:** the three `_plans/*` are single-writer — only S1 and S14 write them in r07.
- **(g) Acceptance:** exit checklist all-green; matrix updated; status current. **[Opus gate]:** Opus
  signs off the whole extraction.
- **(i) Checkpoint commit:** `docs(r07.x): reconcile ch.24, update version matrix, mark shipping orchestrated-saga extraction DONE`

---

## ch.24 EXIT CHECKLIST (equivalence green across the orchestrated seam + ACID→ACD realized for shipping)
- [ ] **Orchestration decided & applied (DRQ-056/057):** shipping is a bounded orchestrated saga on
      `payment.captured`, coordinated by a Camel Saga EIP route (`InMemorySagaService`) in
      `examples/06-shipping-service`; whole-flow re-expression and LRA both considered and rejected/
      deferred with rationale.
- [ ] **Saga coordinator genuinely sequences + compensates (DRQ-059):** `.saga()` with
      `.compensation(...)/.option(...)/.timeout(...)`; happy path dispatches + emits
      `shipment.dispatched`; abort/timeout invokes compensation (cancel shipment + emit
      `shipment.failed`) exactly once; validated via camel-mcp + a saga abort-path unit test.
- [ ] **Equivalence green across the seam — honestly:** Scenario 1 (longer bounded-wait → CONFIRMED),
      Scenario 4 (bounded-wait → SHIPPING_FAILED + inventory net-zero via the orchestrated
      compensation), Scenario 2/3 unchanged, plus the Shipping Context Contract folder; assertions
      strictly assert terminal status + side-effects (not vacuous).
- [ ] **Cross-context compensation proven net-zero (DRQ-060):** a forced `SHIP-FAIL` ends with stock
      net-zero, driven by the coordinator's compensation → `shipment.failed` → order `SHIPPING_FAILED`
      + gRPC `Release`; not an in-process path.
- [ ] **Order lifecycle updated (DRQ-058/061):** order reaches `CONFIRMED` only on `shipment.dispatched`
      (via `AWAITING_SHIPMENT`), and `SHIPPING_FAILED` on `shipment.failed`; external async contract
      (DRQ-047) unchanged.
- [ ] **Shipping owns its data & fulfilment over Kafka:** the Quarkus service owns its schema, consumes
      `payment.captured`, runs the saga, and emits `shipment.dispatched`/`shipment.failed` via its own
      transactional outbox; sole owner after decommission (`GET :8080/api/shipments` → 404).
- [ ] **ACID→ACD realized for shipping (SMELL[ch.22]):** in-process shipping dispatch no longer rides
      any checkout/payment transaction; SMELLS.md updated with evidence.
- [ ] **Idempotency / at-least-once handled (DRQ-064):** redelivered `payment.captured` does not create
      a second shipment / re-run the saga; redelivered `shipment.dispatched`/`shipment.failed` are
      no-ops; `Release` only on the first `shipment.failed`.
- [ ] **Two-phase honored (DRQ-063):** read surface Phase A (spring-compat) → Phase B (idiomatic),
      measured; saga/consumer/producer idiomatic-from-start; `Shipment` FK decomposed to `orderId` value.
- [ ] **Reversibility shown (DRQ-065)** before decommission (both flags off → in-process confirm+dispatch
      restored, green).
- [ ] **Negative check proven (DRQ-065):** Camel Saga compensation disabled ⇒ forced `SHIP-FAIL` leaves
      stock decremented / order not failed ⇒ Scenario 4 RED; shipping consumer down ⇒ order stuck
      `AWAITING_SHIPMENT` ⇒ Scenario 1 RED.
- [ ] **Code-CI green:** the shipping equivalence gate exercises the orchestrated saga end-to-end in
      GitHub Actions (red-then-green via disabling the saga compensation); sibling gates still green with
      the shipping dependency added (cross-service cascade handled).
- [ ] **ch.24 authored ≥2000 words**, runnable example, embedded diagrams, real "ADLC in Action" callout,
      verification-status footer; choreography-vs-orchestration taught concretely.
- [ ] **Ledger reconciled:** `decisions.md` DRQ-056…065 accepted + version matrix updated; §E row 5 DONE;
      this plan's steps and exit checklist marked DONE.

## Biggest risks
1. **(Highest) The orchestrated compensation silently fails or diverges (H3/DRQ-059/060).** The
   compensation is coordinator-initiated and spans shipping→Kafka→order→gRPC with no request thread; if
   the Camel Saga coordinator doesn't actually invoke compensation on abort/timeout, or the
   `shipment.failed` reaction is missed/not idempotent, or the `Release` fails, stock stays decremented
   after a shipping failure and Scenario 4 goes RED — or drifts silently. Mitigation: baseline Scenario 4
   green vs the monolith (S2); a saga abort-path unit test + `camel_validate_route` (S5); idempotent
   order reaction issuing `Release` only on first `shipment.failed` (S6/DRQ-064); S8 forces `SHIP-FAIL`
   and bounded-waits stock to net-zero; S10 proves it red-then-green by disabling the saga compensation.
2. **The Camel Saga EIP coordinator looks wired but never compensates (H1).** The subtle failure mode is
   a route that completes "successfully" even on a thrown exception (wrong `completionMode`, exception
   swallowed before the saga boundary, `InMemorySagaService` bean not registered). Mitigation: ground the
   DSL in `camel_catalog_eip_doc saga` (confirmed options), validate the route, and make the abort-path
   unit test a hard acceptance criterion in S5 — the coordinator's compensation must be *observed*, not
   assumed.
3. **A dishonest/too-weak equivalence suite hides the longer chain or the new outcome (H2/DRQ-065).** A
   too-short Scenario 1 budget goes falsely RED; a relaxed Scenario 4 goes falsely GREEN. Mitigation: the
   *terminal* status + net-zero are asserted strictly via bounded-wait; only the transport/latency is
   tolerant; the negative check proves non-vacuity; S2 is an explicit `[Opus gate]`.
4. **The order-lifecycle change regresses the in-process baseline (H4).** Moving CONFIRMED one hop later
   and adding two new consumers + states risks breaking the still-in-process default. Mitigation:
   everything new is gated behind `shipping.mode=orchestrated`; with the flag off the full suite stays
   byte-for-byte green (asserted in S6).
5. **In-memory saga state is not crash-durable (DRQ-057).** A shipping-service restart mid-saga loses
   in-flight coordinator state (an in-flight saga may neither complete nor compensate). Mitigation:
   documented honest limitation (parallel to ch.23's "no saga ledger"); LRA named as the distributed,
   durable alternative (ch.25/ch.28); the `.timeout(...)` bounds stuck sagas in the common case; not in
   r07 scope to solve.
6. **Cross-service CI cascade (learned from ch.23 S10b).** Once checkout's terminal `CONFIRMED` depends on
   the shipping service, every full-suite gate silently needs it up or will hang/500. Mitigation: S10
   extends the sibling gates' topology with the shipping service + a consumer-stabilization wait, and
   records the cascade.

## Resume boundary for ch.26 (order + GraphQL gateway — the last/hardest extraction)
ch.26 resumes from the `build-plan.md` §E status table (row 6) and this plan's evidence. With shipping
extracted, **five of six** contexts are out of the monolith (review, notification, inventory, payment,
shipping); only the **order** god-aggregate remains. ch.26 extracts it last (the hardest: it is the
coordinator of the whole checkout flow and the holder of the reserved-line snapshot the saga
compensations depend on), introduces **CQRS** + a **GraphQL gateway** as the read-aggregation surface,
and **decommissions the monolith**. What ch.26 inherits from ch.24, ready to build on:
- **Both saga styles now exist on one codebase** — choreographed (payment, DRQ-048/049) and orchestrated
  (shipping, Camel Saga EIP, DRQ-056/059). ch.26 decides how the extracted order aggregate relates to
  both coordinators (it currently hosts the order-saga reactions for all of them).
- **The order-context reaction machinery** (`OrderSagaListener`: `onPaymentCaptured`/`onPaymentDeclined`/
  `onShipmentDispatched`/`onShipmentFailed`, the status guards, `RemoteInventoryClient` compensating
  `Release`) — this is exactly the surface ch.26 lifts into the extracted order service.
- **The event topology** — `order.placed` → `{payment.captured,payment.declined}` →
  `{shipment.dispatched,shipment.failed}` all flow through Kafka with `common/Topics.java` as the
  registry; ch.26 extends it rather than reinventing transport.
- **Decided honest limitations to revisit** — in-memory saga durability (DRQ-057, LRA) and
  JSON-not-Avro (DRQ-038) remain cross-referenced to ch.28 (contracts/registry); LRA durability may also
  be revisited in ch.25 (resilience).
