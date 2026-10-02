---
title: "r02 Iteration Plan — The Walking Skeleton"
description: "Concrete, ordered, executable step plan for r02: public repo, Jekyll scaffold, podman stack, Spring monolith + Newman behavior-equivalence suite, Review extraction (two-phase) behind a Camel strangler proxy, a minimal Code-CI equivalence gate, ch.15 authored to the full bar, and the ADLC demonstrated once."
status: "execution plan — unblocks the r02 relay (Opus plan → Sonnet execute → Opus validate)"
iteration: r02
depends_on: [_plans/build-plan.md, _plans/decisions.md, _plans/research/reuse-map.md]
---

# r02 — The Walking Skeleton (execution plan)

> **Branch for all r02 work:** `r02-walking-skeleton`
> **Total steps:** 15 (S1 … S14, plus S-CI)
> **Relay tiering (DRQ-004):** every step is *executed* by **Sonnet**; steps marked
> **[Opus gate]** additionally require an **Opus validation** pass before their
> checkpoint commit. The whole iteration plan is itself the Opus "Plan" phase and
> must be **user-approved before any code is written** (ADLC Plan gate, §F.1).

## r02 definition of done (from build-plan §J, DRQ-011)
Site scaffold (lgtm-jekyll) + podman stack + **monolith built (ch.08–10 incl. the
behavior-equivalence suite)** + **Review extracted (ch.15) end-to-end, two-phase** +
**ch.15 authored to ≥2000 words with runnable code + tests + equivalence pass** +
**ADLC demonstrated once** (ch.07 loop + full plan→execute→validate trace in
`_plans/` + the ch.15 "ADLC in Action" callout) + **minimal Code-CI green (the
equivalence gate running in GitHub Actions, DRQ-030)**. Four risks retired at once
(R2 monolith demonstrates a pattern; strangle works; R3 ADLC is real; R6 a chapter
hits 2k with running code).

## Standing constraints applied to every step
Scope discipline (no speculative infra — nothing here that an r02 deliverable
doesn't require); conceptual coherence; security-by-design (OWASP/CIS — OIDC on
review, secrets hygiene, secure-by-default Camel, no creds in git); **SIMPLE git
only — `git -C <dir> …`, never `cd && git`**; **never push beyond
`github.com/patterncatalyst/modernizing-enterprise-applications`**; Conventional
Commits with the project's type/scope convention (`feat`/`fix`/`docs`/`chore`/
`refactor`/`ci`/`site` + scopes `rNN.x`, `§NN`, service names); **NO attribution
trailers** (no Co-authored-by). A subagent does **not** inherit a loaded skill —
each executor prompt must explicitly invoke/read the named skill.

## Parallelism overview
```
S1 (repo+branch, SEQUENTIAL, must be first)
 ├─ S2 site scaffold ─┐           (PARALLEL lane A — owns _config.yml/CSS/_parts)
 ├─ S3 podman stack ──┤           (PARALLEL lane B — owns infra/, compose)
 └─ S4 monolith code ─┘           (PARALLEL lane C — owns examples/00-monolith/)
S5 monolith tests        (SEQ after S4)
S6 Newman EQUIVALENCE SUITE (SEQ after S5 + S3)              [Opus gate]
S7 Camel strangler proxy (SEQ after S4; may overlap S5/S6)
S8 Review Phase A (lift) (SEQ after S6 + S7)                 [Opus gate — equivalence gate]
S9 Review Phase B (idiomatic + measured)  (SEQ after S8)     [Opus gate — equivalence gate]
S10 flag-gated cutover + decommission     (SEQ after S9)     [Opus gate]
S-CI minimal Code-CI (equivalence gate)   (SEQ after S10)    [Opus gate]
 ├─ S11 ch.15 diagram ───┐        (PARALLEL after S8)
 └─ S12 ch.07 ADLC loop ─┘        (PARALLEL after S10 evidence exists)
S13 ch.15 authoring to the bar   (SEQ after S10 + S11 + S12) [Opus gate — 2k+footer]
S14 reconcile + status + exit    (SEQ, last)                 [Opus gate]
```
**Single-writer / serialize (DRQ-022, §K):** `_config.yml`, `assets/css/site.css`,
`_parts/*`, nav, reactor `pom.xml`, `domain-model`/`contracts` modules,
`assets/diagrams/README.md`, and the three `_plans/*` ledgers. Each lane owns a
disjoint directory subtree so S2/S3/S4 never touch the same file.

