---
title: "The ADLC Safety Net"
order: 7
part: "The Agentic Development Lifecycle (ADLC)"
description: "Equivalence tests, adversarial verification and human gates make agentic generation trustworthy, shown by one full loop run on the Review extraction."
---

## Learning objectives

By the end of this chapter you can:

- Frame an acceptance criterion as a re-runnable artifact instead of a judgment call.
- Explain why the equivalence check must run against every build mode you ship, including native.
- Design a differential check that exposes a routing defect two backends with shared state would hide.
- Place the two human gates, plan approval and equivalence sign-off, and say what each one decides.
- Reconcile a run by recording what happened, including what did not match the plan.

## Why this matters

The ADLC is safe because the loop has an automated behavior-equivalence check, two places where a human stops and decides, and a habit of writing down what happened. It is not safe because an agent is careful.

The loop ran on the Review extraction, which produced `examples/00-monolith/`, `examples/01-strangler-proxy/` and `examples/02-review-service/`. It broke twice, and the net caught both breaks before either reached production. The primary sources are `examples/02-review-service/MIGRATION.md` and `examples/01-strangler-proxy/CUTOVER.md`, written when the work happened, and `tooling/newman/`, the equivalence collection itself.


## Frame: state intent on the record

A human writes down what "done" means before any tool runs, because a plan that lives only in someone's head cannot be approved, audited or disputed. For Review, the frame was one decision-log row:

```
From the decision log: the behavior-equivalence rule

The monolith's Newman collection is the behavior-equivalence suite (the
Newman collection captured against the monolith), run unchanged against
each extracted service and gating every extraction in CI via the
equivalence gate.
```

The row names an artifact (one Postman collection), an invariant (it is never edited to make a later service pass) and an enforcement point (CI). Had it said "the agent will judge whether the extraction looks right", there would be no automated check at all, only a model's self-assessment. A re-runnable artifact makes every later phase checkable rather than merely plausible.

## Map: find out what you are touching

The monolith's six bounded contexts share one Postgres database, one Spring Security realm and one deployable JAR. Map had to settle whether Review was independent enough to cut first. Reading the `review` package, its dependents and its data shape showed that Review was REST-only, had no synchronous call into or out of it from any other context, and touched one table, `reviews`, that nothing else joined against.

That made Review the right first slice: the first pass could exercise the whole seam machinery (strangler proxy, anti-corruption layer, Quarkus scaffold, equivalence check, flag-gated cutover, monolith-module decommission) without a second context confounding the result.

Map also fixed a scope fact. Review would arrive on Quarkus through the Spring-compatibility lift first (Phase A), and move to its own schema only in a later data-ownership pass. Until then the service and the monolith read and write the same `reviews` table. That one fact is why the second bug went undetected as long as it did.

## Plan: the first human gate

The Review plan had five steps: build the equivalence collection against the running monolith, stand up a flag-gated strangler proxy that defaults every request to the monolith, lift Review onto Quarkus with the Spring-compatibility extensions, refactor it to idiomatic Quarkus with measured before and after numbers, then flip the flag and decommission the monolith's copy. Each step had its own acceptance criterion and checkpoint, and none began until a human approved the plan.

Plan is the cheapest point to catch a wrong assumption. The approved plan deferred schema ownership as a scoping decision. A plan that gave Review its own database from the start would have taken longer to reach a first green run, and would also have hidden the bug the shared table later exposed. A person agreeing to that trade-off before any code existed is a judgment call the agent that drafted the plan cannot make for itself.

## Generate: Phase A, then Phase B

The two-phase strategy turns Generate into two Generate-then-Verify passes.

**Phase A** lifted Review's Spring MVC controller, Spring Data repositories and Spring-stereotype service onto Quarkus essentially unchanged, using `quarkus-spring-web`, `quarkus-spring-di` and `quarkus-spring-data-jpa` as a translation layer. The quarkus-agent MCP server's `migrate-spring-to-quarkus` process drove the pass, so version-matched, tool-grounded knowledge replaced the model's possibly stale memory of a migration path. The lift was trusted once the equivalence collection came back 16/16 green.

