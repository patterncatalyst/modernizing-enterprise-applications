---
title: "Notification Extraction Plan — ch.17 (the second strangler extraction, event-driven)"
description: "Concrete, ordered, executable step plan for the Notification service extraction: cure SMELL #4 (synchronous in-transaction notification) by introducing a transactional OUTBOX + polling relay in the monolith that publishes order.placed to Kafka, and a new Quarkus notification service (examples/03-notification-service) that CONSUMES it via SmallRye Reactive Messaging and owns its own data — flag-gated cutover behind the Camel strangler proxy, equivalence-gated with bounded-wait eventual-consistency assertions. Mirrors the r02 walking-skeleton plan and the Review extraction template."
status: "execution plan — planning only; nothing built, scaffolded, or pushed until the user approves"
iteration: r04
chapter: 17
depends_on:
  - _plans/build-plan.md            # §E row 2 (notification = outbox + async Kafka consumer), §G (equivalence gate)
  - _plans/iterations/r02-plan.md    # the walking-skeleton template this mirrors
  - examples/00-monolith/SMELLS.md   # SMELL #4 (the thing cured)
  - examples/02-review-service/MIGRATION.md  # the Phase A→B two-phase template
  - examples/01-strangler-proxy/CUTOVER.md   # the flag-cutover + decommission template
references:
  - "~/Dev/datamesh-reference-arch-quarkus/examples/notification-service (DRQ-032 non-trivial pattern)"
---

# ch.17 — Notification Service Extraction (execution plan)

> **Status: COMPLETE.** All steps S1–S13 executed and reconciled; EXIT CHECKLIST
> below is all-green. Commit range `3b6e538..a5fda78` on branch
> `r02-walking-skeleton` (not the `r04-notification-extraction` branch originally
> named below — the work landed on the active walking-skeleton branch instead).
>
> **PLANNING ONLY.** This file is the Opus "Plan" phase (ADLC §F.1) and must be
> **user-approved before any code is written**. Nothing is built, scaffolded, or
> pushed until approval.
>
> **Branch for all work:** `r04-notification-extraction` (off `main`).
> **Total steps:** 13 (S1 … S13).
> **Relay tiering (DRQ-004):** every step is *executed* by **Sonnet**; steps marked
> **[Opus gate]** additionally require an **Opus validation** pass before their
> checkpoint commit. Steps marked **[Opus gate — equivalence gate]** are the ADLC
> Verify human sign-off points (§F.1).

## What this extraction delivers (from build-plan §E row 2, §C, SMELL #4)
Cure **SMELL #4** — the order-confirmation notification sent *synchronously, in-process,
inside the checkout `@Transactional`* (`order/OrderService.java#placeOrder` →
`notification/NotificationService.java#sendOrderConfirmation`) — by replacing it with:

1. A **transactional outbox** in the monolith: `placeOrder` writes an `order.placed`
   event row to an `outbox` table **inside the same checkout transaction** (atomic with
   the order), and a **simple scheduled polling relay** publishes unpublished rows to
   Kafka (topic `order.placed`, the name already reserved in `common/Topics.java`).
2. A new **Quarkus notification service** at **`examples/03-notification-service`** that
   **consumes** `order.placed` via **SmallRye Reactive Messaging** (`@Incoming`),
   idempotently persists a notification into **its own schema/database**, exposes the
   `/api/notifications` read surface, and (adapting datamesh) a `/ws/notifications`
   WebSocket push. Built two-phase for the *lifted read surface*; the *consumer is
   idiomatic-from-the-start* (net-new, nothing to lift — see DRQ-035).
3. The **Camel strangler proxy** gains a `strangler.notification.enabled` flag routing
   `/api/notifications` to the new service; the monolith's synchronous path and read
   surface are **decommissioned** once equivalence holds.
4. The **behavior-equivalence suite** still passes: the existing checkout folders are
   **unchanged and stay green** (notification was never in the checkout response), and a
   **new "Notification Context Contract" folder** asserts the async notification via a
   **bounded-wait eventual-consistency poll** so the *same* collection passes against both
   the synchronous monolith and the async service (DRQ-037).
5. Tests at every tier + an equivalence-gate run in CI, with honest handling of eventual
   consistency.