---

## S1 — Repo init, ledger commit, working branch  *(SEQUENTIAL — must be first)*
- **(b) Goal / DoD:** The existing planning artifacts are committed to a fresh git
  repo on `main`, the **public** GitHub repo exists and `main` is pushed, and an
  empty **`r02-walking-skeleton`** branch is checked out so that *all* later work
  lands on the branch, never on `main`.
- **(c) Creates/touches:** `git init` in the project root; add `.gitignore`
  (JVM/Maven `target/`, `node_modules/`, `vendor/`, `_site/`, `.env`, OS cruft),
  `README.md` (minimal project blurb + house identity), `LICENSE`, `CLAUDE.md`
  (clone the DataMesh skeleton per reuse-map §5: overview, version matrix,
  conventions, skill/MCP table), `PRD.md` (13-section template, reuse-map §5).
  Commits existing `_plans/**` and `_source/**` verbatim.
- **(d) Skills/MCP:** **lgtm-github** (public repo create + first push + Conventional
  Commits). `gh` is already authed as user `patterncatalyst`.
- **(e) Deps / parallel:** none; **SEQUENTIAL**, gates everything.
- **(f) Collision risk:** none (first writer). Use `git -C <projectdir>` for all
  git ops. Confirm `_source/*.pdf` is wanted in-repo (large binary) before push.
- **(g) Acceptance:** `git -C <dir> rev-parse --abbrev-ref HEAD` → `r02-walking-skeleton`;
  `gh repo view patterncatalyst/modernizing-enterprise-applications --json visibility`
  → `PUBLIC`; `main` push succeeded; `_plans/` + `_source/` present in the GitHub tree.
- **(h) Tier:** Sonnet. No Opus gate (mechanical).
- **(i) Checkpoint commit:** `chore(r02.x): initialize repo, ledger, and working branch`
  (on `main` before branching; create branch after).

## S2 — Jekyll site scaffold (house style)  *(PARALLEL lane A, after S1)*
- **(b) Goal / DoD:** A buildable Jekyll site in the inherited house skeleton
  (`_docs`, `_parts`, `_plans`, `_example_pages`, `_layouts`, `assets/css/site.css`),
  accent **`--accent: #3d7a4e`** (+ derived `--accent-hover`/`--accent-soft`),
  `brand_emoji: 🏗️`, `github_username: patterncatalyst`,
  `github_repo: modernizing-enterprise-applications`. Card-grid homepage renders;
  `_parts/` seeded with Parts 0–10 (blurbs from §B.1). `_config.yml` cloned verbatim
  from reuse-map §5 (collections `docs`/`plans`[unpublished]/`example_pages`/`parts`;
  `exclude: examples/ scripts/ demos/ presentation/`).
- **(c) Creates/touches:** `_config.yml`, `assets/css/site.css`, `_layouts/*`,
  `_parts/00…10-*.md`, `index.md`/homepage, `Gemfile`. Does **not** author chapters.
- **(d) Skills/MCP:** **lgtm-jekyll** (scaffold + validation).
- **(e) Deps / parallel:** after S1; **PARALLEL with S3, S4**. Must land before S13.
- **(f) Collision risk:** **HIGH** — sole writer of `_config.yml`, CSS, `_parts/`,
  nav. No other step may touch these in r02; S13 only *adds* `_docs/15-*.md`.
- **(g) Acceptance:** `bundle exec jekyll build` succeeds; accent hex present in
  compiled CSS; 11 part pages render; lgtm-jekyll static validation passes
  (front-matter, nav).