**Phase B** removes the shim and rewrites the same behavior as idiomatic Quarkus: Jakarta REST (`@Path`, `@GET`, `@POST`) instead of Spring MVC, Panache repositories instead of Spring Data JPA interfaces, a `@ServerExceptionMapper` instead of a `@RestControllerAdvice`. `MIGRATION.md` maps the change component by component: HTTP layer, data access, entities, service, exception mapping, DTOs, security, config, build and tests. The JPA entities needed zero edits, because Panache's repository pattern, unlike active record, works against ordinary `@Entity` classes.

## Verify catches bug 1: the native image's closed world

Phase B's first native-image build failed one assertion of the equivalence collection:

```
Captured: the equivalence run, Phase B, first native-image build
(examples/02-review-service, tooling/newman/mea.postman_collection.json,
"Review Context Contract" folder, 16 requests / 16 assertions)

newman run mea.postman_collection.json --folder "Review Context Contract" \
    --env-var baseUrl=http://localhost:8081

  ✔ 1a GET /api/reviews?sku=... -> 200, non-empty array
  ✔ 1b GET /api/reviews/{id} -> 200, same shape
  ✔ ...
  ✖ 4e POST /api/reviews (authenticated) with invalid rating ->
       expected 400, got 500

  15/16 assertions passed, 1 failed
```

An out-of-range rating should return `400 VALIDATION_FAILED` from the `@ServerExceptionMapper`. On the JVM it always had, because Jackson falls back to runtime reflection and discovers how to serialize `ApiError` when it needs to. GraalVM's native image has no such fallback. Its closed-world assumption requires every serialized type to be registered for reflection at build time, from a static scan. `GlobalExceptionMapper`'s methods returned the generic `jakarta.ws.rs.core.Response`, so the scanner never saw `ApiError` as a return type, never registered it, and the native binary crashed serializing a body it had no metadata for. Only invoking the compiled binary revealed the 500.

The defect was invisible to every JVM-mode check, including the full Phase A run, for a structural reason that no re-run would fix. The fix was `@RegisterForReflection` on the `ApiError` record, left in the code with a comment. Finding it required the check to be cheap enough to re-run against the native artifact specifically. A suite run once, against whichever build was convenient, would have shipped the bug.

```
Captured: the equivalence run, Phase B, native image, after the fix
(same collection, rebuilt native executable)

newman run mea.postman_collection.json --folder "Review Context Contract" \
    --env-var baseUrl=http://localhost:8081

  16/16 assertions passed, 0 failed
```

Native was attempted, not deferred, so the payoff could be measured instead of estimated:

```
Captured: examples/02-review-service/MIGRATION.md, before and after
(packaged artifacts run against the same podman-stack Postgres, profile prod)

| Build                                   | Startup | RSS     | Artifact size | Features |
|------------------------------------------|--------:|--------:|---------------:|---------:|
| Phase A: JVM, Spring-compat             | 1.492s  | ~316 MB | 46 MB          | 17       |
| Phase B: JVM, idiomatic                  | 1.431s  | ~304 MB | 45 MB          | 14       |
| Phase B: native image                    | 0.048s  | ~73 MB  | 87 MB          | 14       |
```

JVM to JVM, Phase B saves a few percent of memory from no longer loading the three translation extensions, and startup is within run-to-run noise. JVM to native is roughly thirty times faster startup and a little over four times less resident memory. The cost is the reflection gotcha above and a build that takes markedly longer than a JVM package step.

## The second human gate: sign-off on equivalence

A green run does not close Verify. A human has to look at "16/16" and decide it is sufficient evidence to trust the service. The collection checks only what it was written to check: the monolith's observable HTTP contract of status codes, response shapes and content types. That is why it can catch the wrong status code in one build mode and would miss a defect in anything it never asserts. Sign-off is a person taking responsibility that this coverage is the right coverage for this step, not confirming that an exit code was zero.

## Operate: flipping the flag catches bug 2

{% include excalidraw.html
   file="shared-table-routing-defect"
   alt="Two panels. Left: the Camel strangler proxy can route a request to the Spring monolith or the Quarkus review-service, but both read and write the same reviews table in the shared public schema, so a behavior-equivalence collection that only checks the HTTP response sees identical results either way. Right: the differential test that disambiguates them: stop the monolith, re-run the same route, and only the service the proxy's decision actually reaches can still answer; GET /api/reviews returns 200 from review-service while GET /api/orders still 500s against the dead monolith."
   caption="Figure 7.1: Why 49/49 green could not tell a routing bug from a correct cutover, and the check that could" %}

