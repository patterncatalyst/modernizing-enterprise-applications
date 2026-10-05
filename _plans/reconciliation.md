---
title: "Reconciliation Log — Modernizing Enterprise Applications"
description: "Append-only artifact→source reconciliation log (build-plan.md §M, R4). Tracks every reused/adapted artifact against its source (datamesh-reference-arch-quarkus, CNDP, prior in-project chapters) with a verdict, so reuse drift is always named, never silent. Updated at each extraction's reconcile (S14/S16) step."
status: "live — appended at each extraction's reconcile step"
---

# Reconciliation Log

Append-only. One section per extraction/chapter's reconcile pass. For each
reused or adapted artifact: the source, what was reused, what diverges
(project-specific), and a verdict. `R4` (`build-plan.md` §M) requires **zero
unexplained drift** — divergence is fine as long as it is named here and
traceable to an accepted `DRQ-NNN`.

## First creation note (r06/ch.23 S14)

This file did not exist before this reconcile pass, even though `decisions.md`
DRQ-007 and `build-plan.md` §M (R4) / §L.2 both name it as a required ledger
artifact. Review (ch.15, r02), Notification (ch.17, r04), and Inventory (ch.19,
r05) were reconciled instead via inline `— DONE` annotations in their own
`_plans/iterations/{notification,inventory}-plan.md` files plus the
`decisions.md` DRQ append — no separate reconciliation file was produced for
them. Backfilled below at summary level from their own already-committed
`README.md`/`MIGRATION.md`/`SMELLS.md` evidence (no new investigation implied,
no code touched); full per-artifact granularity starts with ch.23 payment,
per this step's scope.

## ch.15 — Review (r02, extraction 1 of 6)

Deliberately the simple walking-skeleton slice (DRQ-032): REST-only, no
synchronous collaborators, no event consumer/producer. No
`datamesh-reference-arch-quarkus` pattern was reused for Review specifically —
its role per DRQ-032 is to stay minimal, not to showcase a Quarkus strength.
Verdict: no drift to reconcile (nothing reused from an external source).

## ch.17 — Notification (r04, extraction 2 of 6)

Source: `~/Dev/datamesh-reference-arch-quarkus/examples/notification-service`.
Reused: the `quarkus-websockets-next` push shape (`OrderNotificationSocket`,
`@OnOpen` handshake ack) and the open-connections fan-out helper. Named
divergence: JSON event payloads, not Avro (DRQ-038 — Apicurio deferred to
ch.28); the Kafka consumer (`OrderPlacedPushConsumer`) is idempotent-by-design
to match this project's at-least-once outbox relay (DRQ-034/037), a concern
datamesh's version did not need to carry the same way. Documented in
`examples/03-notification-service/README.md` and `MIGRATION.md`. Verdict:
clean adaptation, divergence already named at the time (DRQ-035/038).

## ch.19 — Inventory (r05, extraction 3 of 6)

No external-source reuse is called out in `examples/04-inventory-service`'s
own docs (gRPC server + CDC backfill were authored for this project's
specific seam). Debezium Connect pinned at `docker.io/debezium/connect:3.0.0.Final`,
transition-only, retired at S11 (DRQ-040). Verdict: no unexplained drift;
no datamesh artifact claimed as source for this extraction.

---

## ch.23 — Payment (r06, extraction 4 of 6) — choreographed saga

### Datamesh payment-service adaptation (DRQ-032)

Source: `~/Dev/datamesh-reference-arch-quarkus/examples/payment-service`
(`PaymentProcessor.java`, `PaymentStore.java`).

**Reused as-is (shape):**
- The reactive-messaging choreography shape: a processor that is
  `@Incoming(order.placed)` and reacts by capturing and emitting a payment
  outcome event — mirrored by `examples/05-payment-service`'s
  `OrderPlacedConsumer`/`PaymentService#processOrderPlaced`.
- The idempotent-by-`orderId` capture guard: datamesh's `PaymentProcessor`
  javadoc states "capture is idempotent on the order id — a redelivery
  returns the payment already captured... instead of minting a second one";
  this project implements the identical guarantee via the
  `uq_payments_order_id` unique index (DRQ-051).
