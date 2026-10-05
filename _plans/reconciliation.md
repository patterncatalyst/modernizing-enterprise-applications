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

## ch.24 — Shipping (r07, extraction 5 of 6) — orchestrated saga

### Datamesh shipping-service adaptation (DRQ-032)

Source: `~/Dev/datamesh-reference-arch-quarkus/examples/shipping-service`,
cross-referenced against `_docs/13-orchestration-styles.md` (choreography vs.
two orchestration shapes over the same shipping/order domain).

**Reused as-is (shape):**
- The idiomatic Quarkus shipping service owning its own `shipment` table/
  schema — no cross-DB FK, `orderId` carried as a plain value — mirrored by
  `examples/06-shipping-service`'s `Shipment` entity/repository (FK
  decomposition, DRQ-063).
- The reactive-messaging consumer-of-an-upstream-event shape (datamesh's
  shipping-service reacts to an upstream order/payment event) — mirrored by
  `@Incoming(payment.captured)` in `ShippingSagaRoute`/the saga consumer.
- The service-owns-its-fulfilment-data posture (no shared schema, no
  reach-back into the order aggregate's tables) — mirrored exactly; the saga's
  *enrichment* step reads the order context only via its public REST read
  surface (`OrderReadClient GET /api/orders/{id}`), never its database.

**Named divergence — the deliberate reshape (choreography → Camel Saga EIP
coordinator), each traced to an accepted DRQ, not unexplained drift:**
- **Orchestration, not choreography.** Datamesh's `shipping-service` (per
  `13-orchestration-styles.md`) is a pure choreography participant: it reacts
  to an event and emits its own, with no central coordinator. This project
  reshapes the same domain into an **orchestrated** saga — a single Camel
  `.saga()` route in the shipping service that explicitly sequences
  *enrich → dispatch → book carrier → emit* and owns the decision to
  compensate (DRQ-056/059) — the deliberate contrast to ch.23's choreographed
  payment saga, teaching both styles on one codebase (build-plan §C "Saga —
  Orchestrated (D) → ch.24").
- **A named coordinator (`InMemorySagaService`).** Datamesh has no saga
  coordinator object at all (choreography needs none). This project
  introduces `camel-quarkus-saga`'s `InMemorySagaService` as a CDI bean the
  `.saga()` DSL looks up, with `.compensation(...)/.option(...)/.timeout(...)`
  (DRQ-057/059) — net-new machinery with no datamesh precedent; `LRASagaService`/
  Narayana was considered and deferred (new infra container, contra the
  no-new-container discipline since DRQ-048).
- **Compensation is coordinator-initiated and cross-context, not a peer
  reaction.** Datamesh's choreography has no compensation step for shipping at
  all (out of scope for that capstone slice, same framing as payment's
  decline-branching gap, DRQ-048). This project's coordinator decides to
  compensate on any abort/timeout, cancels the local shipment, and **delegates**
  the cross-context undo (`shipment.failed` → order context issues the gRPC
  `Release`) because the reserved-line snapshot lives on the order aggregate,
  not in shipping (DRQ-060) — a decision datamesh's model never had to make.
- **PENDING-then-atomic-flip persistence, not direct-then-emit.** To jointly
  satisfy DRQ-059 (the saga shape)/DRQ-062 (the `SHIP-FAIL` injection point
  must fire *after* the `Shipment` row exists, so both compensations are
  exercised)/DRQ-063 (own transactional outbox, no dual-write), the *dispatch*
  step persists `Shipment` as **`PENDING`** first, then a single
  `@Transactional` step flips `PENDING`→`DISPATCHED` + inserts the outbox row
  atomically (and, on compensation, `PENDING`→`CANCELLED` + `shipment.failed`
  atomically) — a reinterpretation of DRQ-059's literal two-step wording,
  decided at S5, with no datamesh equivalent (datamesh has no outbox, no
  saga-compensation race to guard against).
- **JSON, not Avro/Apicurio; own transactional outbox, not a direct
  `@Outgoing`.** Same divergence as payment's (DRQ-038 Avro deferred to ch.28;
  DRQ-053-style outbox mirrored as `ShipmentOutboxEvent`/`ShipmentOutboxRelay`,
  DRQ-063) — datamesh emits directly with Avro/Apicurio; this project does
  neither, for the same already-accepted reasons.
