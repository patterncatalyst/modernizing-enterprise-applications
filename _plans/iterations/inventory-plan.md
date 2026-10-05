---
title: "Inventory Extraction Plan — ch.19 (the third strangler extraction, synchronous gRPC + CDC)"
description: "Concrete, ordered, executable step plan for the Inventory service extraction: cure SMELL #5 (raw-entity leak / no ACL) and SMELL #1 (shared-schema FK, order_items→inventory_items) by making order→inventory a synchronous service-to-service gRPC call across a real seam, giving inventory its OWN decomposed database seeded + kept-current by Debezium CDC (where DRQ-034's deferred CDC lands), and turning ch.16's InventoryAclRoute sketch into a wired, flag-gated cutover behind the Camel strangler proxy — equivalence-gated at every step, with the checkout seam (happy / out-of-stock / payment-decline-rollback) kept green even though the stock decrement now lives in another service's DB. Mirrors the notification-plan (ch.17) template."
status: "execution plan — planning only; nothing built, scaffolded, or pushed until the user approves"
iteration: r05
chapter: 19
depends_on:
  - _plans/build-plan.md            # §E row 3 (inventory = sync gRPC + Panache + CDC/Debezium backfill + decomposed DB), §G (equivalence gate)
  - _plans/decisions.md             # DRQ-034 (CDC deferred to HERE), DRQ-037 (async equivalence discipline), DRQ-029/032/035
  - _plans/iterations/notification-plan.md   # the proven extraction template this mirrors
  - examples/00-monolith/SMELLS.md  # SMELL #5 (raw-entity leak / no ACL) + SMELL #1 (shared-schema FK) — the things cured
  - _docs/16-content-based-routing-acl.md    # the InventoryAclRoute sketch this extraction makes real
references:
  - "~/Dev/datamesh-reference-arch-quarkus/examples/inventory-service (DRQ-032: quarkus-grpc + Panache; CheckStock proto — extended here with Reserve/Release)"
  - "~/Dev/datamesh-reference-arch-quarkus/examples/contracts/src/main/proto/capstone/inventory/v1/inventory.proto"
---

# ch.19 — Inventory Service Extraction (execution plan)

> **Status: COMPLETE.** All 16 steps (S1–S16) executed and reconciled. Commit range
> `fa9d020..c6638a2` on branch `r02-walking-skeleton` (the plan's `r05-inventory-extraction`
> framing was carried out as `r05`-scoped commits on the project's single working branch;
> see per-step commit hashes below). SMELL #5 cured, SMELL #1's FK portion cured for
> inventory, gRPC + CDC seam live, equivalence green through the proxy (79/79 post-decommission).
>
> **PLANNING ONLY.** This file is the Opus "Plan" phase (ADLC §F.1) and must be
> **user-approved before any code is written**. Nothing is built, scaffolded, or
> pushed until approval.
>
> **Branch for all work:** `r05-inventory-extraction` (off the current default branch).
> **Total steps:** 16 (S1 … S16).
> **Relay tiering (DRQ-004):** every step is *executed* by **Sonnet**; steps marked
> **[Opus gate]** additionally require an **Opus validation** pass before their
> checkpoint commit. Steps marked **[Opus gate — equivalence gate]** are the ADLC
> Verify human sign-off points (§F.1).

## Why this extraction is harder than notification (the framing)
Notification (ch.17) was an **asynchronous, fire-and-forget** concern — the monolith
never *waited* for it, and removing it from the checkout transaction only *shrank* the
blast radius. Inventory is the opposite: `order.OrderService#placeOrder` calls
`inventoryService.findBySkuOrThrow(sku)` **and** `inventoryService.reserve(sku, qty)`
**synchronously, in-process, inside the checkout `@Transactional`**, holding a
**pessimistic write lock** (`findWithLockBySku`) while it decrements stock
(`examples/00-monolith/.../inventory/InventoryService.java#reserve`). Three things make
this the hardest cut so far:

1. **It is a synchronous collaborator in the critical path.** Checkout cannot return 201
   until the reserve succeeds. Moving reserve across a service boundary means a remote
   call *inside* (or replacing) the checkout transaction — with real failure modes
   (timeout, unavailable, partial success) the in-JVM method call never had.
2. **It mutates, and the mutation must honor ACID→ACD.** Unlike the datamesh reference's
   read-only `CheckStock`, our `reserve` **writes**. Once inventory owns its own DB, the
   monolith's Postgres `@Transactional` **can no longer roll back that decrement** when
   payment later declines. Scenario 3 of the equivalence suite (payment-declined ⇒ stock
   **NOT** decremented) must still pass — so the cross-service decrement needs an explicit
   **compensation** (a deliberate first taste of the saga, foreshadowing ch.23).
3. **It shares the schema.** `order.OrderItem` holds a JPA `@ManyToOne` FK onto
   `inventory.InventoryItem` (`order_items.inventory_item_id → inventory_items.id`,
   SMELL #1 / SMELL[ch.18]). The moment inventory owns its data, that cross-database FK
   cannot exist; `OrderItem` must become a **denormalized snapshot** (sku, name,
   unit-price-at-order-time) — a soft reference, tied to ch.18.

## What this extraction delivers (from build-plan §E row 3, §C, SMELL #5 + #1)
Cure **SMELL #5** (the raw `InventoryItem` entity leaked across the order↔inventory seam
with no ACL) and begin curing **SMELL #1** (the shared-schema cross-context FK) by:

1. A new **Quarkus inventory service** at **`examples/04-inventory-service`** (:8084)
   that **owns its own decomposed database** and exposes **two surfaces**: a
   **`quarkus-grpc` server** (`capstone.inventory.v1.InventoryService`) for the hot,
   synchronous **CheckStock / Reserve / Release** service-to-service calls, and the
   lifted REST **`/api/inventory`** read surface. The **proto is the ACL contract** — the
   typed wire vocabulary the ch.16 message translator converts to/from `StockDto`.
2. **Debezium CDC** (Postgres connector on **Kafka Connect**, added to the podman stack)
   that **initial-snapshot backfills** the monolith's `inventory_items` into the new
   service's owned schema and **keeps it current during the transition window** (reading
   the Postgres WAL via logical replication). This is where **DRQ-034's deferred CDC
   lands** (ch.17 used a polling outbox precisely so ch.19 could introduce CDC here).
3. The **monolith's order→inventory call becomes a decorating collaborator**: an adapter
   that implements the same seam the god `OrderService` already calls, but routes to the
   extracted service over **gRPC** (reserve/check) and translates the gRPC reply into the
   clean `StockDto` contract — never the leaked entity. `order_items` is denormalized off
   the FK (ch.18).
4. The **Camel strangler proxy** gains a `strangler.inventory.enabled` flag routing the
   REST read surface `/api/inventory` to the new service, and **`InventoryAclRoute`**
   (the ch.16 sketch) is wired for real as the content-based-router + content-enricher +
   message-translator at the seam (decorating collaborator, the ch.16→ch.19 payoff).
5. The **behavior-equivalence suite still passes unchanged**: the checkout folders
   (Scenario 1 happy-path stock-decrement, Scenario 2 out-of-stock 409, **Scenario 3
   payment-decline ⇒ stock untouched**) stay green **through the seam**, plus a new
   "Inventory Context Contract" folder for the `/api/inventory` read surface. Reserve
   stays **synchronous** (correctness); replication/read is **async via CDC**.