- **(h) Tier:** Sonnet. No Opus gate (scaffold).
- **(i) Checkpoint commit:** `site(r02.x): scaffold Jekyll site in house style (migration-green #3d7a4e, 🏗️)`

## S3 — Local podman observability + infra stack  *(PARALLEL lane B, after S1)*
- **(b) Goal / DoD:** A single **podman** compose source stands up **Postgres +
  Kafka (KRaft)** at minimum, plus the LGTM observability stack + OTel Collector;
  image tags **pinned once in `.env`** to match Quarkus Dev Services defaults
  (inherited wire-compat gotcha, R5/R11). Apicurio optional — include only if the
  monolith/Review needs a registry (Review is REST-only, so Apicurio is **deferred**
  unless an executor shows a concrete r02 need → scope discipline).
- **(c) Creates/touches:** `infra/` (compose + Grafana datasources + OTel config +
  Postgres init), `.env` (pinned tags, no secrets committed), a `scripts/` up/down
  helper. **Must NOT** pull `lgtm-docker-stack` or DataMesh's Docker compose (R5).
- **(d) Skills/MCP:** **lgtm-podman-stack**.
- **(e) Deps / parallel:** after S1; **PARALLEL with S2, S4**. Must be up before S6.
- **(f) Collision risk:** HIGH on toolchain choice — single podman compose source
  only; never mix Docker. Owns `infra/`, `.env` exclusively.
- **(g) Acceptance:** `podman compose up` brings Postgres + Kafka healthy; a psql
  ping and a Kafka topic create/list succeed; Grafana reachable; `.env` tags match
  the versions in `decisions.md` matrix.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `chore(r02.x): add local podman stack (Postgres + Kafka + LGTM)`

## S4 — Spring Boot monolith (ch.08/09 subject)  *(PARALLEL lane C, after S1)*
- **(b) Goal / DoD:** A runnable **Spring Boot 3.x / JDK 25** monolith at
  `examples/00-monolith/` — one deployable, one DB, one JVM — with the **six
  bounded contexts** (`order`, `inventory`, `payment`, `shipping`, `notification`,
  `review`) as layered packages (controller→service→repository), **Spring MVC REST,
  Spring Data JPA, Bean Validation, Spring Security** on review endpoints
  (deliberately *not* hexagonal — the chosen-for-spring-compat surface, DRQ-029).
  Shared `OrderDto`/`OrderStatus`/`StockDto`/`ReviewDto`/`NotificationDto`/
  `OrderCreate`/`Topics` vocabulary (reuse-map §6). **One shared Postgres schema**
  with cross-context FKs/joins; **Flyway** migrations; **deterministic seed data**.
  REST surface for all six; an in-process order-placement flow across
  inventory→payment→shipping→notification in **one `@Transactional`**. springdoc
  OpenAPI exposed (seeds the behavior-equivalence suite). **All six deliberate smells planted and
  tagged** to their curing chapters (§D). Scale discipline: 3–9 files/module.
- **(c) Creates/touches:** `examples/00-monolith/pom.xml` (single module or thin
  reactor), `src/main/java/**` (six context packages), `src/main/resources/{db/migration,
  application.yml}`, seed loader, OpenAPI config. Keeps the monolith permanently
  in-repo (DRQ-024).
- **(d) Skills/MCP:** Spring authored **manually** (per §K — no Spring scaffold
  skill); **lgtm-quarkus** only later for the twin. Use **quarkus_searchDocs**/
  the Spring project context later in S8, not here.
- **(e) Deps / parallel:** after S1; **PARALLEL with S2, S3**. SEQUENTIAL internally
  (single reactor `pom.xml` — one writer).
- **(f) Collision risk:** HIGH on `examples/00-monolith/pom.xml` and the
  `domain-model` vocabulary — one writer; isolated under `examples/00-monolith/`.