- The service owning its own payment store (datamesh's `PaymentStore` ↔ this
  project's `Payment` entity/`PaymentRepository`), not a shared schema.

**Named divergence (project-specific, each traceable to an accepted DRQ —
not unexplained drift):**
- **Decline branching.** Datamesh's `PaymentProcessor` javadoc explicitly
  scopes out decline branching ("every order is captured immediately... that
  branching is out of scope for this capstone slice"). This project's
  `PaymentService#charge` implements `CARD-DECLINE` and emits
  `payment.declined` (DRQ-048) — required because Scenario 3 / choreographed
  compensation (DRQ-049) is ch.23's entire point.
- **Own transactional outbox, not a direct `@Outgoing` channel.** Datamesh
  emits `PaymentCaptured` directly as the `@Outgoing` return value of the
  same method that consumes `OrderPlaced` (single reactive-messaging hop, no
  outbox). This project routes the emit through `PaymentOutboxEvent` +
  `PaymentOutboxRelay` (mirrors DRQ-034) so the capture/decline write and the
  event-to-be-published are atomic in one local transaction (DRQ-053) —
  direct-emit risks losing the event on a post-commit crash, which this
  project's "no dual-write" discipline (stated since ch.17) does not accept.
- **JSON, not Avro/Apicurio.** Datamesh serializes `OrderPlaced`/
  `PaymentCaptured` as Avro against an Apicurio schema registry. This project
  defers Avro/Apicurio to ch.28 (DRQ-038); `payment.captured`/
  `payment.declined` are plain JSON POJOs (`PaymentCaptured.java`,
  `PaymentDeclined.java`).
- **Separate `payment.declined` topic.** Datamesh has no decline path or
  topic; `payment.declined` is net-new to this project's `common/Topics.java`
  (DRQ-048).
- **Two-phase read surface.** Datamesh's payment-service has no Spring
  original to lift (no read-surface migration concern at all); this
  project's `/api/payments` follows DRQ-029 Phase A → Phase B (DRQ-052) —
  a concern that simply does not arise in the datamesh source.

**Verdict:** CLEAN adaptation. The reused *shape* (idempotent
choreography processor over its own store) is faithfully carried over; every
divergence from the datamesh source is driven by a named, already-accepted
project decision (DRQ-038/048/049/051/052/053), not undocumented drift.

### Other ch.23 artifact → source lines

- **Saga compensation machinery (`OrderSagaListener`)** — adapted from this
  project's own ch.19 in-line `catch` compensation (DRQ-042), re-plumbed per
  DRQ-049. Source is in-project (ch.19), not an external reference.
- **Equivalence-suite bounded-wait / negative-check technique** — reused
  from ch.17's DRQ-037 pattern, extended to the checkout path (DRQ-055).
  Source is in-project (ch.17), not external.
- **Kafka broker (`apache/kafka:3.8.0`)** — reused unchanged from the
  `.env.example` `KAFKA_IMAGE_TAG` already pinned for ch.17/ch.19.
  **No new infra container was added for ch.23** — contrast ch.19's
  Debezium/Kafka Connect, which was transition-only and retired at S11.
- **`quarkus-messaging-kafka` / Quarkus platform BOM** — `3.40.1`, the same
  version already pinned for notification-service (DRQ-035); no version
  drift introduced by payment-service.

**Verdict: zero unexplained drift (R4) for r06/ch.23.**

### Process deviations from `payment-plan.md` (recorded honestly, per S14)

1. **S11 diagram naming.** The plan's S11 goal named the output
   `assets/diagrams/23-*.svg`. The diagrams actually shipped
   (`payment-choreographed-saga-sequence`, `checkout-sync-async-contract`,
   `payment-compensation-choreography`) use descriptive names with no numeric
   chapter prefix, matching the existing catalogue convention already in use
   for every other diagram (`monolith-architecture`,
   `transactional-outbox-sequence`,
   `inventory-reserve-compensation-sequence`, etc. — see
   `assets/diagrams/README.md`). Each is tagged `ch.23` in the catalogue's
   own chapter column instead of the filename. Deviation: naming convention
   only; no content/scope gap.
2. **S13 chapter filename.** The plan's S13(c) specified
   `_docs/23-choreographed-saga-payment.md`. A pre-existing "Coming soon"
   stub, `_docs/23-saga-and-extraction-4-payment.md` (matching the sibling
   `NN-...-extraction-N-...` naming convention used by ch.17/ch.19), already
   held `order: 23` in its front matter. To avoid a duplicate-`order`
   collision between two files both claiming position 23, the authored
   chapter was consolidated onto the stub's existing filename rather than
   the plan's literal name. Verified: `_docs/23-saga-and-extraction-4-payment.md`
   exists with `order: 23`; no second `_docs/23-*.md` file was created.
3. **Inserted step S2b (suite-gap fix, commit `28e62ab`, Opus-gated).** The
   S8 live cutover surfaced two gaps the S2 baseline had not actually
   delivered: (a) a missing "Payment Context Contract" Newman folder
   (`/api/payments` shape assertions), and (b) a stale "Notification Context
   Contract / 5a" assertion still encoding the synchronous-checkout
   assumption, now invalid under the bounded-wait contract (DRQ-055). S2b
   added the missing folder and adapted the stale assertion, then
   re-baselined the collection green (99 assertions, 0 failed) before
   cutover proceeded. Not in the original plan's step list; inserted
   in-sequence between S2 and S8's dependents.
4. **Inserted step S10b (cross-gate cascade fix, commit `1585166`).** S10's
   own `payment-equivalence-gate` passed, but running it exposed that the
   pre-existing `inventory-equivalence-gate` and `notification-equivalence-gate`
   jobs began failing: post-S9 choreographed checkout requires the payment
   service to be up and reach a terminal state for `order.placed` requests
   those gates also exercise, and those jobs' CI topology had not been
   updated for payment's new dependency. S10b added the payment-service
   dependency plus a consumer-group-stabilization wait to both sibling gate
   jobs in `.github/workflows/code-ci.yml`. This is the documented
   cross-service CI cascade: an extraction's cutover can silently widen the
   blast radius of already-green CI jobs, and the fix belongs with the
   extraction that caused it, not deferred.

**Note on scope:** per this step's instructions, only `_plans/decisions.md`,
`_plans/build-plan.md`, and this file were edited to record the above.
`_plans/iterations/payment-plan.md` itself (including its own step
annotations and its EXIT CHECKLIST checkboxes) was intentionally **not**
edited — the verification below stands in for marking that file's checklist,
without touching a file outside the three-ledger scope given for this step.

### ch.23 EXIT CHECKLIST — verified item by item against committed reality

Source: `_plans/iterations/payment-plan.md`, "ch.23 EXIT CHECKLIST" section.
Code CI is confirmed GREEN (all four gates: `equivalence-gate`,
`notification-equivalence-gate`, `inventory-equivalence-gate`,
`payment-equivalence-gate`); every Opus gate in this run (S6, S8, S9, S10,
S2b, S13) returned GO.

1. **Sync→async contract decided & applied (DRQ-047).** SATISFIED.
   `POST /api/orders` returns `202` + `PENDING` + `Location`; terminal
   `CONFIRMED`/`PAYMENT_DECLINED` observed via polling
   `GET /api/orders/{id}`; the synchronous `402` is gone. Evidence: S6
   `ccf08eb`, S8 `38cbdf0` CUTOVER.md ("`202 Accepted`, body `status:
   PENDING`" for both Scenario 1 and 3), S9 `d053fc2`.
2. **Equivalence green across the seam, honestly.** SATISFIED. CUTOVER.md
   records Scenario 1 (bounded-wait 6 attempts → `CONFIRMED`), Scenario 3
   (bounded-wait 8 attempts → `PAYMENT_DECLINED` + a second bounded-wait to
   inventory net-zero), Scenario 2 unchanged synchronous `409`, plus the
   Payment Context Contract folder — three full suite runs recorded (89/90/85
   assertions, 2 pre-existing unrelated failures each run, both load-bearing
   checkout scenarios green every run). Evidence: `examples/05-payment-service/CUTOVER.md`.
3. **Scenario 3 compensation via choreography proven (DRQ-049).** SATISFIED.
   CUTOVER.md documents the forced `CARD-DECLINE` checkout, the
   `payment.declined` → `OrderSagaListener#onPaymentDeclined` → compensating
   gRPC `Release` path, and explicit before/after stock numbers showing
   net-zero; the ch.19 in-line catch compensation is removed for the
   choreographed path (SMELLS.md smell #3, S9 `d053fc2`).
4. **Payment owns its data & the capture over Kafka.** SATISFIED.
   `examples/05-payment-service` owns the `payment` schema, consumes
   `order.placed`, captures, and emits via its own transactional outbox
   (`PaymentOutboxEvent`/`PaymentOutboxRelay`); SMELLS.md confirms the
   monolith's `payment.Payment` JPA entity was deleted at S9 and
   `GET :8080/api/payments` → 404 post-decommission.
5. **ACID→ACD realized for payment (SMELL[ch.22]).** SATISFIED. SMELLS.md
   smell #3 entry is explicit and detailed: the one in-process
   `@Transactional` spanning payment no longer exists for the choreographed
   path; the saga + compensation rebuild cross-context consistency
   explicitly, with the remaining local transaction narrowed to the order
   context's own work. The smell is correctly left un-struck-through overall
   (shipping/order still pending — the honest ch.24/ch.26 resume boundary).
6. **Idempotency / at-least-once handled (DRQ-051).** SATISFIED. Payment
   consumer dedupes by `orderId` (`uq_payments_order_id`); order-saga
   reaction guards the status transition (no-op once left `PENDING`); Release
   issued only on first decline. Evidence: S5 `c782f65`, S6 `ccf08eb`.
7. **Two-phase honored (DRQ-052).** SATISFIED. Phase A spring-compat lift
   (S4 `0c99f2d`) → Phase B idiomatic Quarkus REST+Panache (S5 `c782f65`,
   `examples/05-payment-service/MIGRATION.md`); consumer/producer idiomatic
   from the start (no Spring original existed to lift).
8. **Reversibility shown (DRQ-054).** SATISFIED before decommission: both
   flags (`payment.mode`, `strangler.payment.enabled`) flipped back during
   S8 cutover, synchronous `201`/`402` contract re-asserted green, before S9
   removed the flag entirely.
9. **Negative check proven (DRQ-055).** SATISFIED per CUTOVER.md's negative-
   check cycle — payment consumer down ⇒ order stuck `PENDING` ⇒ Scenario 1
   bounded-wait RED; decline-with-no-reaction ⇒ Scenario 3 net-zero RED. This
   is the same discipline S10's CI gate also proves red-then-green.
10. **Code-CI green.** SATISFIED. `payment-equivalence-gate` exercises the
    choreographed saga end-to-end in GitHub Actions, red-then-green via
    disabling the compensation reaction (S10 `8d175ed`); the S10b fix
    (`1585166`) restored the two sibling gates to green after the
    cross-service cascade. All four gates confirmed GREEN as of this run.
11. **ch.23 authored ≥2000 words**, runnable example, embedded diagrams, real
    "ADLC in Action" callout, verification-status footer. SATISFIED. S13
    `04f031f`; `_docs/23-saga-and-extraction-4-payment.md`
    (see S13-filename deviation above); ADLC trace
    `_docs/_adlc-traces/23-payment-choreographed-saga-trace.md` (S12
    `4fb7539`); diagrams embedded (S11 `b9ff2de`).
12. **Ledger reconciled.** SATISFIED by this S14 pass: `decisions.md`
    DRQ-047…055 accepted + version matrix updated (this document's sibling
    edits); this plan's steps and exit checklist verified here (not
    hand-edited into `payment-plan.md` itself — see the scope note above).

**No checklist item was found unsatisfiable.** All twelve items (the plan's
nine plus the three "process/ledger" closing items read as one group above)
are confirmed against committed evidence.

### Resume boundary for ch.24 (shipping — orchestrated saga)

ch.24 resumes from `build-plan.md` §E's decomposition-roadmap status column
(row 5, "not started") and this reconciliation's evidence. This
**choreographed** saga (ch.23) is the deliberate contrast ch.24 builds
against: shipping will complete the chain using an **orchestrated** saga via
the **Camel Saga EIP** (cross-referenced against DataMesh's
`_docs/13-orchestration-styles.md`). What ch.24 inherits from ch.23, ready to
build on without re-deriving:
- **The event topology** — `order.placed` → `{payment.captured,
  payment.declined}` already flows through Kafka with `common/Topics.java`
  as the registry; ch.24 adds shipping's own events onto the same topology
  rather than inventing a new transport.
- **The payment-service outbox-emit pattern** (`PaymentOutboxEvent`/
  `PaymentOutboxRelay`, DRQ-053) — the reusable "own transactional outbox,
  no dual-write" shape a shipping-service emit can mirror directly.
- **The order-context reaction-consumer + compensation machinery**
  (`OrderSagaListener`, DRQ-049/050/051) — the monolith is already a Kafka
  consumer with idempotent, status-guarded reactions; ch.24's orchestrator
  will coordinate shipping dispatch/compensation against this same
  machinery rather than building a parallel one.

ch.26 later extracts the order aggregate itself (CQRS/GraphQL) and
decommissions the monolith; ch.28 replaces this seam's JSON events with
Apicurio-registered Avro/Protobuf (cross-referenced in ch.23's own prose, per
`payment-plan.md`).

## R4 status summary

| Extraction | Chapter | Zero unexplained drift? | Evidence |
|---|---|---|---|
| Review | ch.15 | yes (no external source reused) | `examples/02-review-service/README.md` |
| Notification | ch.17 | yes | `examples/03-notification-service/README.md`, `MIGRATION.md` |
| Inventory | ch.19 | yes (no external source reused) | `examples/04-inventory-service/MIGRATION.md`, `SMELLS.md` |
| Payment | ch.23 | **yes** — this reconcile pass | this document, this section |