6. Tests at every tier + the equivalence gate extended in CI to stand up Kafka Connect +
   Debezium + the gRPC service + the monolith.

## Decisions seeded by this plan (append to `_plans/decisions.md`, next free IDs after DRQ-038)
- **DRQ-039 — gRPC for the synchronous reserve/check hot path; REST for the read surface.**
  The order→inventory `CheckStock`/`Reserve`/`Release` calls use **quarkus-gRPC**
  (build-plan §E row 3, DRQ-032); the public `/api/inventory` read surface stays REST,
  routed by the existing proxy. **Why gRPC for the hot path:** the `.proto` *is* the ACL
  contract — a strongly-typed, versioned wire vocabulary (`stock_keeping_unit`,
  `unit_price_cents`, `on_hand_qty`) the ch.16 message translator maps to `StockDto`;
  low-latency, binary, service-to-service, exactly the datamesh pattern. REST stays for
  the read surface because the Newman suite hits `/api/inventory` and the proxy already
  routes REST by path prefix. **Tradeoff:** two protocols at one seam; mitigated because
  each serves its natural traffic (typed internal RPC vs. public read).
- **DRQ-040 — CDC via Debezium Postgres connector on Kafka Connect, added to the podman
  stack (NOT Debezium Embedded).** ch.19's title is literally "Transaction Log Tailing,
  CDC"; teaching real WAL-based CDC wants the real connector as a visible, inspectable
  artifact, and the stack already runs Kafka so Connect is one more container. **Role:**
  (a) initial snapshot → backfill `inventory_items` into the owned schema; (b) streaming
  changes to keep the owned store current *during the transition window* (before writes
  cut over to the service). **Tradeoff vs ch.17's polling outbox (DRQ-034):** CDC reads
  the WAL — no app poll, no table scan, lower latency — at the cost of `wal_level=logical`
  on the monolith's Postgres, a replication slot + publication, and Connect/connector
  lifecycle. Debezium **Embedded** (library-in-service) is the lighter alternative,
  noted in-chapter and deliberately *not* used because a standalone connector is the
  clearer teaching artifact for log-tailing.
- **DRQ-041 — Reserve stays synchronous; replication is async.** Correctness requires the
  caller to know *before* confirming the order whether stock was secured, so `Reserve`
  is a synchronous gRPC call that mutates the inventory service's own DB (server-side
  atomic decrement under its own lock). Only the owned-store *replication/read* path is
  async (CDC). This is the honest split: sync where correctness needs it, async where it
  doesn't.
- **DRQ-042 — Cross-service consistency without a distributed transaction: Reserve +
  compensating Release (a deliberate first taste of saga).** Once the decrement lives in
  another DB, the monolith's `@Transactional` can't roll it back on a later
  payment-decline. The monolith issues a **compensating `Release(sku, qty)`** gRPC call
  when checkout fails after a successful reserve, so the **net observable effect** matches
  the monolith baseline (Scenario 3: stock untouched after a decline). This is explicitly
  a minimal compensation **foreshadowing the choreographed saga (ch.23)**, cross-referenced
  in-chapter; ch.19 does not build a full saga.