- **(g) Acceptance:** `mvn -f examples/00-monolith/pom.xml package` builds; app boots
  against podman Postgres (or Testcontainers); seed data loads deterministically;
  all six REST context APIs respond; OpenAPI doc served; a smell-map comment/table
  lists all six smells with curing-chapter tags.
- **(h) Tier:** Sonnet. No Opus gate (its gate is S5/S6).
- **(i) Checkpoint commit:** `feat(monolith): runnable Spring Boot monolith with six contexts, seed data, and deliberate smells`

## S5 — Monolith test suite (ch.10 subject, part 1)  *(SEQUENTIAL, after S4)*
- **(b) Goal / DoD:** **JUnit** unit tests per service + **Testcontainers**
  integration tests over real Postgres (`@SpringBootTest`), green locally.
- **(c) Creates/touches:** `examples/00-monolith/src/test/java/**`, Testcontainers
  config. No production-code change beyond test hooks.
- **(d) Skills/MCP:** JUnit/Testcontainers (reuse EIP-Camel three-tier + App. K/N
  patterns, cited not copied).
- **(e) Deps / parallel:** after S4; SEQUENTIAL (shares the monolith module).
- **(f) Collision risk:** low (test tree only).
- **(g) Acceptance:** `mvn -f examples/00-monolith/pom.xml verify` green; Testcontainers
  spins Postgres with no external stack required.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `test(monolith): JUnit + Testcontainers integration suite`

## S6 — Newman behavior-equivalence suite (ch.10 subject, part 2 — THE EQUIVALENCE SUITE)  *(SEQUENTIAL, after S5 + S3)*  **[Opus gate]**
- **(b) Goal / DoD:** A **Newman contract collection** with **happy path +
  out-of-stock + payment-decline** captured against the *running* monolith; this
  collection becomes the **behavior-equivalence suite** (the Newman collection
  captured against the monolith, DRQ-014) re-run **unchanged** against the
  extracted Review service via the **equivalence gate**. Environment files for
  local (reuse DataMesh single-collection-many-environments + DDD-Obs
  failure-path payload shapes).
- **(c) Creates/touches:** `tooling/newman/mea.postman_collection.json`,
  `tooling/newman/local.postman_environment.json`, a `demos/demo-equivalence.sh` runner.
- **(d) Skills/MCP:** Newman (reuse CNDP App. O + DDD-Obs payloads). Runs against
  the S3 stack + S4 app.
- **(e) Deps / parallel:** after S5 **and** S3 (needs a live monolith). SEQUENTIAL.
- **(f) Collision risk:** low (own `tooling/`); versioned with the monolith (R8).
- **(g) Acceptance:** `newman run` is **green against the monolith** for all three
  scenarios; failure scenarios assert the *monolith's* actual error behavior (so
  equivalence is meaningful later). **[Opus gate]:** Opus confirms the three
  scenarios genuinely exercise the Review path + the order flow, not just 200s.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `test(equivalence): Newman behavior-equivalence suite (happy + out-of-stock + payment-decline)`

## S7 — Camel strangler proxy  *(SEQUENTIAL, after S4; may overlap S5/S6)*
- **(b) Goal / DoD:** A **Camel strangler proxy** fronting the monolith, routing by
  URI/content, with a **feature-flag (OpenFeature/flagd)** cutover switch that (for
  now) sends **all** traffic to the monolith. Secure-by-default Camel config
  (dynamic-URI allow-list, header boundaries — §F.4). This is the ch.14 seam
  machinery that S10 flips.
- **(c) Creates/touches:** `examples/01-strangler-proxy/` (Camel on Quarkus route +
  Citrus test + flag config). Does not modify the monolith.
- **(d) Skills/MCP:** **lgtm-camel** + **camel-mcp** (`camel_route_context`,
  `camel_validate_route`, `camel_render_route_diagram`).
- **(e) Deps / parallel:** after S4; may overlap S5/S6 (disjoint dir).
- **(f) Collision risk:** low (own example dir + per-route Citrus files).
- **(g) Acceptance:** proxy forwards all Review routes to the monolith; the
  **equivalence suite (S6) passes through the proxy** unchanged;
  `camel_validate_route` clean; Citrus route test green.
