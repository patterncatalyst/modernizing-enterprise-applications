# r02 "Walking Skeleton" — status & resume record

_Last updated: 2026-10-02. Saved before a user break/possible reboot._

## Where we are

Branch `r02-walking-skeleton`, merged to `main` at this checkpoint. The walking
skeleton's **code is complete and gated**: the Spring monolith runs and is tested,
the Review context is fully extracted to Quarkus (Phase A lift → Phase B idiomatic
→ flag cutover → monolith decommission), and the behavior-equivalence suite gates
it both locally and in **green GitHub Actions CI**.

## Completed steps (all committed on `r02-walking-skeleton`)

| Step | What | Commit | Gate |
|------|------|--------|------|
| S1 | repo init + branch | ee54b1f | — |
| — | rename oracle→equivalence; CI step; GH Actions note | a48fe4b | — |
| S2 | Jekyll site (green #3d7a4e, 11 parts, 33 stubs) | a668511 | — |
| S3 | podman stack (Postgres 16, Kafka 3.8 KRaft, LGTM/OTel) | c1cdf3b | — |
| S4 | Spring Boot monolith (6 contexts, 6 tagged smells) | a0088a5 | — |
| S5 | three-tier JUnit tests (45 green) | e6d62bc | — |
| S6 | behavior-equivalence suite (Newman, 49 assertions) | 6cb113e | Opus PASS |
| S7 | Camel strangler proxy (flag-gated) | 4927e1b | — |
| S8 | Review Phase A — Spring-compat lift to Quarkus | 5479d49 | Opus PASS |
| — | DRQ-032 non-trivial Quarkus example bar (datamesh) | 7362aa0 | — |
| — | codetabs Spring⇄Quarkus (DRQ-033) | c475a12 | — |
| S9 | Review Phase B — idiomatic Quarkus (native 0.048s) | ad5a1c1 | Opus PASS |
| §05 | SDLC-vs-ADLC figures | 6d8b63e | — |
| §05 | chapter "From SDLC to ADLC" (~3.8k words, 4 figs) | a354966 | verified |
| S10 | strangler cutover + decommission (routing bugfix) | e11f53e | Opus PASS |
| S-CI | GitHub Actions equivalence-gate workflow | d22ab82 | **live run GREEN** |
| §07 | chapter "The ADLC Safety Net" (~3.9k words) | 9e6f5be | verified |
| §15 | strangler-extraction + two-phase-migration figures | 8ba2b56 | — |

## Remaining in r02 (resume here)

- **S13** — author ch.15 "Extraction 1: the Review service" to the ≥2000-word bar,
  embedding the two §15 figures (already committed) and the real Phase A/B + cutover
  story. (Stub currently at `_docs/15-extraction-1-review-service.md`.)
- **S14** — reconcile: pin the version matrix in `_plans/decisions.md`
  (Spring Boot 3.5.16, springdoc 2.8.17, Quarkus 3.40.1, Camel 4.22.1, Postgres 16,
  Kafka 3.8.0, otel-lgtm 0.8.1); fix the `examples/02-review-service` vs plan's
  `examples/15-review-service` path reference in `r02-plan.md`; tick the r02 EXIT
  CHECKLIST; set r02 status DONE.

Because r02 was merged to `main` at this checkpoint, resume S13/S14 on a fresh
branch off `main` (e.g. `r02-finish` or fold into `r03`).

## Known issues / user action items

1. **GitHub Pages not enabled** → the `pages.yml` workflow is RED on every push
   (code builds fine; it's a repo setting). To publish the site: Settings → Pages →
   Build and deployment → Source = **GitHub Actions**, or
   `gh api -X POST repos/patterncatalyst/modernizing-enterprise-applications/pages -f build_type=workflow`.
   Deliberately NOT enabled unilaterally (publishing is outward-facing). The
   `code-ci.yml` equivalence-gate workflow is GREEN.
2. **Monolith `security/SecurityConfig.java` + spring-security deps are now dead
   weight** after Review was decommissioned. The decommission agent's `rm` was
   blocked by the permission classifier ("Security Weaken") and correctly not
   routed around. Decide: remove them (human action) or keep as vacuous.
3. **`demo-cutover.sh` / `CUTOVER.md`** should note that running the monolith jar
   directly needs `SPRING_DATASOURCE_PASSWORD=monolith_dev_only` (compose/.env
   injects it otherwise). Fold into S14.

## Local runtime state (after reboot)

- The podman stack (`mea-postgres`, `mea-kafka`, `mea-lgtm`) was left running; after
  a reboot bring it back with `scripts/stack-up.sh` (pinned tags in `.env` /
  `.env.example`). Original decks are reference-only under gitignored `_source/`.
- App services (monolith :8080, review :8081, proxy :8888) are stopped. Build:
  `mvn -f examples/00-monolith package`, `./mvnw` in the two Quarkus projects.
- Run the equivalence suite: `demos/demo-equivalence.sh http://localhost:8888`
  (needs monolith + review-service + proxy up; flag default = true).