`examples/01-strangler-proxy/` fronts the monolith with a Camel route that inspects the incoming path and a flag, `strangler.review.enabled`, that picks the backend for `/api/reviews`. With the flag at its default of `false`, the equivalence collection run through the proxy (16 requests expanding to 49 assertions once the order, inventory and payment scenarios are included) passed:

```
Captured: demos/demo-equivalence.sh http://localhost:8888, flag OFF
(examples/01-strangler-proxy/CUTOVER.md, timeline step 1)

49/49 assertions passed, 0 failed.
Review served by: the monolith, reached through the proxy.
```

Flipping `strangler.review.enabled` to `true` should send Review traffic to `examples/02-review-service`. The same run again:

```
Captured: demos/demo-equivalence.sh http://localhost:8888, flag ON
(first attempt, before the routing defect was found)

49/49 assertions passed, 0 failed.
```

All 49 were false positives. The routing predicate tested whether `${header.CamelHttpPath}` started with `/reviews`, but the `platform-http` consumer, configured with `matchOnUriPrefix=true`, sets `CamelHttpPath` to the full incoming path, `/api/reviews`. The predicate never matched, `choice()` fell through to `otherwise()` on every request, and the flag had no effect on where Review traffic went. The assertions passed because the Phase A service reads and writes the same `reviews` rows in the same Postgres database as the monolith. A black-box check of the response cannot tell the correct service from the wrong one when both return identical data.

A behavior-equivalence collection verifies the response, not the route that produced it. When two backends share state, a routing defect and a correct cutover look the same from outside. The question to ask is whether the proxy's decision survives a condition under which the two backends cannot agree. The blunt check is to stop the monolith and retry the Review route:

```
Captured: differential monolith-down check
(examples/01-strangler-proxy/CUTOVER.md, timeline step 2)

monolith process stopped; proxy left running with the flag at `true`

GET /api/reviews?sku=SKU-WIDGET-001 -> 200 (served by review-service;
                                             monolith is down)
GET /api/orders                     -> 500 (still targets the dead
                                             monolith, as expected)
```

The first attempt returned the same failure as every other `/api/**` path, which proved the defect had been sending every request to the monolith regardless of the flag. The fix was a one-line predicate correction: match the full `/api/reviews` path the consumer presents, not a path relative to the route's `/api` prefix. The differential check was re-run with the monolith still down to confirm a real decision, not another coincidence:

```
Captured: differential monolith-down check, after the predicate correction
(examples/01-strangler-proxy/CUTOVER.md, timeline step 2)

GET /api/reviews?sku=SKU-WIDGET-001 -> 200 (served by review-service;
                                             monolith remains down)
```

Only then was the monolith's Review module decommissioned and the full collection re-run through the proxy:

```
Captured: demos/demo-equivalence.sh http://localhost:8888, after decommission
(examples/01-strangler-proxy/CUTOVER.md, timeline step 4)

49/49 assertions passed, 0 failed.
Review served by: examples/02-review-service (Quarkus, sole owner).
Everything else served by: the monolith (now five contexts).
```

## Necessary, not sufficient

{% include excalidraw.html
   file="adlc-safety-net-layers"
   alt="Three stacked layers. Layer 1, automated: the Newman behavior-equivalence collection, re-run against every candidate build, which caught the native-image @RegisterForReflection gap; removing this layer lets that regression ship silently. Layer 2, adversarial verification: the differential test that stopped the monolith and asked whether the proxy's routing decision survives one backend being gone, which caught the routing defect hidden behind two backends sharing one table; removing this layer lets that defect ship 49-for-49. Layer 3, human judgment: plan approval and equivalence sign-off, which decide whether the first two layers' coverage is the right coverage for the stakes; removing this layer means a green suite run gets trusted as self-certifying."
   caption="Figure 7.2: The safety net's three layers, and the specific bug each one stops" %}

The two bugs together state the rule. The native-image bug shows what the equivalence check is for: a cheap, automated, re-runnable check that catches what no JVM testing surfaces, by running against a build mode JVM tests do not exercise. The routing bug shows what it is not: it cannot tell a correct decision from coincidental agreement when the backends share state, and 49 green assertions proved nothing about the flag. Without the check, the native regression ships. Without an adversarial check aimed at its blind spot (remove the shared state, or here remove one backend), the routing defect ships behind a passing report.

The net has three layers, one stopping each failure:

| Layer | What it is | Bug it stopped | Without it |
|---|---|---|---|
| Automated | Newman equivalence collection, re-run against every candidate build | Native-image reflection gap | The regression ships silently |
| Adversarial | Differential check that removes a backend | Routing defect hidden by shared state | The defect ships 49-for-49 |
| Human | Plan approval and equivalence sign-off | Nothing in these two runs; it judges whether the first two layers cover the stakes | A green run is trusted as self-certifying |

"Verify" therefore means asking whether the collection's specific coverage could be fooled by the shape of this change, here two backends sharing one table, and reaching for a check without that blind spot when the answer is yes. A green run is evidence, not proof. Keep asking what would make it pass by accident until nothing comes to mind. That question is asked by a human or a reasoning-model validator. It's a skill, not a checklist item, and a scripted assertion cannot ask it of itself, which is why the second gate sits with a person.

## Reconcile: the ledger closes the loop

Neither bug stayed a verbal anecdote. The native-image finding is a dated section of `MIGRATION.md` with the root cause, the fix, and a javadoc note in the `ApiError` source. The routing finding is a dated section of `CUTOVER.md` with the predicate change and the differential check that proved it.

Both documents also record what was left unfixed. The `reviews` table was not split into its own schema during this cutover, although doing so would have kept the routing bug from hiding as long as it did, because per-context data ownership was outside the approved plan. That is drift from an idealized sequence, and the ADLC's answer is to write it down, scoped and dated, so the later data-ownership pass inherits an accurate starting point instead of a surprise.

The git history is the same ledger one layer down. Each checkpoint lands one phase of the loop: the reference Spring Boot monolith, its three-tier JUnit test layer, the equivalence collection, the flag-gated Camel strangler proxy, the Phase A lift, the Phase B refactor, and the cutover with decommission. Each commit message names the Verify result that let the step be trusted, so nobody has to rely on a retelling when they can read the commit and the file it changed. The decision log and build plan carry the same discipline at a coarser grain.

> **Decision**
>
> Treat a green equivalence run as evidence, not proof. Run it against every build mode you ship, and when the compared backends share state, add a check that removes one of them before you trust a cutover.

> **🤖 Explore with your agent**
>
> "Read `examples/01-strangler-proxy/CUTOVER.md` and the Camel route behind `strangler.review.enabled`. List two ways a routing predicate could silently match nothing while `demos/demo-equivalence.sh` still reports all assertions green, then write a monolith-down check for the Review route and say what it must return."
>
> Check: at `stage/01-review-extracted`, with the monolith stopped and the flag at `true`, `GET /api/reviews?sku=SKU-WIDGET-001` through the proxy returns 200 and `GET /api/orders` returns 500.

## Build, run, observe

The evidence is already in the repository. To reproduce the Review cutover state, run `git checkout stage/01-review-extracted` and start Postgres (`mea-postgres` on 5432), the monolith on 8080, review-service on 8081 and the strangler proxy on 8888. In the current tree the monolith no longer serves orders, so the monolith-down results below hold only at that tag.

- `bash demos/demo-equivalence.sh http://localhost:8888` runs the equivalence collection through the proxy on port 8888 and reports passed and failed assertions.
- `bash demos/demo-cutover.sh` walks the Review cutover recorded in `CUTOVER.md`: `/api/reviews` answered by review-service, every other `/api/**` path by the monolith, the monolith's own `/api/reviews` returning 404, and the full collection at 49/49 through the proxy.
- Stop the monolith with the flag at `true` and request `GET /api/reviews?sku=SKU-WIDGET-001` through the proxy. A 200 means the proxy's decision reached review-service; a 500 like the one from `/api/orders` means Review traffic still goes to the monolith.
- Remove `@RegisterForReflection` from the `ApiError` record, build the native image and run the "Review Context Contract" folder. The invalid-rating request returns 500 instead of 400.

## For deeper coverage

Daniel Oh's **Enterprise Agentic AI Workshop** uses `AGENTS.md` to govern agent behavior, the direct analog of the three-artifact ledger used here: `decisions.md`, `build-plan.md`, and the per-example `MIGRATION.md` and `CUTOVER.md` records, as lightweight, agent-maintained memory a human can audit without re-deriving it from a transcript. Michael Albada's *Building Applications with AI Agents* is the deeper treatment of why externalized, auditable memory matters for any agent architecture.