- **(h) Tier:** Sonnet. No Opus gate (its proof is the S8 equivalence-gate run).
- **(i) Checkpoint commit:** `feat(strangler): Camel strangler proxy with flag-gated cutover (default → monolith)`

## S8 — Review extraction, **Phase A** (lift onto Quarkus)  *(SEQUENTIAL, after S6 + S7)*  **[Opus gate — equivalence gate]**
- **(b) Goal / DoD:** The monolith's **Review** source lifted onto **Quarkus**
  largely unchanged via **Quarkiverse Spring-compatibility extensions**
  (`quarkus-spring-web`, `-di`, `-data-jpa`, `-security`, `-boot-properties` as
  applicable, DRQ-029 Phase A), with its **own schema from day one** (no shared-txn
  entanglement), OIDC-protected endpoints, an **ACL** (Camel message translator /
  content enricher) at the seam. **Passes the equivalence gate (S6 suite) unchanged.**
- **(c) Creates/touches:** `examples/15-review-service/` (Quarkus module), its own
  Flyway schema, ACL route. Reactor wiring isolated to this module.
- **(d) Skills/MCP:** **quarkus-agent** MCP — **`quarkus_skills` with the monolith
  dir to discover and follow `migrate-spring-to-quarkus`** (do NOT self-plan the
  migration); `quarkus_create`/`quarkus_start`/`quarkus_searchDocs`;
  **lgtm-quarkus**; **lgtm-camel**/**camel-mcp** for the ACL route.
- **(e) Deps / parallel:** after S6 (equivalence suite must exist) **and** S7 (proxy). SEQUENTIAL.
- **(f) Collision risk:** med — isolate under `examples/15-review-service/`;
  serialize any reactor-root `pom.xml` edit (one writer).
- **(g) Acceptance:** Review runs natively on Quarkus; **`newman run` of the S6
  collection is green against the extracted service** (via the proxy pointed at
  Review for Review routes). **[Opus gate]:** human/Opus signs off on the
  equivalence-gate pass (§F.1 Verify gate) before commit.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(review): Phase A — lift Review onto Quarkus via Spring-compat extensions, equivalence gate green`

## S9 — Review extraction, **Phase B** (idiomatic Quarkus + measured)  *(SEQUENTIAL, after S8)*  **[Opus gate — equivalence gate]**
- **(b) Goal / DoD:** Refactor Review off the compat shim to **idiomatic Quarkus** —
  Quarkus REST (RESTEasy Reactive), **Panache**, native CDI, SmallRye Config,
  Quarkus Security — and capture a **measured before/after** (startup time, memory,
  native-image size/build) as the teaching payoff (DRQ-029 Phase B). **Re-passes
  the equivalence gate unchanged.**
- **(c) Creates/touches:** `examples/15-review-service/**` (refactor), a
  `measurements.md`/table artifact for the before/after numbers.
- **(d) Skills/MCP:** **quarkus-agent** (`quarkus_skills` panache/rest, `quarkus_searchDocs`),
  **lgtm-quarkus**; native build via the Quarkus toolchain.
- **(e) Deps / parallel:** after S8. SEQUENTIAL.
- **(f) Collision risk:** low (same isolated module).
- **(g) Acceptance:** compat extensions removed; `newman run` against the
  equivalence suite **still green**; before/after metrics captured with method
  noted. **[Opus gate]:** equivalence-gate pass re-confirmed; metrics are real
  (not placeholder).
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `refactor(review): Phase B — idiomatic Quarkus (REST/Panache/CDI) with measured before/after`

## S10 — Flag-gated cutover + monolith module decommission  *(SEQUENTIAL, after S9)*  **[Opus gate]**
- **(b) Goal / DoD:** Flip the strangler flag so **Review traffic routes to the
  Quarkus service**; verify the equivalence gate green end-to-end through the proxy;
  **decommission the monolith's review module** (code removed/disabled, reversibly
  — reversibility is a design property, §E). Full loop proven once.
- **(c) Creates/touches:** flag config in `examples/01-strangler-proxy/`; removes/
  disables the monolith `review` package + its routes; updates seed if needed.
- **(d) Skills/MCP:** **camel-mcp** (`camel_runtime_*` to confirm routing),
  OpenFeature/flagd, Newman re-run.
- **(e) Deps / parallel:** after S9. SEQUENTIAL.
- **(f) Collision risk:** touches the monolith module (one writer) + proxy flag.
- **(g) Acceptance:** with the flag ON, equivalence gate green and Review served by Quarkus;
  with the flag OFF, traffic reverts to the (pre-decommission tag) monolith —
  reversibility demonstrated before decommission; post-decommission build green.
  **[Opus gate]:** cutover + decommission reviewed.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `feat(review): flag-gated cutover to Quarkus and decommission monolith review module`

## S-CI — Minimal Code-CI: equivalence gate in GitHub Actions  *(SEQUENTIAL, after S10)*  **[Opus gate]**
- **(b) Goal / DoD:** One minimal **GitHub Actions** workflow at
  `.github/workflows/code-ci.yml` that runs the **behavior-equivalence suite**
  (the Newman collection, S6) against the extracted, cut-over **Quarkus Review
  service** (S10) and **fails the build if it does not pass** — proving the
  "equivalence-gate-in-CI" thesis (DRQ-014, DRQ-030; build-plan §H) once,
  end-to-end, inside the walking skeleton.
- **(c) Creates/touches:** `.github/workflows/code-ci.yml` (new) — checks out
  the repo, builds `examples/15-review-service/`, brings up the minimal
  prerequisites Review needs to serve traffic (its own Postgres schema; reuse
  the S3 podman-stack service definitions as GitHub Actions service
  containers or an equivalent CI-local substitute), then runs
  `newman run tooling/newman/mea.postman_collection.json` against the running
  Review service and fails the job on a non-zero Newman exit code.
- **(d) Skills/MCP:** **lgtm-github** (GitHub Actions / workflow conventions);
  no new MCP tools — reuses the S6 equivalence suite and the S8–S10 Review
  service artifacts as-is.
- **(e) Deps / parallel:** after **S10** (needs the cut-over Quarkus Review
  service to exist). SEQUENTIAL.
- **(f) Collision risk:** low — new, isolated `.github/workflows/` file; no
  overlap with any other step's directories.
- **(g) Acceptance:** the workflow triggers on `push`/`pull_request`; the job
  executes the equivalence suite against the Quarkus Review service and is
  green; a **deliberately-broken Review** (one Review behavior intentionally
  violated, then reverted) makes the job go **red** — demonstrated once and
  recorded as evidence for this step. **[Opus gate]:** Opus confirms the
  workflow actually gates (verified red-then-green), not just a green-only run.
- **(h) Tier:** Sonnet execute; **Opus validate** (acceptance-bearing step).
- **(i) Checkpoint commit:** `ci(r02.x): minimal Code-CI — equivalence gate runs the Newman suite against Review in GitHub Actions`

## S11 — ch.15 diagram(s)  *(PARALLEL, after S8)*
- **(b) Goal / DoD:** At least one paired **SVG + `.excalidraw`** figure for ch.15
  (strangler proxy + ACL seam / cutover flow), house style, catalogued.
- **(c) Creates/touches:** `assets/diagrams/15-*.svg` + `.excalidraw`; append a row
  to `assets/diagrams/README.md` (serialize this catalogue — one writer at a time).
- **(d) Skills/MCP:** **lgtm-diagram-generator** (+ `camel_render_route_diagram` as
  a source reference).
- **(e) Deps / parallel:** after S8 (route shape known); PARALLEL with S12.
- **(f) Collision risk:** low per-SVG; serialize `assets/diagrams/README.md`.
- **(g) Acceptance:** SVG renders; `.excalidraw` source present; catalogue updated.
- **(h) Tier:** Sonnet. No Opus gate.
- **(i) Checkpoint commit:** `docs(§15): strangler-seam diagram (SVG + excalidraw)`

## S12 — ch.07 ADLC worked loop + captured tool output  *(PARALLEL, after S10 evidence exists)*
- **(b) Goal / DoD:** The ADLC **demonstrated once** (R3): the ch.07 "worked loop"
  established as the reusable **"ADLC in Action"** format, backed by
  **pre-captured, reproducible, narrated tool output** (DRQ-025) from the actual
  Review extraction (S8–S10) — quarkus-agent `migrate-spring-to-quarkus` trace,
  camel-mcp validation, the equivalence-gate result, the `DRQ-NNN` entries, agent tiers,
  and both human gates. The repo's own `_plans/` ledger is Exhibit A (DRQ-007).
- **(c) Creates/touches:** captured transcripts under `_docs/` companion assets
  (e.g. `_docs/_adlc-traces/` or alongside the chapter), and the "ADLC in Action"
  callout template. Drafts ch.07 loop content (full ch.07 prose completes in r03).
- **(d) Skills/MCP:** **lgtm-tutorial** (callout format); evidence sourced from the
  S8–S10 MCP runs (captured, not re-run live).
- **(e) Deps / parallel:** needs S10 evidence; PARALLEL with S11.
- **(f) Collision risk:** low; coordinate the callout template reuse with S13.
- **(g) Acceptance:** a complete Frame→Map→Plan→Generate→Verify→Operate→Reconcile
  trace exists as checked-in narrated output; both gates and the equivalence-gate
  result are visible; `_plans/` is referenced as the worked example.
- **(h) Tier:** Sonnet. Opus gate folded into S13/S14 review.
- **(i) Checkpoint commit:** `docs(§07): ADLC worked-loop trace and "ADLC in Action" callout format`

## S13 — ch.15 authored to the full bar  *(SEQUENTIAL, after S10 + S11 + S12)*  **[Opus gate — 2k + footer]**
- **(b) Goal / DoD:** Chapter 15 ("Extraction 1 — Review Service, the walking
  skeleton") authored end-to-end to the full bar: **≥2000 words excl. code/diagrams**,
  progressive, the **runnable** `examples/15-review-service/` referenced, the S11
  diagram embedded, a real **"ADLC in Action" callout** (from S12), and a
  **verification-status footer** naming exactly the tests/demos run (equivalence
  suite, Citrus, Testcontainers). Narrates both Phase A and Phase B with the
  measured numbers.
- **(c) Creates/touches:** `_docs/15-extraction-1-review-service.md` (front matter:
  `title`, `order: 15`, `part: "The Strangler Fig in Practice"`, `description`,
  `duration`); may add `_example_pages/` entry for the Review example.
- **(d) Skills/MCP:** **lgtm-tutorial** (authoring + static validation) +
  **lgtm-jekyll** (build/word-count check).
- **(e) Deps / parallel:** after S10 (behavior final), S11 (diagram), S12 (callout).
  SEQUENTIAL. Only *adds* `_docs/15-*.md` — never edits `_config.yml`/`_parts/`.
- **(f) Collision risk:** low (single new `_docs` file); do not touch S2's shared files.
- **(g) Acceptance:** lgtm-jekyll/lgtm-tutorial validation green: **word count ≥2000**,
  example-dir present, verification footer present, links resolve, diagram embeds.
  **[Opus gate]:** Opus confirms the 2k-with-running-code bar and callout authenticity.
- **(h) Tier:** Sonnet execute; **Opus validate**.
- **(i) Checkpoint commit:** `docs(§15): author Extraction 1 — Review Service to the full bar`

## S14 — Reconcile, status, exit validation  *(SEQUENTIAL, last)*  **[Opus gate]**
- **(b) Goal / DoD:** Append r02 outcomes to the ledger: `reconciliation.md`
  created/updated (artifact→source drift; zero unexplained drift, R4); `build-plan.md`
  status table rows for r02 marked DONE; `decisions.md` version matrix **pinned**
  (actual Quarkus / Camel / Spring Boot versions used). The **r02 EXIT CHECKLIST**
  (below) is verified. Clean resume boundary recorded for r03.
- **(c) Creates/touches:** `_plans/reconciliation.md` (new), `_plans/build-plan.md`
  (status), `_plans/decisions.md` (pinned matrix). Serialize all three (one writer).
- **(d) Skills/MCP:** none new; lgtm-jekyll full-site validation; re-run the equivalence suite once.
- **(e) Deps / parallel:** after all. SEQUENTIAL.
- **(f) Collision risk:** the three `_plans/*` are single-writer — this is the only
  step that writes them in r02 (besides S1's initial add).
- **(g) Acceptance:** exit checklist all-green; matrix pinned; status table current.
  **[Opus gate]:** Opus signs off the whole iteration.
- **(i) Checkpoint commit:** `docs(r02.x): reconcile, pin version matrix, and mark r02 status DONE`

---

## r02 EXIT CHECKLIST (mirrors build-plan §J r02 row + §L.2 bar)
- [ ] Public repo `patterncatalyst/modernizing-enterprise-applications` exists; all
      r02 work on `r02-walking-skeleton`, nothing committed to `main` after S1.
- [ ] Jekyll site builds; accent `#3d7a4e` + 🏗️; Parts 0–10 present.
- [ ] Podman stack (Postgres + Kafka + LGTM) comes up; tags pinned in `.env`; no Docker.
- [ ] Monolith at `examples/00-monolith/` runs; six contexts; seed data; all six
      deliberate smells planted + tagged; JUnit + Testcontainers green.
- [ ] Newman behavior-equivalence suite (happy + out-of-stock + payment-decline)
      green **against the monolith**.
- [ ] Review extracted two-phase (A: Spring-compat; B: idiomatic + measured),
      behind the Camel strangler proxy, **equivalence gate green unchanged** after each phase.
- [ ] Flag-gated cutover demonstrated + reversibility shown + monolith review module decommissioned.
- [ ] ch.15 authored ≥2000 words, runnable code, embedded diagram, real "ADLC in
      Action" callout, verification-status footer.
- [ ] ADLC demonstrated once end-to-end (ch.07 loop + captured/narrated trace +
      `_plans/` ledger as worked example).
- [ ] Minimal Code-CI green (the equivalence gate runs in GitHub Actions, S-CI).
- [ ] `reconciliation.md` present (zero unexplained drift); `build-plan.md` r02 rows
      DONE; `decisions.md` version matrix pinned.

## Resume boundary for r03
r03 ("Front matter + ADLC") resumes from the `build-plan.md` status table: author
Part 0 (00–02), Part 1 (03–04), Part 2 (05–07, expanding the S12 ch.07 loop to the
full bar), and finish Part 3 prose (08–10, elevating the S4/S5/S6 monolith into
chapters 08–10). The S12 "ADLC in Action" callout template and the S6
equivalence suite are the reusable foundations r03+ build on. No new service
extraction in r03 (next extraction, Notification, is r04). The S-CI minimal
Code-CI workflow is the seed that r08's full Site CI + Code CI build on.

## Resolved — CI scope for r02 (DRQ-030)
**CI scope for r02:** the r02 row in §J originally did not list CI, while
DRQ-014 says the equivalence gate "gates every extraction **in CI**." The user
approved adding a **minimal GitHub Actions Code-CI workflow in r02** (DRQ-030):
one job (**S-CI**) that runs the Newman behavior-equivalence suite against the
extracted Quarkus Review service, proving the equivalence-gate-in-CI mechanism
once as part of the walking skeleton. The remaining CI/CD surface (full Site
CI, the migration/cutover pipeline, GitOps, supply-chain scanning, §H) stays
deferred to r08 to hold scope discipline.