## Decisions seeded by this plan (append to `_plans/decisions.md`, next free IDs after DRQ-033)
- **DRQ-034 — Outbox relay: simple polling publisher, NOT Debezium CDC.** ch.17 teaches
  the transactional outbox with a `@Scheduled` relay that selects unpublished `outbox`
  rows and publishes them to Kafka. **Why:** build-plan §C deliberately homes
  **Debezium/CDC at ch.19 (inventory)**, not ch.17; the polling relay is the simplest
  mechanism that teaches the outbox *guarantee* honestly (atomic write + at-least-once
  publish) without pulling Kafka-Connect / a replication slot into the podman stack a
  chapter early. **Tradeoff (stated in-chapter):** polling adds publish latency (poll
  interval) and steady DB read load, and *requires* idempotent consumption + ordering
  care; CDC (ch.19) reads the WAL — lower latency, no app poll, no table-scan — at the
  cost of connector/replication-slot operational complexity. **ch.20 ("Outbox, done
  right") revisits** dedup/ordering; **ch.19** introduces CDC as the alternative.
- **DRQ-035 — Two-phase for the lifted read surface; consumer idiomatic-from-start.**
  DRQ-029's two-phase template (Phase A spring-compat lift → Phase B idiomatic) applies
  to the part with a Spring original: `NotificationController` +
  `NotificationService.listByCustomerId` + `Notification` entity/repo. The **Kafka
  consumer has no Spring original** (the monolith never consumed events), so it is
  authored **idiomatic Quarkus from the start** — you cannot "lift" code that does not
  exist. This is the honest reading of DRQ-029 ("two-phase *if a Spring original is
  lifted*").
- **DRQ-036 — Reversibility via two flags.** Write side: a monolith config flag
  `notification.mode = synchronous|outbox` (default `synchronous`). Read side: the proxy
  flag `strangler.notification.enabled` (default `false`). Cutover flips **both
  together**; reversible until the decommission step (mirrors Review's CUTOVER.md).
- **DRQ-037 — Equivalence under async: bounded-wait polling + a negative check.** The
  new Notification Context Contract folder polls `/api/notifications?customerId=` up to N
  bounded retries after a happy-path checkout. Written this way it passes immediately
  against the synchronous monolith (baseline) and after a short delay against the async
  service — one collection, both backends, unchanged. A **negative check** (stop the
  consumer → assertion must go RED) proves the assertion truly exercises the async
  pipeline and is not a false positive (the exact false-equivalence trap that bit Review
  in CUTOVER.md §2).
- **DRQ-038 — JSON serialization at ch.17; Avro+Apicurio deferred to ch.28.** The event
  is serialized as JSON over Kafka; the podman stack stays unchanged (no Apicurio in
  r04). datamesh's notification-service uses **Avro + Apicurio registry**; we adopt that
  at **ch.28 (Contracts & the Service Registry)**, cross-referenced in-chapter. Topic
  `order.placed` / channel `order-placed`.

## Standing constraints applied to every step
Scope discipline (nothing here an r04 ch.17 deliverable doesn't require — no Apicurio, no
Debezium, no saga, no second topic); conceptual coherence; security-by-design (OWASP/CIS
— secrets hygiene, secure-by-default Camel dynamic-URI allow-list, no creds in git);
**SIMPLE git only — `git -C <dir> …`, never `cd && git`**; **never push beyond
`github.com/patterncatalyst/modernizing-enterprise-applications` without explicit user
permission**; Conventional Commits (`feat`/`fix`/`docs`/`chore`/`refactor`/`ci`/`test`/
`site` + scopes `rNN.x`, `§NN`, service names); **NO attribution trailers**. A subagent
does **not** inherit a loaded skill — each executor prompt must explicitly invoke/read the
named skill.

## Parallelism overview
```
S1 (frame + branch + decisions, SEQUENTIAL, must be first)
S2 equivalence suite: add Notification folder + baseline-green vs monolith   [Opus gate]
 ├─ S3 monolith OUTBOX + relay (modifies examples/00-monolith) ─┐ (lane M)   [Opus gate]
 └─ S4 new svc scaffold + Phase A read-surface lift ────────────┘ (lane N — disjoint dir)
S5 Phase B idiomatic + SmallRye consumer (+ WebSocket push)  (SEQ after S4; needs S3 to e2e-test) [Opus gate]
S6 strangler proxy: notification flag + route            (SEQ after S4)
S7 CUTOVER: flip both flags, equivalence green, reversibility shown (SEQ after S3+S5+S6) [Opus gate — equivalence gate]
S8 DECOMMISSION monolith sync path + read surface        (SEQ after S7)      [Opus gate]
S9 Code-CI: extend equivalence-gate job (Kafka + svc)    (SEQ after S8)      [Opus gate]
 ├─ S10 ch.17 diagram(s) ───┐   (PARALLEL after S5)
 └─ S11 ch.17 ADLC trace ───┘   (PARALLEL after S8 evidence exists)
S12 ch.17 authored to the bar  (SEQ after S8 + S10 + S11)                    [Opus gate — 2k + footer]
S13 reconcile + status + exit  (SEQ, last)                                   [Opus gate]
```
**Single-writer / serialize (§K):** the monolith reactor `pom.xml` + `OrderService` +
Flyway migrations (S3, S8 — lane M, one writer); `examples/01-strangler-proxy/`
`application.properties` + `StranglerProxyRoute.java` (S6, S7); `tooling/newman/
mea.postman_collection.json` (S2 — versioned with the monolith); the three `_plans/*`
ledgers (S1, S13); `_config.yml`/`_parts/`/CSS untouched (chapters only *add* `_docs/17-*.md`).

---

## THE HARD PARTS (called out explicitly, per the brief)

**H1 — The outbox change modifies `examples/00-monolith` (S3/S8).** This is the first
time the monolith's *checkout write path* changes, not just a context being peeled off.
`placeOrder`'s `@Transactional` must atomically write the order **and** an outbox row; the
synchronous `notificationService.sendOrderConfirmation(...)` call is removed (gated by the
`notification.mode` flag for reversibility). Adds `spring-kafka` + Spring `@Scheduled` and
a Flyway `V3__outbox.sql` to the monolith — a new dependency surface in the "before"
artifact. Collision-critical: lane M is the *only* writer of the monolith reactor root in
r04. Risk: a buggy outbox write could regress the checkout contract — mitigated by S2's
unchanged checkout folders gating every S3 commit.

**H2 — Async / eventual consistency breaks naive equivalence (S2/S7).** The monolith
writes the notification synchronously (present the instant checkout returns 201); the
extracted path writes it *eventually* (after poll → publish → consume). A synchronous
"GET /api/notifications right after checkout" assertion would be **flaky against the
service and give a false pass against the monolith**. Cured by DRQ-037: a **bounded-wait
poll** (retry up to N × delay) that is correct against *both* backends, plus a **negative
check** (consumer stopped ⇒ RED) so we never ship the Review-style false positive
(CUTOVER.md §2: the suite stayed green while testing the wrong backend). The existing
checkout folders (Scenario 1–3) are **not touched** — their contract is genuinely
unchanged because notification was never in the checkout response.

**H3 — Kafka wiring on BOTH sides (S3 publisher + S5 consumer).** Monolith side:
`spring-kafka` producer publishing JSON `order.placed` from the relay, bootstrap
`localhost:9092` (host) per `compose.yaml`. Service side: SmallRye Reactive Messaging
`@Incoming("order-placed")`, `mp.messaging.incoming.order-placed.topic=order.placed`,
JSON deserializer, `auto.offset.reset=earliest`, `enable.auto.commit=false`. The two must
agree on topic name, JSON shape, and message key (order id). Dev Services provides Kafka
for the service's tests; the podman `mea-kafka` broker is the shared local/CI broker.

---

## S1 — Frame, branch, decisions  *(SEQUENTIAL — must be first)*  **[DONE]**
- **(b) Goal / DoD:** The ch.17 outcome + acceptance criteria are framed; the working
  branch exists off `main`; DRQ-034…038 are appended to `decisions.md`; a one-line
  build-plan status row for r04/ch.17 is added as "in progress."
- **(c) Creates/touches:** `_plans/decisions.md` (DRQ-034…038), `_plans/build-plan.md`
  (status row), checkout branch `r04-notification-extraction`. No code.
- **(d) Skills/MCP:** none (ledger authoring). `git -C <dir> checkout -b`.
- **(e) Deps / parallel:** none; SEQUENTIAL, gates everything.
- **(f) Collision risk:** `_plans/*` single-writer — this and S13 are the only ledger writers.
- **(g) Acceptance:** `git -C <dir> rev-parse --abbrev-ref HEAD` → `r04-notification-extraction`;
  DRQ-034…038 present; CDC-vs-polling + two-phase + serialization decisions written.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(r04.x): frame ch.17 notification extraction; seed DRQ-034..038 and working branch`

## S2 — Extend the equivalence suite: Notification Context Contract (baseline green vs monolith)  *(SEQUENTIAL, after S1)*  **[Opus gate]**  **[DONE — commit 3b6e538]**
- **(b) Goal / DoD:** Add a **"Notification Context Contract"** folder to the existing
  `mea.postman_collection.json` that, after a happy-path checkout, asserts a confirmation
  notification is retrievable via `GET /api/notifications?customerId=` — using a
  **bounded-wait eventual-consistency poll** (Postman `setNextRequest` retry loop, e.g.
  up to 10 × 500ms). Capture it **green against the still-synchronous monolith** (the
  baseline), proving the assertion is correct before any behavior changes. The existing
  Smoke / Scenario 1–3 / Review folders are **not edited** (H2).
- **(c) Creates/touches:** `tooling/newman/mea.postman_collection.json` (new folder only),
  `tooling/newman/notification-service.postman_environment.json` (forward-ref env on the
  service port, mirroring `review-service.postman_environment.json`); extend
  `demos/demo-equivalence.sh` docs if needed. **Collection is versioned with the monolith
  (R8); single writer.**
- **(d) Skills/MCP:** Newman (reuse CNDP App. O patterns). Runs against S3-era monolith on :8080.
- **(e) Deps / parallel:** after S1; SEQUENTIAL (must baseline before S3 changes behavior).
- **(f) Collision risk:** low (own `tooling/`), but it is the equivalence contract — changes
  here ripple to every later equivalence run.
- **(g) Acceptance:** `newman run … --folder "Notification Context Contract"` is **green
  against the unmodified monolith** on :8080; the poll loop terminates on first success
  (synchronous write ⇒ immediate hit). **[Opus gate]:** Opus confirms the assertion
  genuinely reads a notification *caused by the checkout* (right customer + order), not a
  seeded row, and that the retry loop has a bounded failure exit (goes RED, not hangs,
  when absent).
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `test(equivalence): add Notification Context Contract folder (bounded-wait), green vs monolith baseline`

## S3 — Monolith transactional OUTBOX + polling relay  *(PARALLEL lane M, after S2)*  **[Opus gate]**  — **HARD PART H1/H3**  **[DONE — commit 0263727]**
- **(b) Goal / DoD:** Within `examples/00-monolith`: a new `outbox` table (Flyway
  `V3__outbox.sql`: `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`
  (jsonb), `created_at`, `published_at` nullable); an `OutboxEvent` entity + repository in
  a new `common/outbox` (or `order/outbox`) package; `placeOrder` writes an `order.placed`
  event row **inside its existing `@Transactional`** (atomic with the order). A
  `notification.mode` flag (`synchronous` default | `outbox`) selects behavior:
  `synchronous` keeps today's `sendOrderConfirmation` call (unchanged baseline);
  `outbox` writes the outbox row and **skips** the synchronous call. A Spring
  `@Scheduled` **relay** polls unpublished rows (`published_at IS NULL`), publishes JSON
  to Kafka topic `order.placed` via `spring-kafka` (key = order id), and stamps
  `published_at`. Secure config: bootstrap from env/`application.yml`, no creds in git.
- **(c) Creates/touches:** `examples/00-monolith/pom.xml` (+`spring-kafka`),
  `src/main/resources/db/migration/V3__outbox.sql`, `.../common/outbox/OutboxEvent.java`,
  `OutboxRepository.java`, `OutboxRelay.java` (the `@Scheduled` publisher), an
  `OrderPlacedEvent` payload record, edits to `order/OrderService.java#placeOrder`
  (guarded by the flag), `application.yml` (kafka + `notification.mode`). Does **not**
  touch the service or proxy dirs.
- **(d) Skills/MCP:** Spring authored manually (per §K). Use **camel-mcp** only if a
  Camel route is chosen for the relay (not required — Spring `@Scheduled` is simplest;
  stay Spring-native here). Verify topic with the podman `mea-kafka` CLI.
- **(e) Deps / parallel:** after S2; **PARALLEL with S4** (disjoint dirs). SEQUENTIAL
  internally (single monolith reactor — one writer).
- **(f) Collision risk:** **HIGH** — lane M is the sole writer of the monolith reactor
  root, `OrderService`, and Flyway migrations in r04. No other step touches
  `examples/00-monolith` until S8.
- **(g) Acceptance:** with `notification.mode=synchronous` the **full equivalence suite
  (incl. the S2 folder) stays green** — zero regression (H1 guard). With
  `notification.mode=outbox`, placing an order writes exactly one `outbox` row atomically
  (rolls back with the order on payment-decline — assert no orphan outbox row after a
  Scenario-3 decline), and the relay publishes one `order.placed` message to
  `mea-kafka` (verified via `kafka-console-consumer`). **[Opus gate]:** Opus confirms the
  outbox write is inside the checkout transaction (not a dual-write after commit) and the
  relay is at-least-once + idempotent-friendly (stamps `published_at`; a crash between
  publish and stamp re-publishes, handled by consumer idempotency in S5).
- **(h) Tier:** Sonnet execute; **Opus validate** (checkout-path change).
- **(i) Checkpoint commit:** `feat(monolith): transactional outbox + scheduled Kafka relay for order.placed (flag-gated, default synchronous)`

## S4 — New notification service: scaffold + Phase A read-surface lift  *(PARALLEL lane N, after S1; may start with S2/S3)*  **[Opus gate]**  **[DONE — commit 0ae6b17]**
- **(b) Goal / DoD:** A new Quarkus module `examples/03-notification-service` (:8083)
  that **owns its own schema/database from day one** (contrast with Review, which stayed
  in the shared schema — the event-driven read model *must* own its store, DRQ-035). The
  monolith's Spring read surface is **lifted via Quarkiverse Spring-compat extensions**
  (`quarkus-spring-web`, `-di`, `-data-jpa`): `NotificationController` →
  `/api/notifications?customerId=`, `NotificationService.listByCustomerId`, a
  `Notification` entity + repository over its own `notifications` table (its own Flyway).
  No consumer yet (S5). Its own `notifications` table is seeded empty; populated only by
  the consumer once S5 lands.
- **(c) Creates/touches:** `examples/03-notification-service/**` (pom, `application.properties`
  on :8083 with its own datasource + its own Flyway schema, controller/service/entity/repo,
  tests), `src/main/docker/*`. Isolated subtree.
- **(d) Skills/MCP:** **quarkus-agent** MCP — `quarkus_skills` against the monolith dir to
  discover + follow **`migrate-spring-to-quarkus`** (do NOT self-plan the migration);
  `quarkus_create`/`quarkus_start`/`quarkus_searchDocs`; **lgtm-quarkus**.
- **(e) Deps / parallel:** after S1; **PARALLEL with S2/S3** (disjoint dir). SEQUENTIAL internally.
- **(f) Collision risk:** low — isolated under `examples/03-notification-service/`.
- **(g) Acceptance:** service boots on :8083 against its own DB; `GET /api/notifications?customerId=`
  returns `[]` (own empty table) or seeded rows; Spring-compat extensions present (Phase A);
  unit tests green. **[Opus gate]:** Opus confirms it owns its schema (no reach into the
  shared monolith `notifications` table) and the read contract matches the monolith's.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(notification): Phase A — lift read surface onto Quarkus via Spring-compat, own schema from day one`

## S5 — Phase B idiomatic + SmallRye Reactive Messaging consumer (+ WebSocket push)  *(SEQUENTIAL, after S4; needs S3 for e2e)*  **[Opus gate]**  — **HARD PART H3**  **[DONE — commit e1b1285]**
- **(b) Goal / DoD:** Refactor the read surface off the compat shim to **idiomatic
  Quarkus** (Quarkus REST / Panache / native CDI) — mirroring Review's Phase B — and add
  the **net-new SmallRye Reactive Messaging consumer** (idiomatic-from-start, DRQ-035):
  `@Incoming("order-placed")` + `@Transactional`, JSON `order.placed` → **idempotent**
  persist into its own `notifications` table (dedupe by order id, à la datamesh's
  `Notification.findByOrderId`), so the at-least-once relay (S3) is safe. Add the
  datamesh-style **WebSocket push** (`quarkus-websockets-next`: a second same-topic /
  unique-group consumer + `@WebSocket("/ws/notifications")`) as the non-trivial DRQ-032
  showcase (build-plan §E row 2 names "WebSockets.Next"). Capture **measured
  before/after** (startup/RSS/native) like `review-service/MIGRATION.md`.
- **(c) Creates/touches:** `examples/03-notification-service/**` — pom (remove
  spring-compat; add `quarkus-messaging-kafka`, `quarkus-hibernate-orm-panache`,
  `quarkus-rest(+jackson)`, `quarkus-websockets-next`, `quarkus-smallrye-health`),
  `application.properties` (`mp.messaging.incoming.order-placed.*`), `OrderPlacedConsumer`,
  `OrderPlacedPushConsumer`, `OrderNotificationSocket`, Panache refactor, `MIGRATION.md`,
  Citrus/`@QuarkusTest` + Dev Services tests. **Adapt from `~/Dev/datamesh-reference-arch-quarkus/
  examples/notification-service` with attribution** (but JSON not Avro — DRQ-038; and we
  DO build the outbox, which datamesh does not).
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills` for `messaging-kafka,websockets-next,
  panache`; `quarkus_searchDocs`), **lgtm-quarkus**; Dev Services for Kafka/PG in tests.
- **(e) Deps / parallel:** after S4; needs **S3** live to test end-to-end (monolith relay →
  Kafka → consumer). SEQUENTIAL.
- **(f) Collision risk:** low (same isolated module).
- **(g) Acceptance:** compat extensions gone; publishing an `order.placed` (from S3's relay
  or a test producer) results in exactly **one** persisted notification even if the event is
  delivered twice (idempotency proven); `GET /api/notifications?customerId=` returns it;
  `/ws/notifications` receives a push; before/after metrics captured (real runs, not
  placeholders). **[Opus gate]:** equivalence-relevant read contract unchanged; idempotency
  + eventual-consistency behavior verified.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(notification): Phase B — idiomatic Quarkus + SmallRye order.placed consumer (idempotent) + WebSocket push, measured`

## S6 — Strangler proxy: notification flag + content-based route  *(SEQUENTIAL, after S4; may overlap S5)*  **[DONE — folded into commit e4eb3d0]**
- **(b) Goal / DoD:** Add to `StranglerProxyRoute` a `strangler.notification.enabled` flag
  (default **false** → monolith) and `strangler.notification.base-url` (the :8083 service),
  with content-based routing on the `/api/notifications` path prefix — **exactly mirroring
  the proven Review pattern** (and heeding its CUTOVER.md bug: match on the **full**
  `/api/notifications` path, not `/notifications`). Secure-by-default: only the two fixed,
  operator-configured base URLs, never request-derived.
- **(c) Creates/touches:** `examples/01-strangler-proxy/src/main/java/.../StranglerProxyRoute.java`,
  `src/main/resources/application.properties` (new flag + base-url). Single writer of the proxy.
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (`camel_route_context`,
  `camel_validate_route`, `camel_render_route_diagram`).
- **(e) Deps / parallel:** after S4 (service port known). May overlap S5.
- **(f) Collision risk:** med — single writer of the proxy route + properties (shares the
  file with the existing Review flag; append, don't rewrite).
- **(g) Acceptance:** with the flag **false**, `/api/notifications` still reaches the
  monolith and the full suite is green through :8888; `camel_validate_route` clean; a
  Citrus route test proves the path matches the full `/api/notifications` prefix (not the
  Review-era `/notifications` bug).
- **(h) Tier:** Sonnet. No Opus gate (proof is S7).
- **(i) Checkpoint commit:** `feat(strangler): add notification flag + /api/notifications content-based route (default → monolith)`

## S7 — CUTOVER: flip both flags, equivalence green, reversibility shown  *(SEQUENTIAL, after S3 + S5 + S6)*  **[Opus gate — equivalence gate]**  — **HARD PART H2**  **[DONE — commit e4eb3d0]**
- **(b) Goal / DoD:** Flip **both** flags together (DRQ-036): monolith
  `notification.mode=outbox` (checkout now writes outbox→Kafka, no synchronous send) +
  proxy `strangler.notification.enabled=true` (reads served by the new service, which has
  consumed the event into its own store). Run the **full equivalence suite through the
  proxy** (:8888): checkout folders **unchanged green**; the Notification Context Contract
  folder **green via bounded-wait** against the async service. Demonstrate **reversibility**
  (flip both back → synchronous monolith path, still green) *before* decommission. Run the
  **negative check** (stop the consumer → Notification folder goes RED) to prove the async
  assertion is real (DRQ-037, guarding against the CUTOVER.md §2 false positive).
- **(c) Creates/touches:** flag config only (`notification.mode`, `strangler.notification.enabled`);
  a `demos/demo-notification-cutover.sh` narration/verification script (mirrors
  `demo-cutover.sh`); evidence appended to a new `examples/03-notification-service/CUTOVER.md`.
- **(d) Skills/MCP:** **camel-mcp** (`camel_runtime_*` to confirm routing), Newman re-run,
  `kafka-console-consumer` to show the event flow.
- **(e) Deps / parallel:** after S3 + S5 + S6. SEQUENTIAL.
- **(f) Collision risk:** touches both flag files; no code rewrite.
- **(g) Acceptance:** suite green through the proxy in the cutover state; reversibility
  demonstrated (both-flags-off → green again); **negative check RED** when the consumer is
  down (the async path is genuinely exercised); the latency gap (notification arrives
  *after* 201) is observed and documented. **[Opus gate]:** human/Opus signs off the
  equivalence-gate pass **and** that the negative check proves real async delivery.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(notification): flag-gated cutover to async outbox→Kafka→service; equivalence green + reversibility + negative-check proven`

## S8 — DECOMMISSION the monolith synchronous notification path + read surface  *(SEQUENTIAL, after S7)*  **[Opus gate]**  — **HARD PART H1**  **[DONE — commit ebbef1b]**
- **(b) Goal / DoD:** Remove the monolith's **synchronous** notification path and read
  surface: delete the `notificationService.sendOrderConfirmation(...)` call site logic and
  make `outbox` the **only** behavior (remove the `notification.mode` flag); remove
  `NotificationController` + `NotificationService.listByCustomerId` (+ the now-unused
  read repository method); **keep the outbox table + relay** (permanent monolith infra).
  Decide (document) whether to keep the now-write-only `Notification` entity/`notifications`
  table in the monolith or drop reads of it — **recommend keeping the table but ceasing to
  write/read it from the sync path** (true data decomposition deferred to ch.18/19, same as
  Review). Update `SMELLS.md` (mark SMELL #4 **CURED**) and `SixContextsSmokeTest` (add a
  "notification is now async via outbox / monolith no longer serves /api/notifications"
  assertion). `strangler.notification.enabled=true` becomes the committed default.
- **(c) Creates/touches:** `examples/00-monolith/**` (remove sync notification call +
  controller + flag; keep outbox), `SMELLS.md`, `SixContextsSmokeTest.java`,
  `examples/01-strangler-proxy/application.properties` (committed default true). Lane M + proxy.
- **(d) Skills/MCP:** none new; Newman re-run; `mvn -f examples/00-monolith clean verify`.
- **(e) Deps / parallel:** after S7. SEQUENTIAL.
- **(f) Collision risk:** monolith (lane M) + proxy — single writer each. (Note: a prior
  decommission `rm` was blocked by the permission classifier — see r02-status #2; expect a
  human-action step if a delete is refused.)
- **(g) Acceptance:** `GET :8080/api/notifications` → 404 (monolith no longer serves it);
  `GET :8080/api/orders` → 200 (unaffected); checkout still writes an outbox row (async
  notification still delivered via the service); monolith `clean verify` green; full suite
  green through the proxy; `SMELLS.md` #4 marked cured with the cure evidence. **[Opus
  gate]:** decommission reviewed; no orphaned sync path remains; outbox retained.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(notification): decommission monolith synchronous notification path; outbox→Kafka→service is now the only path (SMELL #4 cured)`

## S9 — Code-CI: extend the equivalence-gate job (Kafka + notification service)  *(SEQUENTIAL, after S8)*  **[Opus gate]**  **[DONE — commits 6023efd, b3ea789]**
- **(b) Goal / DoD:** Extend `.github/workflows/code-ci.yml` so the equivalence-gate job
  also brings up **Kafka** (service container / the podman-stack broker def) + the
  notification service + the monolith relay, then runs the **full** behavior-equivalence
  suite (incl. the Notification folder with bounded-wait) through the proxy and **fails on
  non-zero Newman exit**. Prove the gate truly gates with a **red-then-green**: a
  deliberately-broken consumer (e.g. idempotency disabled or persist skipped) makes the
  Notification folder go RED, then revert to green.
- **(c) Creates/touches:** `.github/workflows/code-ci.yml` (extend the existing r02 job;
  do not fork a new workflow). Isolated.
- **(d) Skills/MCP:** **lgtm-github** (GitHub Actions conventions). Reuses S2 suite + S3/S5/S8 artifacts.
- **(e) Deps / parallel:** after S8. SEQUENTIAL.
- **(f) Collision risk:** low — single workflow file.
- **(g) Acceptance:** workflow green on `push`/`pull_request` with the async notification
  path exercised end-to-end in CI; the deliberate break makes it RED (recorded as
  evidence), then green. **[Opus gate]:** Opus confirms the job genuinely waits for and
  asserts the async notification (bounded-wait honored in CI), not a green-only race.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `ci(r04.x): equivalence gate covers async notification (Kafka + service) in GitHub Actions`

## S10 — ch.17 diagram(s)  *(PARALLEL, after S5)*  **[DONE — commit 1ed0fa6]**
- **(b) Goal / DoD:** Paired **SVG + `.excalidraw`** figures: (1) the outbox →
  `@Scheduled` relay → Kafka `order.placed` → SmallRye consumer → own-DB + WebSocket
  sequence; (2) the before/after (sync-in-transaction vs async-decoupled) contrast. House
  style, catalogued.
- **(c) Creates/touches:** `assets/diagrams/17-*.svg` + `.excalidraw`; append rows to
  `assets/diagrams/README.md` (serialize this catalogue — one writer).
- **(d) Skills/MCP:** **lgtm-diagram-generator** (+ `camel_render_route_diagram` as a source ref).
- **(e) Deps / parallel:** after S5 (pipeline shape known); PARALLEL with S11.
- **(f) Collision risk:** low per-SVG; serialize `assets/diagrams/README.md`.
- **(g) Acceptance:** SVGs render; `.excalidraw` sources present; catalogue updated.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(§17): outbox→Kafka→consumer sequence + sync-vs-async diagrams (SVG + excalidraw)`

## S11 — ch.17 ADLC trace capture  *(PARALLEL, after S8 evidence exists)*  **[DONE — folded into commit a5fda78]**
- **(b) Goal / DoD:** Capture the **"ADLC in Action"** trace for ch.17 as pre-captured,
  narrated tool output (DRQ-025): the `migrate-spring-to-quarkus` run for the read-surface
  lift (S4), the SmallRye/Kafka validation (S5), the camel-mcp proxy validation (S6), the
  equivalence-gate + negative-check result (S7), both human gates, and the DRQ-034…038
  entries. The `_plans/` ledger is Exhibit A.
- **(c) Creates/touches:** captured transcripts under `_docs/_adlc-traces/` (or the ch.17
  companion), reusing the ch.07/S12-era callout template.
- **(d) Skills/MCP:** **lgtm-tutorial** (callout format); evidence sourced from S3–S9 runs (captured, not re-run live).
- **(e) Deps / parallel:** needs S8 evidence; PARALLEL with S10.
- **(f) Collision risk:** low; coordinate callout reuse with S12.
- **(g) Acceptance:** a complete Frame→Map→Plan→Generate→Verify→Operate→Reconcile trace
  exists as checked-in narrated output; both gates + equivalence + negative-check visible.
- **(h) Tier:** Sonnet. Opus gate folded into S12.
- **(i) Checkpoint commit:** `docs(§17): ADLC-in-Action trace for the notification extraction`

## S12 — ch.17 authored to the full bar  *(SEQUENTIAL, after S8 + S10 + S11)*  **[Opus gate — 2k + footer]**  **[DONE — commit a5fda78]**
- **(b) Goal / DoD:** Chapter 17 ("Extraction 2 — Notification Service, going
  event-driven") authored to the full bar: **≥2000 words excl. code/diagrams**,
  progressive, referencing the runnable `examples/03-notification-service/` + the monolith
  outbox; the S10 diagrams embedded; a real **"ADLC in Action" callout** (S11); a
  **verification-status footer** naming the tests/demos run (equivalence suite incl.
  Notification folder, idempotency test, negative check, Citrus, Dev Services, native).
  Must teach: the outbox pattern + why polling-not-Debezium-here (DRQ-034 tradeoff), the
  two-phase lift + idiomatic consumer split (DRQ-035), the eventual-consistency story and
  how the equivalence suite copes (DRQ-037), and the datamesh attribution + JSON-vs-Avro
  deferral (DRQ-038).
- **(c) Creates/touches:** `_docs/17-extraction-2-notification-service.md` (front matter:
  `title`, `order: 17`, `part: "The Strangler Fig in Practice"`, `description`,
  `duration`). Only *adds* a `_docs` file — never edits `_config.yml`/`_parts/`.
- **(d) Skills/MCP:** **lgtm-tutorial** (authoring + static validation) + **lgtm-jekyll** (build/word-count).
- **(e) Deps / parallel:** after S8 (behavior final), S10 (diagrams), S11 (callout). SEQUENTIAL.
- **(f) Collision risk:** low (single new `_docs` file).
- **(g) Acceptance:** lgtm-jekyll/lgtm-tutorial validation green: **word count ≥2000**,
  example-dir present, verification footer present, links/diagrams resolve. **[Opus
  gate]:** Opus confirms the 2k-with-running-code bar, the outbox/eventual-consistency
  teaching is honest, and the callout is authentic.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `docs(§17): author Extraction 2 — Notification Service to the full bar`

## S13 — Reconcile, status, exit validation  *(SEQUENTIAL, last)*  **[Opus gate]**  **[DONE — this reconciliation]**
- **(b) Goal / DoD:** Append r04/ch.17 outcomes to the ledger: `reconciliation.md` updated
  (artifact→source drift incl. the datamesh notification-service adaptation; zero
  unexplained drift, R4); `build-plan.md` status rows for ch.17 marked DONE;
  `decisions.md` version matrix updated (spring-kafka version, Quarkus messaging/websockets
  versions used). The ch.17 EXIT CHECKLIST (below) verified; clean resume boundary for r05
  (inventory / CDC) recorded.
- **(c) Creates/touches:** `_plans/reconciliation.md`, `_plans/build-plan.md` (status),
  `_plans/decisions.md` (matrix). Serialize all three.
- **(d) Skills/MCP:** none new; lgtm-jekyll full-site validation; re-run the equivalence suite once.
- **(e) Deps / parallel:** after all. SEQUENTIAL.
- **(f) Collision risk:** the three `_plans/*` are single-writer — only S1 and S13 write them in r04.
- **(g) Acceptance:** exit checklist all-green; matrix updated; status current. **[Opus
  gate]:** Opus signs off the whole extraction.
- **(i) Checkpoint commit:** `docs(r04.x): reconcile ch.17, update version matrix, mark notification extraction DONE`

---

## ch.17 EXIT CHECKLIST (mirrors "equivalence green + SMELL #4 cured + notification now async via outbox→Kafka") — ALL GREEN
- [x] **Equivalence green:** the *same* behavior-equivalence collection passes through the
      proxy unchanged — checkout folders (Scenario 1–3) untouched and green; the new
      Notification Context Contract folder green via bounded-wait against the async service.
- [x] **SMELL #4 cured:** `SMELLS.md` #4 marked CURED; the synchronous in-transaction
      `sendOrderConfirmation` call and the monolith's `/api/notifications` read surface are
      decommissioned; `GET :8080/api/notifications` → 404; `SixContextsSmokeTest` asserts it.
- [x] **Notification now async via outbox→Kafka:** `placeOrder` writes an `order.placed`
      outbox row atomically in the checkout transaction; the `@Scheduled` relay publishes
      to Kafka `order.placed`; the Quarkus service consumes via SmallRye Reactive Messaging,
      idempotently persists to its **own** schema, and serves the read surface (+ WebSocket).
- [x] **CDC-vs-polling decided:** polling relay chosen for ch.17 (DRQ-034), Debezium
      reserved for ch.19; tradeoff documented in-chapter.
- [x] **Two-phase honored:** read surface lifted Phase A (spring-compat) → Phase B
      (idiomatic), measured; consumer idiomatic-from-start with rationale (DRQ-035).
- [x] **Eventual consistency handled honestly:** bounded-wait assertion correct against both
      backends; **negative check** (consumer down ⇒ RED) proves the async path is real
      (no Review-style false positive).
- [x] **Kafka wired both sides:** monolith `spring-kafka` producer + service SmallRye
      `@Incoming` consumer agree on topic/shape/key; Dev Services for service tests.
- [x] **Reversibility shown** before decommission (both flags off → synchronous path green).
- [x] **Code-CI green:** the equivalence gate exercises the async notification end-to-end in
      GitHub Actions (red-then-green demonstrated).
- [x] **ch.17 authored ≥2000 words**, runnable example, embedded diagrams, real "ADLC in
      Action" callout, verification-status footer.
- [x] **Ledger reconciled:** `decisions.md` DRQ-034…038 accepted + version matrix updated
      (spring-kafka 3.3.16, quarkus-messaging-kafka/quarkus-websockets-next on Quarkus
      3.40.1, `apache/kafka:3.8.0`); this plan's steps and exit checklist marked DONE.

## Resume boundary for r05 (inventory / CDC)
r05 resumes from the `build-plan.md` status table. The outbox table + relay (S3) and the
JSON `order.placed` event (DRQ-038) are the reusable foundations r05 (and payment ch.23)
build on; ch.19 introduces **Debezium CDC** as the alternative to the polling relay (the
tradeoff flagged in DRQ-034); ch.28 introduces **Avro + Apicurio** (replacing the JSON
serialization used here). No new extraction in r05 beyond inventory.