- **A new failure-bearing topic (`shipment.failed`) and two new order states
  (`AWAITING_SHIPMENT`/`SHIPPING_FAILED`).** Datamesh's shipping-service has no
  failure topology to contrast against; this project adds both because the
  orchestrated contrast *requires* a provable failure/compensation path
  (DRQ-058/061, the H3/H4 crux this extraction exists to teach).
- **Two-phase read surface.** As with payment, datamesh's shipping-service has
  no Spring original to lift; this project's `/api/shipments` still follows
  DRQ-029 Phase A (spring-compat, S4) → Phase B (idiomatic, S5) — a concern
  that does not arise in the datamesh source (DRQ-063).

**Verdict:** CLEAN adaptation. The reused *shape* (an idiomatic, own-schema
Quarkus service, event-driven fulfilment) is faithfully carried over; the
reshape from datamesh's pure choreography to an orchestrated Camel Saga EIP
coordinator is the extraction's entire deliberate point, and every other
divergence is driven by a named, already-accepted project decision
(DRQ-038/056/057/058/059/060/061/062/063), not undocumented drift.

### Other ch.24 artifact → source lines

- **Order-context reaction/compensation machinery (`OrderSagaListener`)** —
  extended, not re-derived, from ch.23's `onPaymentCaptured`/`onPaymentDeclined`
  + `RemoteInventoryClient` compensating `Release` (DRQ-042/049); S6 adds
  `onShipmentDispatched`/`onShipmentFailed` onto the identical idempotent,
  status-guarded reaction shape. Source is in-project (ch.23), not external.
- **Equivalence-suite bounded-wait / negative-check technique** — reused from
  ch.17/ch.23's DRQ-037/DRQ-055 pattern, extended to the longer orchestrated
  chain and the new Scenario 4 (DRQ-065). Source is in-project, not external.
- **Kafka broker (`apache/kafka:3.8.0`)** — reused unchanged from
  `.env.example`'s `KAFKA_IMAGE_TAG`, already pinned for ch.17/ch.19/ch.23.
  **No new infra container was added for ch.24** — `InMemorySagaService` is
  co-located in-JVM; `LRASagaService`/Narayana deferred (DRQ-057).
- **`quarkus-messaging-kafka` / Quarkus platform BOM** — `3.40.1`, the same
  version already pinned for notification/payment-service (DRQ-035/052); no
  version drift introduced by shipping-service. `camel-quarkus-saga` pinned to
  the matching Camel-on-Quarkus extension version for that BOM.

**Verdict: zero unexplained drift (R4) for r07/ch.24.**

### Process deviations from `shipping-plan.md` (recorded honestly, per S14)

1. **Port reassignment `:8086`→`:8088` (S2, `da82287`).** The plan's framing
   text named the shipping service `:8086`, but `:8086` is already bound on
   this machine's compose stack as the **host-side** mapping for Debezium
   Kafka Connect's REST API (`CONNECT_HOST_PORT`, `mea-connect`, pinned since
   ch.19/ch.23 — the payment-service `application.properties` already carries
   the identical "NOT 8086" comment for the same reason). S2 reassigned the
   shipping service to `:8088` and threaded the new port through
   `examples/06-shipping-service/application.properties`,
   `examples/01-strangler-proxy/application.properties` (+ its README),
   `tooling/newman/shipping-service.postman_environment.json`, and
   `_plans/iterations/shipping-plan.md` itself. Deviation: port number only;
   no scope/behavior gap. Confirmed via `grep -rn 8086` across the repo —
   every remaining `:8086` reference is the Debezium Connect host mapping,
   correctly cross-referenced, not a stray shipping-service mention.
