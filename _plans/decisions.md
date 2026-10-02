---
title: "Decision Log — Modernizing Enterprise Applications"
description: "Append-only decision log (DRQ-NNN) in the DataMesh style. Seeds the fixed decisions and the major synthesis decisions. Round-1 planning only."
status: "seed — round-1 planning"
---

# Decision Log (DRQ-NNN)

Append-only. One row per decision: id, decision, rationale, status.
`status` ∈ {fixed (set by the user/brief, not relitigated), accepted (synthesis
decision, open to user override), proposed (recommended default awaiting user
confirmation — see "Open Questions")}.

## Version matrix (to be pinned in r02)

| Component | Target | Notes |
|---|---|---|
| JDK | 25 (SDKMAN) | via lgtm-quarkus/lgtm-camel toolchain |
| Maven | 3.9.x | |
| Quarkus | latest stable (pin in r02) | confirm via quarkus-agent MCP |
| Apache Camel | 4.2x stable | confirm via camel-mcp |
| Spring Boot | 3.x | monolith "before" |
| Postgres / Kafka / Apicurio | pinned once in `.env` to match Quarkus Dev Services | inherited wire-compat gotcha |

## Decisions

| ID | Decision | Rationale | Status |
|---|---|---|---|
| DRQ-001 | **Podman is the default toolchain** (lgtm-podman-stack); minikube (lgtm-minikube-stack) only for k8s-specific chapters (ch.29–31). No Docker inheritance from DataMesh. | User-fixed. Single compose source avoids Dev Services image-tag mismatch; one dev-loop/CI substrate. | fixed |
| DRQ-002 | **Fresh Spring Boot monolith** in the shipping/e-commerce domain (order/inventory/payment/shipping/notification/review), built FIRST, then strangled to Quarkus + Camel. | User-fixed. No clean monolith exists; a fresh one lets us plant deliberate smells each pattern cures. | fixed |
| DRQ-003 | **Audience = professionals**; every chapter ≥ 2000 words excl. code/diagrams; running code + tests throughout; cover ALL ~45 deck patterns and extend with modern/agentic topics. | User-fixed quality bar. | fixed |
| DRQ-004 | **Execution via lgtm-relay** (Opus plan → Sonnet execute → Opus validate). Round-1 = planning artifacts only; repo creation + first push deferred until the user approves. | User-fixed; matches "never push upstream without permission." | fixed |
| DRQ-005 | Honor standing constraints: **scope discipline, conceptual coherence, security-by-design (OWASP/CIS), never push upstream without explicit permission.** | User memory. | fixed |
| DRQ-006 | **ADLC = dedicated Part (Part 2) AND a woven "ADLC in Action" per-chapter callout** — not thread-only, not Part-only. | Requirement #F (ADLC *replaces* the SDLC) needs explicit instruction; the callout prevents hand-waving. Resolves tension T3. | accepted |
| DRQ-007 | **The book's own `_plans/` ledger** (`decisions.md` / `build-plan.md` / `reconciliation.md`) IS the worked ADLC example — the book is built the way it teaches. | Strongest, zero-gold-plating proof; artifacts we build anyway. | accepted |
| DRQ-008 | **Narrative = chronological migration timeline**, patterns introduced at the moment the migration forces each. Not a pattern-catalog structure. | Converged across all three candidates; preserves motivation ("why this pattern, why now"). | accepted |
| DRQ-009 | **33 core chapters / 11 parts (Part 0–10) + Appendices.** | Defensible middle: 16 under-serves 45 patterns + running code; 49 over-scopes. Each extraction earns a chapter; demotions keep it bounded. Resolves T2. User confirmed 33/11 as-is, no compression of Parts 9–10. Resolves O2. | accepted |
| DRQ-010 | **9 iterations** (r01 planning + r02 walking skeleton + r03–r09). | Keeps risk plan's walking-skeleton r02; compresses outcome's 10 where parts combine. Resolves T7. | accepted |
| DRQ-011 | **r02 = thin walking skeleton** retiring the four biggest risks at once (monolith demonstrates a pattern; strangle works; ADLC is real; a chapter hits 2k with running code). | Risk-first mechanism; prove hard parts once before scaling. | accepted |
| DRQ-012 | **Review service is the walking-skeleton / first extraction** (not Inventory). | REST-only, no sync deps → least surface to get end-to-end AND the "anticlimactic first win." Resolves T1 (grafts outcome+simple over risk). | accepted |
| DRQ-013 | **Extraction order = rising difficulty:** review → notification → inventory → payment → shipping → order+gateway. | Each step forces exactly the next pattern; monotonic difficulty curve. Resolves T4. | accepted |
| DRQ-014 | **The monolith's Newman collection is the behavior-equivalence suite** (the Newman collection captured against the monolith), run unchanged against each extracted service and gating every extraction in CI via the equivalence gate. | Converged strong idea; makes "preserves behavior" checkable; per-chapter + CI acceptance gate. | accepted |
| DRQ-015 | **Reuse ≈ 60% adapt/reuse, 40% fresh.** Reuse theory/appendices/infra/harnesses; author fresh the monolith, the strangler narrative + per-step code, Part 2 (ADLC), and CI/CD-for-migration. | Means, not goal; leverages the reuse-map while keeping the spine original. | accepted |
| DRQ-016 | **Appendices are reuse-as-is / adapt / reference; zero net-new appendix prose in r02–r06** (ported in r08). | Scope discipline; lower-value deck patterns land in appendices. | accepted |
| DRQ-017 | **Demotions:** Caching → appendix; Event Sourcing → light/optional (CQRS stays load-bearing via the gateway); Fixed deployment → a paragraph; Netflix OSS/MS-1.0 → historical framing; Repurchase/Retire/Retain + Container-Native Virtualization → mentioned non-targets; Loan Broker/Bond Trading → reference appendix. | Each demotion is an explicit decision, not an omission; keeps coverage honest without reaching 49 chapters. Resolves T5. | accepted |
| DRQ-018 | **Target architecture matches the DataMesh "after"** (same domain shape, DTOs, Avro contracts, choreography chain) so examples cross-reference it rather than re-author. | reuse-map §6; mutual consistency across sibling projects. | accepted |
| DRQ-019 | **Testing spine = EIP-Camel three-tier strategy + Citrus + Testcontainers/Dev Services + Newman**; verification-status footer on every chapter. | Most mature testing material across the four projects; satisfies "testing throughout." | accepted |
| DRQ-020 | **Security-by-design is part of the ADLC Verify gate** (SBOM, CVE/dependency scan, Camel secure-by-default, secrets hygiene) + in CI. | OWASP/CIS standing constraint; not a late audit. | accepted |
| DRQ-021 | **Deck rebuilt last (r09)** with lgtm-presentation as a ~45–55-slide companion mirroring the book's parts; diagrams regenerated via lgtm-diagram-generator. | Prevents the deck drifting ahead of the chapters. | accepted |
| DRQ-022 | **Single-writer discipline** for shared roots (`_config.yml`, CSS, `_parts/`, reactor `pom.xml`, `domain-model`/`contracts`, diagram catalogue, `_plans/*`); parallelize per-file chapters/examples. | Prevents cross-relay collisions. | accepted |
| DRQ-023 | **Accent color = "migration green" `--accent: #3d7a4e`; brand_emoji = 🏗️.** | Distinct from amber (CNDP/EIP), red (DataMesh), teal (DDD-Obs). User confirmed migration green over the blue alternative. Resolves O1. | accepted |
| DRQ-024 | **Keep the monolith permanently in-repo** at `examples/00-monolith/` as the living "before" and the equivalence suite's referent. | The equivalence suite needs a running referent; cross-references stay valid. User confirmed. Resolves O5. | accepted |
| DRQ-025 | **ADLC chapters: agent/MCP tool usage is always shown as pre-captured, reproducible, narrated tool output** (not run live-where-cheap). | User confirmed: determinism/reviewability across every chapter outweighs the marginal proof-value of occasional live runs. Resolves O3. | accepted |
| DRQ-026 | **Spring→Quarkus depth: FULL DEPTH for every extracted service** (review, notification, inventory, payment, shipping, order+gateway) — the `migrate-spring-to-quarkus` process runs in complete detail for all six, not full-once-for-Review-then-summarized. | User confirmed, reversing the earlier "full once, summarize the rest" default. **Scope impact:** enlarges r04–r07 (full per-service migration detail + tests each). **Mitigation:** a repeatable per-service chapter template with the mechanical detail centralized in a shared appendix keeps chapters from ballooning. Resolves O4. | accepted |
| DRQ-027 | **Companion `.docx` deferred past r09.** | Not required for the book+code+deck trio; avoids gold-plating. The deck `.pptx` ships in r09; the `.docx` companion is explicitly out of scope until after r09. User confirmed. Resolves O6. | accepted |
| DRQ-028 | **Reference Daniel Oh's Enterprise Agentic AI Workshop** (Quarkus/Java agentic patterns — LangChain4j-style `@Agent`/`@SystemMessage`, parallel/supervisor/plan-and-execute workflows) as a primary agentic reference alongside the six books; map 4 of its patterns into Part 2 (ch.05–07) as "Further reading" sidebars; add one OPTIONAL Appendix V ("Agentic Patterns Inside the Modernized System") for in-system agents, scope-guarded and deferrable past r09. | User-provided colleague example; Quarkus-native and directly analogous to our ADLC; reinforces the method without growing the chapter count — reference, not expand. | accepted |
| DRQ-029 | **Spring→Quarkus extractions use a two-phase strategy: Phase A lift onto Quarkus via Quarkiverse Spring-compatibility extensions (spring-web/di/data-jpa/security/etc.), Phase B refactor to idiomatic Quarkus, measuring before/after.** | User-provided; de-risks each extraction (fast equivalence-suite-passing bridge) and yields a measurable idiomatic-migration teaching moment; repeatable per-service template. | accepted |
| DRQ-030 | GitHub Actions is the project CI/CD platform; a minimal Code-CI (equivalence gate) ships in r02, and Part 10 explicitly documents all GitHub Actions workflows (equivalence gate + deploy jobs). | User-approved; proves the equivalence-gate-in-CI thesis in the walking skeleton and keeps the CI/CD teaching concrete. | accepted |
| DRQ-031 | Rename "equivalence oracle" → "behavior-equivalence suite" (the Newman collection) and "equivalence gate" (its CI check), dropping the word "oracle" to avoid confusion with Oracle Database (the stack uses PostgreSQL). | User-requested clarity. | accepted |

## Open Questions (consolidated — see build-plan return summary)

All six resolved by explicit user decision; no open questions remain from this set.

- **O1 — RESOLVED.** Accent color / emoji → "migration green" `#3d7a4e` + 🏗️ (DRQ-023).
- **O2 — RESOLVED.** Chapter-count tolerance → 33/11 accepted as-is, no compression (DRQ-009).
- **O3 — RESOLVED.** ADLC demo mechanism → always pre-captured/narrated tool output, not live-where-cheap (DRQ-025).
- **O4 — RESOLVED.** Spring→Quarkus depth per service → full depth for every service, not full-once-summarize-rest (DRQ-026).
- **O5 — RESOLVED.** Keep the monolith in-repo permanently at `examples/00-monolith/` (DRQ-024).
- **O6 — RESOLVED.** Defer the companion `.docx` past r09 (DRQ-027).