- **DRQ-043 — Shared-FK decomposition (realizes ch.18 for this seam).** `order_items`'s
  `@ManyToOne InventoryItem` FK is replaced by a **denormalized snapshot** on `OrderItem`
  (sku + name + unit-price-at-order-time — a soft reference string, no cross-DB FK). The
  snapshot is captured from the gRPC reply at order time. The `inventory_items` table is
  kept in the shared schema (write-only history during transition, same treatment as the
  `reviews`/`notifications` tables in SMELL #1) until writes fully cut over.
- **DRQ-044 — Two-phase for the REST read surface; gRPC server + Reserve/Release
  idiomatic-from-start.** The `/api/inventory` read surface (`InventoryController`/
  `InventoryService.listAll,getBySku`/`InventoryItem`+repo) follows DRQ-029 Phase A
  (spring-compat lift) → Phase B (idiomatic Quarkus REST + Panache). The gRPC server and
  the mutating Reserve/Release have **no Spring original** to lift, so they are authored
  **idiomatic from day one** — the honest reading of DRQ-029/035 (you cannot lift code
  that never existed).
- **DRQ-045 — Reversibility via two flags.** Call/write side: monolith
  `inventory.mode = local|remote` (default `local` = in-JVM reserve against the shared
  schema). Read side: proxy `strangler.inventory.enabled` (default `false`). Cutover flips
  both together; reversible until the decommission step (mirrors Review/Notification).
- **DRQ-046 — Equivalence across a synchronous cross-service seam.** The checkout folders
  (Scenario 1–3) stay **synchronous assertions** — reserve is synchronous, so no
  bounded-wait is needed on the checkout path (contrast ch.17/DRQ-037). The new
  `/api/inventory` read folder may need a **bounded-wait** only while reads are served
  from the CDC-replicated store mid-transition. A **negative check** (stop the inventory
  service ⇒ checkout returns 5xx on reserve and the read folder goes RED) proves the seam
  is genuinely exercised, guarding the CUTOVER.md §2 false-positive trap (which bit Review
  because both backends shared the same table — here the owned DB makes that trap
  structurally harder, but we still prove it).

## Standing constraints applied to every step
Scope discipline (nothing here an r05 ch.19 deliverable doesn't require — no Apicurio/Avro
[ch.28], no full saga [ch.23], no CQRS [ch.21]; CDC and gRPC are *planned* here, not
speculative); conceptual coherence; security-by-design (OWASP/CIS — secrets hygiene,
secure-by-default Camel dynamic-URI allow-list, gRPC with no request-derived targets, no
creds in git, Debezium connector creds from env/Bitwarden not git); **SIMPLE git only —
`git -C <dir> …`, never `cd && git`**; **never push beyond
`github.com/patterncatalyst/modernizing-enterprise-applications` without explicit user
permission**; Conventional Commits (`feat`/`fix`/`docs`/`chore`/`refactor`/`ci`/`test`/
`site` + scopes `rNN.x`, `§NN`, service names); **NO attribution trailers**. A subagent
does **not** inherit a loaded skill — each executor prompt must explicitly invoke/read the
named skill.

## Parallelism overview
```
S1 (frame + branch + decisions, SEQUENTIAL, must be first)
S2 equivalence suite: Inventory folder + baseline-green vs monolith              [Opus gate]
 ├─ S3 podman stack: Debezium/Kafka-Connect + logical replication (infra) ─┐     [Opus gate]
 ├─ S4 gRPC contract: inventory.proto (CheckStock/Reserve/Release/GetStock)─┤ (shared contract)
 └─ S5 new svc scaffold + Phase A read-surface lift + CDC snapshot backfill ┘ (lane N — disjoint dir) [Opus gate]
S6 Phase B idiomatic + gRPC server (Reserve mutating, idiomatic-from-start), measured (SEQ after S4+S5) [Opus gate]
S7 monolith decorating collaborator: gRPC client adapter + compensation (lane M, after S6)             [Opus gate]
S8 ch.18 FK decomposition: OrderItem snapshot, drop cross-context FK (lane M, SEQ after S7)             [Opus gate]
S9 strangler proxy: inventory flag + /api/inventory route + wire InventoryAclRoute (after S4+S6)
S10 CUTOVER: flip both flags; checkout seam + read folder green; reversibility; negative check (SEQ after S7+S8+S9) [Opus gate — equivalence gate]
S11 DECOMMISSION monolith inventory module + cut writes + retire/redirect CDC (SEQ after S10)           [Opus gate]
S12 Code-CI: extend equivalence gate (Connect+Debezium + gRPC + monolith) red-then-green (SEQ after S11) [Opus gate]
 ├─ S13 ch.19 diagram(s) ───┐   (PARALLEL after S6)
 └─ S14 ch.19 ADLC trace ───┘   (PARALLEL after S11 evidence exists)
S15 ch.19 authored to the bar (+ ch.18 FK-cut cross-ref) (SEQ after S11 + S13 + S14)   [Opus gate — 2k + footer]
S16 reconcile + status + exit (SEQ, last)                                               [Opus gate]
```
**Single-writer / serialize (§K):** the monolith reactor `pom.xml` + `OrderService` +
`OrderItem` + Flyway migrations + monolith Postgres config (S3, S7, S8, S11 — lane M, one
writer, serialized); `examples/01-strangler-proxy/` `application.properties` +
`StranglerProxyRoute.java` + the new `InventoryAclRoute.java` (S9, S10); `compose.yaml` +
`.env`/`.env.example` (S3 — single infra writer); the shared `.proto` (S4 — contract,
single writer); `tooling/newman/mea.postman_collection.json` (S2 — versioned with the
monolith, R8); the three `_plans/*` ledgers (S1, S16); `_config.yml`/`_parts/`/CSS
untouched (chapters only *add* `_docs/19-*.md` and *edit* `_docs/18-*.md` prose cross-ref).

---

## THE HARD PARTS (called out explicitly, per the brief)

**H1 — The synchronous cross-service reserve under the equivalence gate (S6/S7/S10).**
Reserve mutates. Moving it across the seam turns an in-JVM, locked, transactional
decrement into a remote gRPC call whose success must be known before checkout returns 201,
and whose effect the monolith transaction can no longer undo. **Scenario 2 (out-of-stock)**
stays correct because the server-side Reserve is atomic (it checks-and-decrements under its
own lock and returns unavailable ⇒ monolith throws `InsufficientStockException` ⇒ 409, no
decrement happened). **Scenario 3 (payment-decline ⇒ stock untouched)** is the dangerous
one: the decrement already committed in the inventory DB, so the monolith must issue a
**compensating `Release`** on the decline path (DRQ-042) to restore the observable baseline.
If that compensation is missed, skipped on an exception path, or itself fails, stock
silently stays decremented after a decline — the equivalence suite goes RED (good) or, worse,
drifts silently (the thing we most fear). Mitigation: S2 captures Scenario 3 green against the
monolith first; every S6/S7/S10 commit re-runs it; S10's negative check proves the seam.

**H2 — CDC / Debezium in the podman stack (S3).** First time the stack gains Kafka Connect +
a Debezium Postgres connector, and the first time the monolith's Postgres is reconfigured for
**logical replication** (`wal_level=logical`, a publication, a replication slot). Risks:
replication-slot leaks (disk fill if the connector dies and the slot isn't dropped), snapshot
vs. streaming cutover correctness, connector credentials (env/Bitwarden, never git), and
image-tag pinning discipline (`.env`/`.env.example`, R5/R11). Mitigation: pin the Connect +
Debezium image tags in `.env.example`; document slot lifecycle; health-gate the connector
before the backfill is trusted.

**H3 — The shared-schema FK decomposition (S8).** `order_items.inventory_item_id →
inventory_items.id` is a real DB FK and a JPA `@ManyToOne`. It cannot survive inventory
owning its own DB. `OrderItem` must drop the entity reference and persist a denormalized
snapshot (sku/name/unit-price-at-order-time). This touches the monolith's hottest write
path (`OrderService#placeOrder` builds `OrderItem`) and a Flyway migration that drops the FK
constraint — a schema change on the "before" artifact. Collision-critical: lane M only.
Risk: `OrderDto.Item`/`toDto` already project from the snapshot fields (sku, qty, unit price),
so the read contract is unchanged — but the equivalence suite must prove that byte-for-byte.

**H4 — Two protocols + the real ACL at one seam (S9).** The ch.16 `InventoryAclRoute` sketch
becomes real: content-based router (flag) → content enricher (dynamic gRPC/REST fetch) →
message translator (gRPC `StockReply` vocabulary → `StockDto`). The monolith side (the
decorating collaborator, S7) and the proxy side (the ACL route, S9) must agree on the same
`StockDto` contract and the same proto. camel-mcp validates the route; the translator is the
one place the gRPC field vocabulary is remapped.

---

## S1 — Frame, branch, decisions  *(SEQUENTIAL — must be first)*  — **DONE** (decisions seeded directly into `_plans/decisions.md`/this plan; no standalone frame commit — folded into the work leading to `fa9d020`)
- **(b) Goal / DoD:** The ch.19 outcome + acceptance criteria are framed; the working
  branch exists; DRQ-039…046 are appended to `decisions.md`; a build-plan status row for
  r05/ch.19 is added as "in progress"; the version matrix gains forward rows (quarkus-grpc,
  Debezium/Connect image tags) to be pinned in S3–S6.
- **(c) Creates/touches:** `_plans/decisions.md` (DRQ-039…046), `_plans/build-plan.md`
  (status row), checkout branch `r05-inventory-extraction`. No code.
- **(d) Skills/MCP:** none (ledger authoring). `git -C <dir> checkout -b`.
- **(e) Deps / parallel:** none; SEQUENTIAL, gates everything.
- **(f) Collision risk:** `_plans/*` single-writer — this and S16 are the only ledger writers.
- **(g) Acceptance:** branch is `r05-inventory-extraction`; DRQ-039…046 present; the
  gRPC-vs-REST, CDC-tool, sync-reserve, compensation, and FK-decomposition decisions written.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(r05.x): frame ch.19 inventory extraction; seed DRQ-039..046 and working branch`

## S2 — Extend the equivalence suite: Inventory Context Contract (baseline green vs monolith)  *(SEQUENTIAL, after S1)*  **[Opus gate]**  — **DONE** (`fa9d020` — extend equivalence suite with Inventory Context Contract folder, baseline green)
- **(b) Goal / DoD:** Add an **"Inventory Context Contract"** folder to
  `mea.postman_collection.json` asserting the `/api/inventory` read surface
  (`GET /api/inventory` → 200 array; `GET /api/inventory/{sku}` → 200 `StockDto` shape;
  unknown sku → 404). **Confirm the existing checkout folders (Scenario 1 stock-decrement,
  Scenario 2 out-of-stock 409, Scenario 3 payment-decline-untouched) are left unedited** —
  they are the *real* inventory equivalence test and must pass across the seam unchanged
  (H1). Capture the new folder **green against the still-monolithic inventory** (baseline).
- **(c) Creates/touches:** `tooling/newman/mea.postman_collection.json` (new folder only),
  `tooling/newman/inventory-service.postman_environment.json` (forward-ref env on :8084,
  mirroring the notification/review envs). **Collection versioned with the monolith (R8).**
- **(d) Skills/MCP:** Newman (reuse CNDP App. O patterns). Runs against the monolith on :8080.
- **(e) Deps / parallel:** after S1; SEQUENTIAL (must baseline before any behavior changes).
- **(f) Collision risk:** low (own `tooling/`), but it is the equivalence contract — ripples to every later run.
- **(g) Acceptance:** `newman run … --folder "Inventory Context Contract"` green against the
  unmodified monolith; Scenario 1–3 still green unchanged. **[Opus gate]:** Opus confirms
  the new folder reads real seeded stock, the read contract matches the monolith's `StockDto`
  JSON exactly, and that Scenario 3's assertion genuinely re-reads stock *after* the decline.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `test(equivalence): add Inventory Context Contract folder, green vs monolith baseline`

## S3 — Podman stack: Debezium (Kafka Connect + Postgres connector) + logical replication  *(PARALLEL infra, after S1)*  **[Opus gate]**  — **HARD PART H2**  — **DONE** (`7c437ea` — add Debezium CDC to podman stack: Kafka Connect + Postgres logical replication; connector RUNNING, CDC event verified)
- **(b) Goal / DoD:** Add a **Kafka Connect** service (with Debezium Postgres connector
  plugins) to `compose.yaml`, pinned in `.env`/`.env.example`; reconfigure the monolith's
  Postgres for **logical replication** (`wal_level=logical`, `max_replication_slots`,
  `max_wal_senders`); define the Debezium connector config (publication + slot for the
  `inventory_items` table) as a tracked JSON/registration script. Secure: connector DB
  creds from env (not git). Verify the connector reaches RUNNING and emits a snapshot +
  change events for `inventory_items` to a CDC topic.
- **(c) Creates/touches:** `compose.yaml` (new `connect` service), `.env`/`.env.example`
  (`CONNECT_IMAGE_TAG`, `DEBEZIUM_*`), a `tooling/debezium/inventory-connector.json` +
  registration helper, Postgres `command`/config for logical replication. Does **not**
  touch service or monolith app code.
- **(d) Skills/MCP:** **lgtm-podman-stack** (compose + healthcheck + networking patterns).
  Reference Bitwarden for any non-demo creds (standing memory).
- **(e) Deps / parallel:** after S1; **PARALLEL with S4/S5** (disjoint from code dirs).
- **(f) Collision risk:** **single infra writer** — only this step edits `compose.yaml`/`.env` in r05.
- **(g) Acceptance:** `podman compose up` brings Connect healthy; the connector registers and
  reaches RUNNING; an UPDATE to `inventory_items` in the monolith DB appears as a CDC event;
  the replication slot is created and its lifecycle (drop on teardown) is documented.
  **[Opus gate]:** Opus confirms secrets hygiene (no creds in git), slot-leak mitigation, and
  pinned image tags.
- **(h) Tier:** Sonnet execute; **Opus validate** (new infra + Postgres reconfig).
- **(i) Checkpoint commit:** `feat(stack): add Debezium Postgres CDC on Kafka Connect; enable logical replication for inventory backfill`

## S4 — gRPC contract: `inventory.proto` (CheckStock / Reserve / Release / GetStock)  *(PARALLEL, after S1)*  — **DONE** (folded into `01f0b9a` scaffold commit — proto ACL contract authored alongside the service scaffold)
- **(b) Goal / DoD:** Author the inventory gRPC contract — the **ACL contract** — adapting
  datamesh's `capstone.inventory.v1.InventoryService` (which ships only read-only
  `CheckStock`) and **extending it** with the mutating RPCs this seam needs:
  `CheckStock(sku,qty)→(available,quantity_on_hand)`, `Reserve(sku,qty)→(reserved,
  quantity_on_hand)` (server-side atomic check-and-decrement), `Release(sku,qty)→(...)`
  (compensation, DRQ-042), and `GetStock(sku)→StockReply` (for the ACL read enrich). Field
  vocabulary deliberately differs from `StockDto` (`stock_keeping_unit`, `unit_price_cents`,
  `on_hand_qty`) so the ch.16 translator earns its keep.
- **(c) Creates/touches:** a shared proto under `examples/04-inventory-service/src/main/proto/
  dev/patterncatalyst/inventory/v1/inventory.proto` (or a small shared `contracts` module if
  the monolith client + proxy both consume it — decide in S4 and document). **Single writer of the contract.**
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills grpc`, `quarkus_searchDocs grpc`);
  reference the datamesh proto. **lgtm-quarkus**.
- **(e) Deps / parallel:** after S1; **PARALLEL with S3/S5**. Feeds S6, S7, S9.
- **(f) Collision risk:** the proto is a shared contract (monolith client, service, proxy ACL) — single writer; version it `v1`.
- **(g) Acceptance:** the proto compiles (protoc/quarkus-grpc codegen) and generates the
  Mutiny stubs; Reserve/Release/CheckStock/GetStock messages present with the distinct field
  vocabulary; attribution to the datamesh proto recorded.
- **(h) Tier:** Sonnet. No Opus gate (proven in S6/S7).
- **(i) Checkpoint commit:** `feat(inventory): gRPC contract v1 — CheckStock/Reserve/Release/GetStock (the ACL contract)`

## S5 — New inventory service: scaffold + Phase A read-surface lift + CDC snapshot backfill  *(PARALLEL lane N, after S3 for backfill; may start scaffold after S1)*  **[Opus gate]**  — **DONE** (`01f0b9a` — scaffold inventory service: proto ACL contract + Phase A read surface + Debezium CDC backfill, own schema, e2e matched)
- **(b) Goal / DoD:** A new Quarkus module `examples/04-inventory-service` (:8084) that
  **owns its own schema/database from day one**, seeded by the **Debezium initial-snapshot
  backfill** (S3) of `inventory_items`. The monolith's Spring read surface is **lifted via
  Quarkiverse Spring-compat** (`quarkus-spring-web`,`-di`,`-data-jpa`):
  `InventoryController` → `/api/inventory` + `/api/inventory/{sku}`,
  `InventoryService.listAll/getBySku`, `InventoryItem` entity + repository over its own
  `inventory_items` table (its own Flyway). No gRPC yet (S6). The CDC stream keeps the owned
  table current during the transition.
- **(c) Creates/touches:** `examples/04-inventory-service/**` (pom, `application.properties`
  on :8084 with its own datasource + Flyway, controller/service/entity/repo, tests),
  `src/main/docker/*`. Isolated subtree.
- **(d) Skills/MCP:** **quarkus-agent** — `quarkus_skills` against the monolith dir to
  discover + follow **`migrate-spring-to-quarkus`** (do NOT self-plan the migration);
  `quarkus_create`/`quarkus_start`/`quarkus_searchDocs`; **lgtm-quarkus**.
- **(e) Deps / parallel:** scaffold after S1; backfill test needs S3 live; **PARALLEL with S4**. Isolated dir.
- **(f) Collision risk:** low — isolated under `examples/04-inventory-service/`.
- **(g) Acceptance:** service boots on :8084 against its own DB; after the CDC snapshot,
  `GET /api/inventory` returns the backfilled stock matching the monolith's; Spring-compat
  extensions present (Phase A); unit tests green. **[Opus gate]:** Opus confirms it owns its
  schema (no reach into the shared monolith table) and the read contract matches the monolith's.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(inventory): Phase A — lift read surface onto Quarkus via Spring-compat; own schema seeded by CDC backfill`

## S6 — Phase B idiomatic + gRPC server (Reserve mutating, idiomatic-from-start), measured  *(SEQUENTIAL, after S4 + S5)*  **[Opus gate]**  — **HARD PART H1**  — **DONE** (`5523429` — inventory Phase B idiomatic + gRPC server: atomic Reserve/Release, concurrency-safe; 16/16)
- **(b) Goal / DoD:** Refactor the read surface off the compat shim to **idiomatic Quarkus**
  (Quarkus REST + Panache, native CDI) — mirroring Review/Notification Phase B — and add the
  **net-new quarkus-gRPC server** (idiomatic-from-start, DRQ-044) implementing
  `CheckStock`/`Reserve`/`Release`/`GetStock`. **Reserve** performs the atomic
  check-and-decrement **in inventory's own DB** under its own lock (Panache +
  `@Transactional`, mirroring the monolith's pessimistic semantics server-side, à la
  datamesh's `InventoryGrpcService` but mutating); **Release** re-increments
  (compensation). Idempotency/safety on Reserve/Release noted. Capture **measured
  before/after** (startup/RSS/native) like `review-service/MIGRATION.md`.
- **(c) Creates/touches:** `examples/04-inventory-service/**` — pom (remove spring-compat;
  add `quarkus-grpc`, `quarkus-hibernate-orm-panache`, `quarkus-rest(+jackson)`,
  `quarkus-smallrye-health`), `application.properties` (grpc server port/config), the
  `InventoryGrpcService`, Panache refactor, `MIGRATION.md`, Citrus/`@QuarkusTest` + Dev
  Services tests (incl. a gRPC Reserve-then-Release round-trip test). **Adapt from datamesh
  `inventory-service` with attribution** (extended with the mutating RPCs it lacks).
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills grpc,panache`; `quarkus_searchDocs`),
  **lgtm-quarkus**; Dev Services for PG in tests.
- **(e) Deps / parallel:** after S4 (proto) + S5 (service). SEQUENTIAL.
- **(f) Collision risk:** low (same isolated module).
- **(g) Acceptance:** compat extensions gone; a gRPC `Reserve` decrements exactly once and
  returns `reserved=false` + unchanged stock when insufficient (out-of-stock semantics);
  `Release` restores it; `CheckStock`/`GetStock` read correctly; `/api/inventory` read
  contract unchanged; before/after metrics captured (real runs). **[Opus gate]:** Opus
  verifies Reserve atomicity/concurrency safety and that out-of-stock + release semantics
  match the monolith's observable behavior.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(inventory): Phase B — idiomatic Quarkus REST/Panache + gRPC Reserve/Release/CheckStock (mutating, measured)`

## S7 — Monolith decorating collaborator: gRPC client adapter + compensation  *(lane M, SEQUENTIAL after S6)*  **[Opus gate]**  — **HARD PART H1**  — **DONE** (`45618ca` — monolith gRPC client for inventory reserve + saga compensation; Scenario 3 rollback holds across the seam)
- **(b) Goal / DoD:** In `examples/00-monolith`, replace the in-JVM order→inventory calls
  with a **decorating collaborator**: an adapter (e.g. `RemoteInventoryClient`) that
  implements the seam `OrderService#placeOrder` uses and routes to the extracted service
  over **gRPC** — translating the gRPC `StockReply` into the clean `StockDto`/contract
  (never the leaked `InventoryItem`, curing SMELL #5 at the source). Gate behind
  `inventory.mode = local|remote` (default `local` keeps today's in-JVM path = unchanged
  baseline). In `remote` mode: `CheckStock`/`Reserve` over gRPC; on any checkout failure
  **after** a successful reserve (notably payment decline), issue a compensating
  **`Release`** (DRQ-042) so Scenario 3 stays green. Handle gRPC failure modes (timeout/
  unavailable → fail the checkout cleanly, no silent success).
- **(c) Creates/touches:** `examples/00-monolith/pom.xml` (+ gRPC client deps),
  `order/OrderService.java` (reserve/check via the adapter; compensation on the failure
  path), a new `inventory/RemoteInventoryClient.java` (the ACL translator, gated by
  `inventory.mode`), `application.yml` (grpc client target + `inventory.mode`). Does **not**
  touch the service or proxy dirs. Lane M single writer.
- **(d) Skills/MCP:** Spring authored manually (per §K); **quarkus-agent** `quarkus_searchDocs`
  for the gRPC client idiom if needed; verify against the live S6 service.
- **(e) Deps / parallel:** after S6 (needs the gRPC server live). SEQUENTIAL in lane M.
- **(f) Collision risk:** **HIGH** — lane M sole writer of the monolith reactor root +
  `OrderService` in r05; serialized with S8/S11.
- **(g) Acceptance:** with `inventory.mode=local` the full suite stays green (zero
  regression). With `inventory.mode=remote`: Scenario 1 decrements via gRPC; Scenario 2
  returns 409 with stock untouched; **Scenario 3 (payment decline) leaves stock untouched
  because the compensating Release ran** (assert via the inventory service + the read
  folder). **[Opus gate]:** Opus confirms the compensation runs on *every* post-reserve
  failure path (not just the happy decline), no leaked entity crosses the seam, and gRPC
  failures fail checkout rather than confirming a phantom order.
- **(h) Tier:** Sonnet execute; **Opus validate** (checkout-path change + consistency).
- **(i) Checkpoint commit:** `feat(monolith): route order->inventory over gRPC via decorating collaborator; compensating Release on failure (flag-gated, default local)`

## S8 — ch.18 shared-FK decomposition: OrderItem snapshot, drop cross-context FK  *(lane M, SEQUENTIAL after S7)*  **[Opus gate]**  — **HARD PART H3**  — **DONE** (`381e9e3` — decompose order_items->inventory_items FK to OrderItem snapshot; V4 migration; order contract unchanged)
- **(b) Goal / DoD:** Replace `OrderItem`'s `@ManyToOne InventoryItem` FK with a
  **denormalized snapshot** (sku:String + name + `unitPriceCents` already present) — a soft
  reference, no cross-DB FK (DRQ-043, realizing ch.18). `OrderService#placeOrder` captures
  the snapshot from the gRPC reply at order time. A Flyway migration **drops the
  `order_items.inventory_item_id` FK constraint** and adds the snapshot columns; backfill
  existing `order_items` from the current join before dropping. `OrderDto.Item`/`toDto`
  already project sku/qty/unit-price, so the **read contract is unchanged** — prove it.
- **(c) Creates/touches:** `order/OrderItem.java` (drop entity ref, add snapshot fields),
  `order/OrderService.java` (`toDto` + `placeOrder` build from snapshot), a Flyway
  `V{n}__decompose_order_items_fk.sql`, affected tests. Lane M single writer.
- **(d) Skills/MCP:** Spring/Flyway manual; `mvn -f examples/00-monolith verify`.
- **(e) Deps / parallel:** after S7 (snapshot sourced from the gRPC reply). SEQUENTIAL in lane M.
- **(f) Collision risk:** **HIGH** — monolith schema + `OrderService`/`OrderItem`; lane M only.
- **(g) Acceptance:** monolith `clean verify` green; the cross-context FK is gone from the
  schema; `OrderItem` no longer imports `inventory.InventoryItem`; Scenario 1–3 + the order
  read contract (`GET /api/orders/{id}` items array) are **byte-for-byte unchanged**.
  **[Opus gate]:** Opus confirms no cross-DB FK remains, the snapshot is captured at order
  time (price-at-time-of-order correctness), and the order read contract is identical.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `refactor(monolith): decompose order_items->inventory_items FK into a denormalized snapshot (ch.18)`

## S9 — Strangler proxy: inventory flag + /api/inventory route + wire InventoryAclRoute  *(SEQUENTIAL, after S4 + S6; may overlap S7/S8)*  — **HARD PART H4**  — **DONE** (`06dbf57` — proxy inventory flag + /api/inventory route; ACL at gRPC boundary; flag-off suite 77/77 green)
- **(b) Goal / DoD:** Add to `StranglerProxyRoute` a `strangler.inventory.enabled` flag
  (default **false** → monolith) + `strangler.inventory.base-url` (:8084), content-based
  routing on the **full `/api/inventory`** path prefix (heeding the Review `/reviews`-prefix
  bug). **Turn the ch.16 `InventoryAclRoute` sketch into a real, compiled, wired route**:
  content-based router (flag) → content enricher (dynamic fetch against the monolith REST
  today / the inventory service's `GetStock` gRPC when enabled) → the
  `StockDtoTranslatingStrategy` message translator (gRPC `StockReply` vocabulary → `StockDto`).
  Secure-by-default: only the two fixed operator-configured targets.
- **(c) Creates/touches:** `examples/01-strangler-proxy/.../StranglerProxyRoute.java`
  (append inventory flag/route), a real `InventoryAclRoute.java` + `StockDtoTranslator`,
  `application.properties` (new flag + base-url + grpc target). Single writer of the proxy.
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (`camel_route_context`,
  `camel_validate_route`, `camel_render_route_diagram`, `camel_component_doc grpc`).
- **(e) Deps / parallel:** after S4 (proto) + S6 (gRPC server). May overlap S7/S8.
- **(f) Collision risk:** med — single writer of proxy route + properties (shares the file
  with Review/Notification flags; append, don't rewrite).
- **(g) Acceptance:** with the flag **false**, `/api/inventory` still reaches the monolith
  and the full suite is green through :8888; `camel_validate_route` clean; a Citrus route
  test proves the full `/api/inventory` prefix match and that the translator produces
  `StockDto` from both backends.
- **(h) Tier:** Sonnet. No Opus gate (proof is S10).
- **(i) Checkpoint commit:** `feat(strangler): inventory flag + /api/inventory route; wire ch.16 InventoryAclRoute for real (ACL translator)`

## S10 — CUTOVER: flip both flags; checkout seam + read folder green; reversibility; negative check  *(SEQUENTIAL, after S7 + S8 + S9)*  **[Opus gate — equivalence gate]**  — **HARD PARTS H1/H4**  — **DONE** (`ef9c6d0` — inventory cutover: gRPC reserve across seam + compensation; equivalence green; service-down RED verified)
- **(b) Goal / DoD:** Flip **both** flags together (DRQ-045): monolith `inventory.mode=remote`
  (checkout reserves/checks over gRPC, compensates on failure) + proxy
  `strangler.inventory.enabled=true` (reads served by the inventory service). Run the **full
  equivalence suite through the proxy** (:8888): **Scenario 1–3 green across the seam** —
  especially **Scenario 2 (out-of-stock 409)** and **Scenario 3 (payment-decline ⇒ stock
  untouched via compensating Release)** — plus the Inventory Context Contract folder.
  Demonstrate **reversibility** (flip both back → in-JVM local path, still green) before
  decommission. Run the **negative check** (stop the inventory service → checkout returns
  5xx on reserve and the read folder goes RED) to prove the seam is genuinely exercised
  (DRQ-046; the CUTOVER.md §2 trap).
- **(c) Creates/touches:** flag config only (`inventory.mode`, `strangler.inventory.enabled`);
  a `demos/demo-inventory-cutover.sh`; evidence appended to a new
  `examples/04-inventory-service/CUTOVER.md`.
- **(d) Skills/MCP:** **camel-mcp** (`camel_runtime_*` to confirm routing), Newman re-run,
  gRPC call tracing to show the reserve/release flow.
- **(e) Deps / parallel:** after S7 + S8 + S9. SEQUENTIAL.
- **(f) Collision risk:** touches both flag locations; no code rewrite.
- **(g) Acceptance:** suite green through the proxy in the cutover state (all three checkout
  scenarios + inventory read); reversibility demonstrated; **negative check RED** when the
  inventory service is down (and the compensation path proven: force a decline, confirm the
  Release restored stock). **[Opus gate]:** human/Opus signs off the equivalence-gate pass
  **and** that the out-of-stock + payment-decline-rollback scenarios are genuinely satisfied
  across the service boundary.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(inventory): flag-gated cutover to gRPC reserve + CDC-backed reads; equivalence green incl. out-of-stock & decline-rollback, reversibility + negative check`

## S11 — DECOMMISSION monolith inventory module + cut writes + retire/redirect CDC  *(SEQUENTIAL, after S10)*  **[Opus gate]**  — **DONE** (`1f75436` — decommission monolith inventory: gRPC-only, SMELL #5 cured, Debezium retired; 404; equivalence 79/79 via proxy)
- **(b) Goal / DoD:** Make the inventory service the **sole owner and writer**: remove the
  monolith's `InventoryController`/`InventoryService`/`InventoryRepository`/`InventoryItem`
  and the `inventory.mode` flag (remote becomes the only path); the monolith no longer
  serves `/api/inventory` and no longer writes `inventory_items`. **Retire or redirect CDC**
  (the backfill/transition role is done; document whether the slot is dropped or the stream
  reversed — recommend dropping the inbound slot now that the service owns writes). Keep the
  `inventory_items` table in the shared schema as write-only history (same treatment as
  `reviews`/`notifications` in SMELL #1) or drop it — recommend keep-and-stop-writing.
  Update `SMELLS.md` (SMELL #5 **CURED**; SMELL #1 FK portion **CURED** for inventory) and
  `SixContextsSmokeTest`. `strangler.inventory.enabled=true` becomes the committed default.
- **(c) Creates/touches:** `examples/00-monolith/**` (remove inventory module + flag; drop
  gRPC-local fallback), `SMELLS.md`, `SixContextsSmokeTest.java`,
  `examples/01-strangler-proxy/application.properties` (committed default true), CDC
  teardown/registration docs. Lane M + proxy.
- **(d) Skills/MCP:** none new; Newman re-run; `mvn -f examples/00-monolith clean verify`.
- **(e) Deps / parallel:** after S10. SEQUENTIAL.
- **(f) Collision risk:** monolith (lane M) + proxy — single writer each. (A decommission
  `rm` may be blocked by the permission classifier — see r02-status; expect a human-action
  step if a delete is refused.)
- **(g) Acceptance:** `GET :8080/api/inventory` → 404; checkout still reserves via gRPC
  (inventory owns the decrement); monolith `clean verify` green; full suite green through the
  proxy; `SMELLS.md` #5 (+ #1 FK) marked cured with evidence; CDC slot lifecycle resolved.
  **[Opus gate]:** decommission reviewed; no orphaned in-JVM inventory path; replication slot
  not leaking.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(inventory): decommission monolith inventory module; gRPC service is sole owner/writer (SMELL #5 cured, FK decomposed)`

## S12 — Code-CI: extend the equivalence-gate job (Kafka Connect/Debezium + gRPC service + monolith)  *(SEQUENTIAL, after S11)*  **[Opus gate]**  — **DONE** (`3b2d122` — add inventory equivalence-gate + inventory-service own seed; fix notification job readiness probes post-S11; `c272ea2` — notification gate starts inventory service, checkout reserves via gRPC post-S11)
- **(b) Goal / DoD:** Add an `inventory-equivalence-gate` job to `.github/workflows/
  code-ci.yml` (sibling to review/notification gates) that brings up Postgres (logical
  replication) + Kafka + **Kafka Connect/Debezium** + the inventory gRPC service + the
  monolith, then runs the **full** behavior-equivalence suite through the proxy — including
  **Scenario 2/3** across the seam — and **fails on non-zero Newman exit**. Prove the gate
  truly gates with a **red-then-green**: disable the compensating Release (so a decline
  leaves stock decremented) ⇒ Scenario 3 goes RED, then restore ⇒ green.
- **(c) Creates/touches:** `.github/workflows/code-ci.yml` (extend; do not fork a workflow). Isolated.
- **(d) Skills/MCP:** **lgtm-github** (GitHub Actions conventions). Reuses S2 suite + S3/S6/S7 artifacts.
- **(e) Deps / parallel:** after S11. SEQUENTIAL.
- **(f) Collision risk:** low — single workflow file.
- **(g) Acceptance:** workflow green on push/PR with the gRPC reserve + CDC path exercised
  end-to-end; the deliberate break (no compensation) makes Scenario 3 RED (recorded), then
  green. **[Opus gate]:** Opus confirms the job genuinely exercises out-of-stock + decline
  compensation across the seam, not a green-only race, and that Connect/Debezium is healthy
  before the suite runs.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `ci(r05.x): inventory equivalence gate — gRPC reserve + Debezium CDC, out-of-stock & decline-rollback (red-then-green)`

## S13 — ch.19 diagram(s)  *(PARALLEL, after S6)*  — **DONE** (`6690217` — add inventory-extraction-topology + reserve-compensation figures, 2 paired SVG+excalidraw figures)
- **(b) Goal / DoD:** Paired **SVG + `.excalidraw`** figures: (1) the synchronous
  order→gRPC-reserve seam with the compensating Release on decline; (2) the Debezium CDC
  flow (WAL → Connect → owned DB backfill/sync); (3) the before/after FK decomposition
  (`order_items`→`inventory_items` entity FK vs. denormalized snapshot). House style, catalogued.
- **(c) Creates/touches:** `assets/diagrams/19-*.svg` + `.excalidraw`; append rows to
  `assets/diagrams/README.md` (serialize this catalogue — one writer).
- **(d) Skills/MCP:** **lgtm-diagram-generator** (+ `camel_render_route_diagram` for the ACL route as a source ref).
- **(e) Deps / parallel:** after S6 (seam shape known); PARALLEL with S14.
- **(f) Collision risk:** low per-SVG; serialize `assets/diagrams/README.md`.
- **(g) Acceptance:** SVGs render; `.excalidraw` sources present; catalogue updated.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(§19): gRPC reserve+compensation, Debezium CDC, and FK-decomposition diagrams (SVG + excalidraw)`

## S14 — ch.19 ADLC trace capture  *(PARALLEL, after S11 evidence exists)*  — **DONE** (folded into `c6638a2` — the chapter's "ADLC in Action" callout; sourced from S3–S12 evidence as captured, narrated tool output)
- **(b) Goal / DoD:** Capture the **"ADLC in Action"** trace for ch.19 as pre-captured,
  narrated tool output (DRQ-025): the `migrate-spring-to-quarkus` run (S5), the quarkus-grpc
  validation (S6), the Debezium connector registration/snapshot (S3), the camel-mcp ACL
  route validation (S9), the equivalence-gate + negative-check + compensation proof (S10),
  both human gates, and the DRQ-039…046 entries. The `_plans/` ledger is Exhibit A.
- **(c) Creates/touches:** captured transcripts under `_docs/_adlc-traces/` (or the ch.19
  companion), reusing the established callout template.
- **(d) Skills/MCP:** **lgtm-tutorial** (callout format); evidence sourced from S3–S12 runs (captured, not re-run live).
- **(e) Deps / parallel:** needs S11 evidence; PARALLEL with S13.
- **(f) Collision risk:** low; coordinate callout reuse with S15.
- **(g) Acceptance:** a complete Frame→Map→Plan→Generate→Verify→Operate→Reconcile trace
  exists as checked-in narrated output; both gates + equivalence + negative-check + the
  compensation proof visible.
- **(h) Tier:** Sonnet. Opus gate folded into S15.
- **(i) Checkpoint commit:** `docs(§19): ADLC-in-Action trace for the inventory extraction`

## S15 — ch.19 authored to the full bar (+ ch.18 FK-cut cross-ref)  *(SEQUENTIAL, after S11 + S13 + S14)*  **[Opus gate — 2k + footer]**  — **DONE** (`c6638a2` — author "CDC & Extraction 3 — Inventory": gRPC seam, CDC/log-tailing, saga-lite compensation; 2 figures; `_docs/19-cdc-and-extraction-3-inventory.md`, cross-ref in `_docs/18-shared-data-to-owned-data.md`)
- **(b) Goal / DoD:** Chapter 19 ("Transaction Log Tailing, CDC & Extraction 3 —
  Inventory") authored to the full bar: **≥2000 words excl. code/diagrams**, progressive,
  referencing the runnable `examples/04-inventory-service/` + the monolith gRPC adapter + the
  wired ACL route; the S13 diagrams embedded; a real **"ADLC in Action" callout** (S14); a
  **verification-status footer** naming the tests/demos run (equivalence suite incl.
  Scenario 2/3 across the seam, the Reserve/Release round-trip test, the negative check,
  Citrus route test, Dev Services, native). Must teach: synchronous gRPC as the hot-path seam
  + the proto-as-ACL-contract (DRQ-039), Debezium CDC (log-tailing, snapshot + stream) and
  why CDC-here-not-polling (DRQ-040 tradeoff vs ch.17), the sync-reserve/async-replication
  split (DRQ-041), the Reserve+compensating-Release taste-of-saga (DRQ-042, forward-ref
  ch.23), and the FK decomposition (DRQ-043, cross-ref ch.18). **Also update `_docs/18-*.md`
  prose** to cross-reference the realized FK cut (if ch.18 is authored by r05; else note the
  handoff).
- **(c) Creates/touches:** `_docs/19-transaction-log-tailing-cdc-inventory.md` (front matter:
  `title`, `order: 19`, `part: "Data Across the Seam"`, `description`, `duration`); minor
  cross-ref edit to `_docs/18-*.md`. Only *adds* a `_docs` file (+ one prose cross-ref) —
  never edits `_config.yml`/`_parts/`.
- **(d) Skills/MCP:** **lgtm-tutorial** (authoring + static validation) + **lgtm-jekyll** (build/word-count).
- **(e) Deps / parallel:** after S11 (behavior final), S13 (diagrams), S14 (callout). SEQUENTIAL.
- **(f) Collision risk:** low (single new `_docs` file + one cross-ref).
- **(g) Acceptance:** lgtm-jekyll/lgtm-tutorial validation green: **word count ≥2000**,
  example-dir present, verification footer present, links/diagrams resolve. **[Opus gate]:**
  Opus confirms the 2k-with-running-code bar, that the CDC + sync-reserve + compensation
  teaching is honest, and the callout is authentic.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `docs(§19): author Transaction Log Tailing, CDC & Extraction 3 — Inventory to the full bar`

## S16 — Reconcile, status, exit validation  *(SEQUENTIAL, last)*  **[Opus gate]**  — **DONE** (this reconciliation: `_plans/decisions.md` DRQ-039…046 + version matrix updated; this plan's steps and exit checklist marked DONE; commit range `fa9d020..c6638a2`)
- **(b) Goal / DoD:** Append r05/ch.19 outcomes to the ledger: `reconciliation.md` updated
  (artifact→source drift incl. the datamesh inventory-service + proto adaptation; zero
  unexplained drift, R4); `build-plan.md` status rows for ch.19 (and the ch.18 FK portion)
  marked DONE; `decisions.md` version matrix updated (quarkus-grpc version, Debezium/Connect
  image tags, Kafka tag). The ch.19 EXIT CHECKLIST (below) verified; clean resume boundary
  for r06 (payment/shipping sagas) recorded — the compensation introduced here is the bridge
  into ch.23.
- **(c) Creates/touches:** `_plans/reconciliation.md`, `_plans/build-plan.md` (status),
  `_plans/decisions.md` (matrix). Serialize all three.
- **(d) Skills/MCP:** none new; lgtm-jekyll full-site validation; re-run the equivalence suite once.
- **(e) Deps / parallel:** after all. SEQUENTIAL.
- **(f) Collision risk:** the three `_plans/*` are single-writer — only S1 and S16 write them in r05.
- **(g) Acceptance:** exit checklist all-green; matrix updated; status current. **[Opus gate]:**
  Opus signs off the whole extraction.
- **(i) Checkpoint commit:** `docs(r05.x): reconcile ch.19, update version matrix, mark inventory extraction DONE`

---

## ch.19 EXIT CHECKLIST (equivalence green + SMELL #5 cured + inventory owns its data over gRPC/CDC)
- [x] **Equivalence green across the seam:** the *same* behavior-equivalence collection
      passes through the proxy unchanged — **Scenario 1 (stock decrement), Scenario 2
      (out-of-stock 409), Scenario 3 (payment-decline ⇒ stock untouched via compensation)** —
      plus the new Inventory Context Contract read folder. (S10 `ef9c6d0`: equivalence green,
      service-down RED verified; S11 `1f75436`: 79/79 via proxy post-decommission.)
- [x] **SMELL #5 cured:** no raw `InventoryItem` entity crosses the order↔inventory seam;
      the ch.16 `InventoryAclRoute`/translator is wired for real; `GET :8080/api/inventory`
      → 404 after decommission. (S9 `06dbf57`, S11 `1f75436` — 404 confirmed, SMELLS.md updated.)
- [x] **SMELL #1 (FK) decomposed for inventory:** `order_items.inventory_item_id` FK dropped;
      `OrderItem` holds a denormalized snapshot; order read contract byte-for-byte unchanged.
      (S8 `381e9e3` — `V4__decompose_order_items_fk.sql`; SMELLS.md SMELL #1 marks the FK
      portion CURED.)
- [x] **Inventory owns its data over gRPC + CDC:** the Quarkus service owns its schema,
      serves `CheckStock`/`Reserve`/`Release`/`GetStock` over quarkus-gRPC, and was seeded +
      kept current by Debezium CDC during transition; it is the sole writer after cutover.
      (S5 `01f0b9a`, S6 `5523429`, S11 `1f75436`.)
- [x] **gRPC-vs-REST decided & applied:** gRPC for the synchronous reserve/check hot path
      (proto = ACL contract, DRQ-039); REST for the `/api/inventory` read surface via the proxy.
- [x] **CDC tool decided & applied:** Debezium Postgres connector on Kafka Connect in the
      podman stack (DRQ-040); logical replication enabled; slot lifecycle resolved; tradeoff
      vs ch.17 polling documented in-chapter. (S3 `7c437ea`; retired/slot-dropped at S11
      `1f75436`, `infra/debezium/README.md` "RETIRED post-cutover".)
- [x] **Reserve stays synchronous (DRQ-041); replication async.** Correctness preserved.
- [x] **Cross-service consistency handled honestly (DRQ-042):** Reserve + compensating
      Release keeps Scenario 3 green; documented as a taste-of-saga forward-ref to ch.23.
      (S7 `45618ca`; proven at S10 cutover.)
- [x] **Two-phase honored:** read surface Phase A (spring-compat) → Phase B (idiomatic),
      measured; gRPC server + Reserve/Release idiomatic-from-start (DRQ-044). (S5 `01f0b9a`
      → S6 `5523429`, `examples/04-inventory-service/MIGRATION.md`.)
- [x] **Reversibility shown** before decommission (both flags off → in-JVM local path green).
      (S10 `ef9c6d0`.)
- [x] **Negative check proven:** inventory service down ⇒ checkout 5xx + read folder RED;
      compensation proven by forcing a decline and confirming stock restored. (S10 `ef9c6d0`
      — "service-down RED verified".)
- [x] **Code-CI green:** the inventory equivalence gate exercises gRPC reserve + CDC
      end-to-end in GitHub Actions (red-then-green via disabling compensation). (S12
      `3b2d122` + `c272ea2` — `.github/workflows/code-ci.yml` `inventory-equivalence-gate`.)
- [x] **ch.19 authored ≥2000 words**, runnable example, embedded diagrams, real "ADLC in
      Action" callout, verification-status footer; ch.18 FK cross-ref updated. (S13 `6690217`
      2 figures; S15 `c6638a2` — `_docs/19-cdc-and-extraction-3-inventory.md`, cross-ref in
      `_docs/18-shared-data-to-owned-data.md`.)
- [x] **Ledger reconciled:** `decisions.md` DRQ-039…046 accepted + version matrix updated;
      this plan's steps and exit checklist marked DONE. (S16 — this reconciliation.)

## Biggest risks
1. **(Highest) The payment-decline compensation silently fails or is missed (H1/DRQ-042).**
   The cross-service decrement is no longer covered by the monolith's `@Transactional`; if
   the compensating `Release` doesn't run on *every* post-reserve failure path — or runs but
   fails — stock stays decremented after a decline and Scenario 3 goes RED, or (worse) drifts
   silently in production-shaped runs. Mitigation: baseline Scenario 3 green vs. the monolith
   (S2); re-run it on every S6/S7/S10 commit; S10 explicitly forces a decline and asserts the
   Release restored stock; S12 CI proves it red-then-green by disabling the compensation.
2. **Debezium replication-slot leak / logical-replication misconfig (H2).** A dead connector
   leaving a slot open fills the WAL disk; snapshot↔stream handoff errors corrupt the backfill.
   Mitigation: health-gate the connector before trusting the backfill; document + script slot
   teardown (S3/S11); pin image tags (R5/R11).
3. **The FK decomposition changes the order read contract (H3).** If the `OrderItem` snapshot
   doesn't reproduce sku/qty/unit-price exactly, `GET /api/orders/{id}` drifts. Mitigation:
   S8 asserts the order read contract is byte-for-byte unchanged; equivalence gate guards it.
4. **Two-protocol ACL drift (H4):** the monolith gRPC adapter (S7) and the proxy ACL route
   (S9) disagree on the `StockDto`↔proto mapping. Mitigation: one shared proto (S4, single
   writer); camel-mcp route validation; a translator test on both backends.

## Resume boundary for r06 (payment / shipping sagas)
r06 resumes from the `build-plan.md` status table. The **Reserve + compensating Release**
introduced here (DRQ-042) is the deliberate bridge into **ch.23's choreographed saga** — the
first compensation becomes a full saga with `order.placed`→`payment.captured` choreography;
the gRPC seam + CDC-owned inventory DB are the foundations payment/shipping build on. ch.28
later replaces this seam's JSON/proto-only contracts with Apicurio-registered schemas
(Avro/Protobuf), cross-referenced from here. No new extraction in r06 beyond payment+shipping.