2. **Inserted de-flake commit (`04aaf86`, between S8 and S9).** Live S8
   cutover surfaced that Notification folder **5c**'s confirmation-notification
   bounded-wait had kept its original 10×500ms budget from ch.17/ch.23 while
   Scenario 1 and Notification **5b** were widened to 20×750ms at S2 for the
   extra Kafka hop (`order.placed→payment.captured→shipping saga→
   shipment.dispatched→CONFIRMED→notification`); under the longer orchestrated
   chain, 5c flaked roughly 1-in-5 runs. The fix widened 5c's budget to match
   5b/1c — **budget-only**, the assertion (`eql('CONFIRMED')`-style terminal
   check) is byte-for-byte unchanged and still goes RED on a never-arriving
   notification. Verified green across 4 cutover runs plus the S2 baseline.
   Not a separate plan step; inserted in-sequence immediately after S8.
3. **`newman --env-var` vs. collection-scoped variable finding (S8,
   `eceedc1`).** The plan's S2 gated the new Scenario 4 (shipping-failure)
   folder behind a `shippingSagaEnabled` flag, intending it to be flipped on
   at cutover via Newman's `--env-var` CLI flag. Live cutover discovered
   `--env-var` can only set an **environment**-scoped variable, not the
   **collection**-scoped `shippingSagaEnabled` the folder's `disabled`/
   `setNextRequest` logic actually reads — so the proposed enable mechanism
   would have silently left Scenario 4 skipped (a false-green cutover on
   exactly the extraction's crux scenario). Resolved with
   `demos/lib/run-shipping-newman.js`, a small Node helper that patches the
   collection JSON in-memory before invoking `newman run`, correctly flipping
   the collection variable. This helper is used by **both**
   `demos/demo-shipping-cutover.sh` (S8) **and** the S10 CI gate
   (`ea97733`) — a single fix point, not duplicated logic.
4. **S10 cross-service cascade fix folded into S10 itself (not a separate
   step, contrast ch.23's S10b).** Having learned from ch.23's S10b (a
   follow-up commit after S10 discovered the payment-service dependency had
   not been threaded into sibling gates), S10 (`ea97733`) proactively added
   the shipping-service dependency to the payment-, inventory-, and
   notification-equivalence-gate jobs' CI topology **in the same commit** that
   added `shipping-equivalence-gate` — because post-S9, every checkout-driven
   gate's terminal `CONFIRMED` assertion now depends on `shipment.dispatched`.
   Also raised the consumer-group-stabilization wait from 2→4 brokers-ready
   checks (`OrderSagaListener` now owns four `@KafkaListener`s) and added a
   shipping-consumer settle wait. Verified locally red-then-green: 134/134
   green → disable `.compensation(...)` → Scenario 4 RED (2/47, stuck
   `AWAITING_SHIPMENT`, stock not restored) → revert byte-for-byte → 133/133
   green; consumer-down → Scenario 1 RED. No ch.24-equivalent of a separate
   "S10b" commit exists because the cascade was anticipated and folded in.
5. **S5 PENDING-reinterpretation of DRQ-059 (`0f0b345`).** DRQ-059's literal
   wording describes *dispatch shipment* (persist `Shipment` DISPATCHED) as one
   step and *emit `shipment.dispatched`* (via the outbox) as a later one. At
   S5, satisfying all three of DRQ-059 (the saga shape)/DRQ-062 (the
   `SHIP-FAIL` injection must fire **after** the `Shipment` row is persisted,
   so *both* compensations — cancel shipment and emit `shipment.failed`→
   release — are reproducibly exercised)/DRQ-063 (own transactional outbox, no
   dual-write) simultaneously required reinterpreting "persist DISPATCHED" as
   two sub-steps: *dispatch* persists `Shipment` as **`PENDING`** first
   (so a `SHIP-FAIL` thrown immediately after has a row to cancel), then a
   single `@Transactional` method atomically flips `PENDING`→`DISPATCHED` **and**
   inserts the outbox row (and, symmetrically, the compensation route
   atomically flips `PENDING`→`CANCELLED` **and** inserts the
   `shipment.failed` outbox row). This resolves what the S5 commit message
   calls the "DRQ-059/062/063 tension" without contradicting any of the three
   — the externally-observable happy-path and compensation outcomes described
   by DRQ-059 are unchanged; only the intermediate persisted state gained a
   name (`PENDING`). Documented in the S5 commit and
   `examples/06-shipping-service/MIGRATION.md`.

**Note on scope:** per this step's instructions, only `_plans/decisions.md`,
`_plans/build-plan.md`, this file, and `_plans/iterations/shipping-plan.md`'s
EXIT CHECKLIST were edited to record the above.
`_plans/iterations/shipping-plan.md`'s own step-by-step prose (S1–S13) was
intentionally **not** rewritten — the verification below stands in for
marking those steps complete, mirroring the ch.23 precedent of not
retroactively editing step narration outside the three-ledger + checklist
scope given for this step.

### ch.24 EXIT CHECKLIST — verified item by item against committed reality

Source: `_plans/iterations/shipping-plan.md`, "ch.24 EXIT CHECKLIST" section.
Code CI is confirmed GREEN (all **five** gates: `equivalence-gate`,
`notification-equivalence-gate`, `inventory-equivalence-gate`,
`payment-equivalence-gate`, `shipping-equivalence-gate`); every Opus gate in
this run (S2, S4, S5, S6, S8, S9, S10, S13) returned GO.

1. **Orchestration decided & applied (DRQ-056/057).** SATISFIED. Shipping is
   a bounded orchestrated saga on `payment.captured`, coordinated by a Camel
   Saga EIP route (`InMemorySagaService`) in `examples/06-shipping-service`;
   whole-flow re-expression (option b) and `LRASagaService`/Narayana both
   considered and rejected/deferred with rationale (shipping-plan.md
   "orchestration decision" section; DRQ-056/057 realized S5 `0f0b345`).
2. **Saga coordinator genuinely sequences + compensates (DRQ-059).**
   SATISFIED. `.saga().propagation(REQUIRES_NEW).completionMode(AUTO)
   .timeout(15s).compensation("direct:ship-compensate").option("orderId",...)`;
   happy path dispatches + emits exactly one `shipment.dispatched`; abort/
   timeout invokes compensation exactly once (Camel saga SPI, S5 commit
   message); validated via `camel_validate_route` (clean) plus an
   AdviceWith/MockEndpoint abort-path unit test (16 tests green, S5
   `0f0b345`).
3. **Equivalence green across the seam — honestly.** SATISFIED. S8
   `examples/06-shipping-service/CUTOVER.md` records Scenario 1 (bounded-wait
   to `CONFIRMED` across the longer chain), Scenario 4 (bounded-wait to
   `SHIPPING_FAILED` **and** inventory net-zero, real numbers
   496→495→496 / 478→477→478), Scenario 2/3 unchanged, plus the Shipping
   Context Contract folder — proxy proven to reach `:8088` byte-identical to
   direct. S10 CI (`ea97733`) reproduces the same 134/134 (then 133/133
   post-revert) green. Assertions strictly assert terminal status +
   net-zero, not "any 2xx" (S2 Opus gate confirmed this before any code was
   built).
4. **Cross-context compensation proven net-zero (DRQ-060).** SATISFIED. A
   forced `SHIP-FAIL` ends with stock net-zero, driven by the coordinator's
   compensation → `shipment.failed` → `OrderSagaListener#onShipmentFailed` →
   order `SHIPPING_FAILED` + gRPC `Release`, not an in-process path (S6
   `973bc19`, S8 CUTOVER.md real-number evidence, S10 red-then-green
   `ea97733`).
5. **Order lifecycle updated (DRQ-058/061).** SATISFIED. Order reaches
   `CONFIRMED` only on `shipment.dispatched` (via `AWAITING_SHIPMENT`), and
   `SHIPPING_FAILED` on `shipment.failed` (S6 `973bc19`); the external async
   contract (DRQ-047, `202`+`PENDING`+`Location`) is unchanged (S2 baseline,
   S8 CUTOVER.md).
6. **Shipping owns its data & fulfilment over Kafka.** SATISFIED.
   `examples/06-shipping-service` owns the `shipment` schema, consumes
   `payment.captured`, runs the saga, and emits via its own transactional
   outbox (`ShipmentOutboxEvent`/`ShipmentOutboxRelay`); S9 (`19575f0`)
   deleted the monolith's shipping module entirely — `GET :8080/api/shipments`
   → 404, confirmed sole owner.
7. **ACID→ACD realized for shipping (SMELL[ch.22]).** SATISFIED.
   `examples/00-monolith/SMELLS.md` smell #3's entry explicitly documents the
   shipping portion cashed in at r07/ch.24 S9: the in-process
   `shippingService.dispatch(...)` call and the `shipping.mode` flag are both
   gone; fulfilment is owned end-to-end by the saga. Smell #3 remains
   correctly un-struck-through overall (order/ch.26 is the final resume
   boundary).
8. **Idempotency / at-least-once handled (DRQ-064).** SATISFIED. Saga
   consumer dedupes by `orderId` (`uq_shipments_order_id`, S5 `0f0b345`,
   verified via an idempotent-redelivery test through the real `@Incoming`
   pipeline); order-context reactions guard the status transition (no-op once
   left `AWAITING_SHIPMENT`); `Release` issued only on the first
   `shipment.failed` (S6 `973bc19`).
9. **Two-phase honored (DRQ-063).** SATISFIED. Phase A spring-compat lift (S4
   `4950070`) → Phase B idiomatic Quarkus REST+Panache + saga/consumer/
   producer idiomatic-from-start (S5 `0f0b345`,
   `examples/06-shipping-service/MIGRATION.md`, measured 1.519s/289MB/15feat
   → 2.002s/364MB/21feat); `Shipment` FK decomposed to an `orderId` value
   (S4).
10. **Reversibility shown (DRQ-065).** SATISFIED before decommission: both
    flags (`shipping.mode`, `strangler.shipping.enabled`) flipped back during
    S8 cutover, in-process confirm+dispatch re-asserted green, before S9
    removed the flag entirely (CUTOVER.md "Reversibility" section).
11. **Negative check proven (DRQ-065).** SATISFIED. CUTOVER.md's negative-
    check cycle: Camel Saga compensation disabled ⇒ forced `SHIP-FAIL` leaves
    stock decremented and the order stuck `AWAITING_SHIPMENT` (reverted
    byte-for-byte) ⇒ Scenario 4 RED; shipping consumer down ⇒ order stuck
    `AWAITING_SHIPMENT` ⇒ Scenario 1 bounded-wait RED. S10's CI gate
    (`ea97733`) reproduces both red-then-green.
12. **Code-CI green.** SATISFIED. `shipping-equivalence-gate` exercises the
    orchestrated saga end-to-end in GitHub Actions, red-then-green via
    disabling the saga compensation (S10 `ea97733`); the cross-service
    cascade fix for the sibling gates was folded into the same S10 commit
    (process deviation #4 above). All **five** gates confirmed GREEN as of
    this run.
13. **ch.24 authored ≥2000 words**, runnable example, embedded diagrams, real
    "ADLC in Action" callout, verification-status footer. SATISFIED. S13
    `89ea367`; `_docs/24-extraction-5-shipping-service.md` (title
    "Orchestrated Sagas & Extraction 5 — Shipping (Camel Saga EIP)",
    `order: 24`), measured **4,531 words** excl. code/diagrams/front-matter
    (well over the 2k bar); ADLC trace
    `_docs/_adlc-traces/24-shipping-orchestrated-saga-trace.md` (S12
    `4c7e079`); diagrams embedded (S11 `9e05dc7`).
14. **Ledger reconciled.** SATISFIED by this S14 pass: `decisions.md`
    DRQ-056…065 verified present/accepted + the shipping-service version
    matrix row verified present (both added at S1, confirmed unduplicated
    here) + `build-plan.md` §E row 5 / §J flipped to DONE (this document's
    sibling edits); this plan's EXIT CHECKLIST flipped below.

**No checklist item was found unsatisfiable.** All fourteen items (the plan's
twelve plus the two "process/ledger" closing items read as one group above)
are confirmed against committed evidence.

### Resume boundary for ch.26 (order + GraphQL gateway — the last/hardest extraction)

ch.26 resumes from `build-plan.md` §E's decomposition-roadmap status column
(row 6, "not started") and this reconciliation's evidence, and is now **r08**
per the cadence reconciled at ch.24/r07 S1 (build-plan.md §J). Five of six
contexts are now out of the monolith (review, notification, inventory,
payment, shipping); only the **order** god-aggregate remains
(SMELLS.md smell #2). ch.26 is deliberately **last and hardest**: it must
decompose the single orchestration point for checkout (`OrderService`),
introduce **CQRS** + a **SmallRye GraphQL gateway** as the read-aggregation
surface across the now-five extracted services, and **cash in the final
clause of SMELL[ch.22]'s ACID→ACD story** — the one remaining local
`@Transactional` (order persistence + the outbox write) — while
**decommissioning the monolith entirely**. What ch.26 inherits from both
payment (ch.23) and shipping (ch.24), ready to build on without re-deriving:
- **Both saga styles now exist on one codebase** — choreographed (payment,
  DRQ-048/049) and orchestrated (shipping, Camel Saga EIP, DRQ-056/059). ch.26
  decides how the extracted order service relates to both coordinators (it
  currently *hosts* the order-saga reactions for all four Kafka topics:
  `payment.captured`/`payment.declined`/`shipment.dispatched`/
  `shipment.failed`).
- **The order-context reaction + compensation machinery**
  (`OrderSagaListener`: `onPaymentCaptured`/`onPaymentDeclined`/
  `onShipmentDispatched`/`onShipmentFailed`, the status guards,
  `RemoteInventoryClient` compensating `Release`) — this is exactly the
  surface ch.26 lifts into the extracted order service, unmodified in shape.
- **The event topology** — `order.placed` →
  `{payment.captured,payment.declined}` → `{shipment.dispatched,
  shipment.failed}` all flow through Kafka with `common/Topics.java` as the
  registry; ch.26 extends it (and becomes the producer of `order.placed`
  from outside the monolith) rather than reinventing transport.
- **The outbox-emit pattern** (`PaymentOutboxEvent`/`PaymentOutboxRelay`,
  `ShipmentOutboxEvent`/`ShipmentOutboxRelay`) — the reusable "own
  transactional outbox, no dual-write" shape the extracted order service's
  own `order.placed` emit can mirror directly.
- **Decided honest limitations to revisit** — in-memory saga durability
  (DRQ-057, LRA) and JSON-not-Avro (DRQ-038) remain cross-referenced to ch.28
  (contracts/registry); LRA durability may also be revisited in ch.25
  (resilience, not yet started).

## R4 status summary

| Extraction | Chapter | Zero unexplained drift? | Evidence |
|---|---|---|---|
| Review | ch.15 | yes (no external source reused) | `examples/02-review-service/README.md` |
| Notification | ch.17 | yes | `examples/03-notification-service/README.md`, `MIGRATION.md` |
| Inventory | ch.19 | yes (no external source reused) | `examples/04-inventory-service/MIGRATION.md`, `SMELLS.md` |
| Payment | ch.23 | yes | this document, ch.23 section |
| Shipping | ch.24 | **yes** — this reconcile pass | this document, this section |
