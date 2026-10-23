---
title: "Verification log"
layout: plan
---

Verification-status footers removed from reader-facing chapters (site chrome shows reader content only; banked here, plans collection output:false). One section per chapter, verbatim.

## 00-introduction

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Its factual claims point at artifacts the
`examples/` directories make checkable elsewhere in this book: the six-context
monolith and its deliberate
smells (Part 3), the behavior-equivalence suite and the equivalence gate
(Chapter 10 and Chapter 7), and the two real defects the first extraction
surfaced (Chapter 7, `examples/02-review-service/MIGRATION.md`,
`examples/01-strangler-proxy/CUTOVER.md`).*

## 01-prerequisites

*Verification status: <span class="status status--verified">verified</span>. The commands here are transcribed from the project's own tracked scripts and workflow — `compose.yaml`, `.env.example`, `scripts/stack-up.sh`/`stack-down.sh`, `demos/demo-equivalence.sh`, and `.github/workflows/code-ci.yml` — the same ones CI runs. On a fresh clone, confirm `scripts/stack-up.sh` reports the services healthy and `demos/demo-equivalence.sh http://localhost:8888` is green before continuing.*

## 02-project-ledger

*Verification status: not applicable — this is a conceptual chapter about the
project ledger's planning artifacts, not a runnable example under `examples/`.
The artifacts it describes live in this repository under `_plans/`
(`decisions.md`, `build-plan.md`, and the per-iteration plan and status
files). The one artifact it notes is *not yet* written — a populated
`reconciliation.md` — is named as such above rather than asserted.*

## 03-modernization-as-engineering

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Its factual claims point at artifacts
this book makes checkable elsewhere: the measured Phase A/B/native numbers
and the 16/16 equivalence-suite results cited above are recorded in full in
`examples/02-review-service/MIGRATION.md`; the five remaining deliberate
smells are catalogued with in-code tags in Chapter 9; and the coupling
vocabulary used to reason about them is developed in full in Chapter 13.*

## 04-strategies-and-assessment

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Its claims about the extraction order are
checkable against `examples/00-monolith/SMELLS.md` (smells #2 and #6, which
name the coupling this chapter's "order last, review first" argument depends
on). The strategy
and strangler-fig mechanics named here are built out, with running code, in
Part 3 and Part 5.*

## 05-from-sdlc-to-adlc

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. The codetab above illustrates a Phase A
lift pattern described in Part 5; it is not itself a standalone runnable
artifact.*

## 06-agents-skills-mcp

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. The captured transcripts quoted above are
drawn from this project's example artifacts
(`examples/02-review-service/AGENTS.md`, `examples/01-strangler-proxy/`) or
reconstructed in the same narrated-and-checked-in style; the
underlying claims — the relay tiers, the `lgtm-*` skill names, the two MCP
servers and their tool names — are checkable against this repository directly.
Chapter 7 is where this chapter's
claims about what the safety net actually catches get their full worked
proof.*

## 07-adlc-safety-net

*Verification status: the two findings this chapter centers on are real,
dated, and recorded in this project's example trees —
`examples/02-review-service/MIGRATION.md` (the native-image
`@RegisterForReflection` gap) and `examples/01-strangler-proxy/CUTOVER.md`
(the routing predicate false positive). What remains unverified is only the claim that
the deferred per-context schema split (Part 6) will fully retire the
shared-table blind spot this chapter documents; that claim is tracked, not
assumed, and gets its own verification pass when ch.18/19 ship.*

## 08-designing-the-monolith

*Verification status: <span class="status status--verified">verified</span>. The code, schema, and API surface described above are real in `examples/00-monolith/`, which builds green under `mvn verify` in CI. The REST endpoint table, the Flyway migration walkthrough, and the Phase A codetabs are all drawn from that tree.*

## 09-deliberate-smells

*Verification status: <span class="status status--verified">verified</span>. Every excerpt is quoted verbatim from `examples/00-monolith/`, and the `grep -rn 'SMELL[ch.'` command runs against that tree. Each smell's cure lands in the chapter named on it (ch.16–26), every extraction built and CI-gated green.*

## 10-testing-the-monolith

*Verification status: the test and tooling code this chapter walks is real
and committed — `examples/00-monolith/src/test/` and `tooling/newman/` — and
runs green under `mvn verify`. The counts quoted here (thirteen classes,
forty-one `@Test` methods, forty-five executions once the one parameterized
test expands) are taken directly from the files on disk; re-run `mvn verify`
to confirm them, since adding or removing a test can change them.*

## 11-ddd-and-hexagonal

*Verification status: <span class="status status--verified">verified</span>. Every excerpt is quoted verbatim from `examples/00-monolith/` and `examples/02-review-service/`; the DDD and hexagonal classifications are this chapter's reasoning about that real code. The extraction-order forecast is borne out by ch.17, 19, 23, 24, and 26, all built and CI-gated green.*

## 12-event-storming

*Verification status: <span class="status status--verified">verified</span>. Every excerpt is quoted verbatim from `examples/00-monolith/`, and the `Topics.java` constants are greppable in that tree. The event-to-seam mapping is realized by the extractions in ch.17, 19, 23, and 24.*

## 13-coupling-and-modular-monolith

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`; `examples/00-monolith/` is unchanged
from Chapter 10. Every coupling score and code excerpt above is drawn
directly from that module's current source and from `SMELLS.md`'s mapping
of each smell to its curing chapter, both independently checkable with
`grep -rn 'SMELL\[ch\.' examples/00-monolith/src`. What remains unverified
is forward-looking: that the Ca/Ce ranking this chapter derives for the six
contexts actually predicts each extraction's real difficulty once Chapters
15 through 26 run it, which can only be confirmed as each chapter's
equivalence-gate result lands.*

## 14-strangler-fig-pattern

*Verification status: <span class="status status--verified">verified</span>. The Newman runs, the predicate bug, and the decommission sequence are drawn from `examples/01-strangler-proxy/`'s `CUTOVER.md` and `README.md`; the `StranglerProxyRoute.java` reading tracks the current route source, which Code CI exercises.*

## 15-extraction-1-review-service

*Verification status: <span class="status status--verified">verified</span>. Every artifact cited — `examples/02-review-service/`, `examples/01-strangler-proxy/` and its `CUTOVER.md`, and `tooling/newman/mea.postman_collection.json` — is real, runnable code gated by CI's review equivalence gate; the metrics are from the service's own `MIGRATION.md`.*

## 16-content-based-routing-acl

*Verification status: <span class="status status--verified">verified</span>. The `SMELL[ch.16]` excerpts and the content-based router from `StranglerProxyRoute.java` are real code exercised by Code CI, and the `choice`/`enrich`/`transform` semantics are from Camel 4.22.1's catalog. The `InventoryAclRoute`/`StockDtoTranslatingStrategy` listing is an illustrative sketch; the production anti-corruption layer ships with the inventory extraction in ch.19 (`examples/04-inventory-service`).*

## 17-extraction-2-notification-service

*Verification status: <span class="status status--verified">verified</span>. Every artifact cited — `examples/00-monolith/`'s outbox and relay, `examples/03-notification-service/`, the proxy's `CUTOVER.md` (including the negative check), the Newman "Notification Context Contract" folder, and CI's `notification-equivalence-gate` — is real code and dated evidence in the repository, gated green.*

## 18-shared-data-to-owned-data

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`; `examples/00-monolith/`,
`examples/02-review-service/`, and `examples/03-notification-service/` are
all unchanged by this chapter. Every schema definition, entity, and javadoc
quoted above is drawn directly from those modules' current source and is
independently checkable with `grep -rn 'SMELL\[ch\.18\]' examples/00-monolith/src`
and by reading `examples/02-review-service/README.md`,
`examples/00-monolith/.../common/Customer.java`, and
`examples/03-notification-service/.../db/migration/V1__create_notifications_table.sql`
directly. The two illustrative code snippets in the denormalization-move
section are explicitly marked as sketches of Chapter 19's target shape, not
claims about existing code, and should not be treated as verified until
Chapter 19 actually builds and runs them. What remains unverified going
forward is the plan itself: that CDC backfill, the gRPC seam, and the
extended negative-check discipline this chapter previews actually hold up
against Inventory's real data and real traffic, which only Chapter 19's own
equivalence-gate run can confirm.*

## 19-cdc-and-extraction-3-inventory

*Verification status: <span class="status status--verified">verified</span>. Every artifact cited — `examples/04-inventory-service/` (proto, gRPC impl, CDC consumer and its test, Flyway migrations), the monolith's `RemoteInventoryClient` and compensation, `infra/debezium/`'s connector and retirement record, the proxy's `CUTOVER.md` (negative check and hybrid-state finding), the Newman Inventory Context Contract folder, and CI's inventory equivalence gate — is real code and dated evidence, gated green.*

## 20-outbox-pattern-done-right

*Verification status: <span class="status status--verified">verified</span>. This chapter adds no new code: it reads the outbox mechanism in `examples/00-monolith/` and `examples/03-notification-service/` that ch.17 already exercised end-to-end, including the idempotency negative check. The cleanup/retention and poison-message gaps it names are explicitly future hardening, not implemented here.*

## 21-event-sourcing-and-cqrs

*Verification status: <span class="status status--verified">verified</span>. The code described is the already-running CQRS-lite behavior in `examples/00-monolith/` and `examples/03-notification-service/` exercised in ch.17; this chapter reframes it and adds no new code. The `eventStore.loadEvents(...)` fold is an intentional illustration only — there is no event store in this repository, by design. The GraphQL read-model aggregation it previews is realized in ch.26 (`examples/08-graphql-gateway`).*

## 22-acid-to-acd

*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Every artifact it cites is real code
and real, dated evidence already in this repository:
`examples/00-monolith/SMELLS.md` (SMELL #3's row and cure mapping),
`order/OrderService.java#placeOrder` and its `@Transactional` javadoc,
`common/exception/PaymentDeclinedException.java` and
`InsufficientStockException.java`, `order/OrderServiceTest.java`'s
payment-declined unit test and its own comment naming the unit tier's limit,
and `tooling/newman/mea.postman_collection.json`'s "Scenario 3 —
Payment-Declined" folder, whose three-step capture/decline/recheck sequence
is paraphrased (condensed, not byte-exact) above and is also narrated in
Chapter 10's testing chapter. What a reader's own run should confirm
independently: executing that Newman folder against a freshly seeded
monolith to watch the stock count hold steady across the decline,
rather than taking the paraphrased assertions on the page as sufficient — the whole point of a
behavior-equivalence suite is that it is runnable, not merely readable.*

## 23-saga-and-extraction-4-payment

*Verification status: <span class="status status--verified">verified</span>. Every artifact cited — `examples/05-payment-service/`, the monolith's `OrderSagaListener`, the proxy's payment branch and `CUTOVER.md` (cutover, reversibility, and the consumer-killed negative check: RED then GREEN), the Newman payment folders, and CI's `payment-equivalence-gate` — is real code and dated evidence, gated green; the service's own tests (`PaymentServiceTest`, `OrderPlacedConsumerTest`, 18 in total) pass.*

## 24-extraction-5-shipping-service

*Verification status: <span class="status status--verified">verified</span>. Every artifact cited — `examples/06-shipping-service/` (the Camel Saga EIP route and steps), the monolith's `OrderSagaListener` reactions, the proxy's shipping branch, the Newman Scenario 4 and Shipping Context Contract folders, and CI's `shipping-equivalence-gate` — is real code and dated evidence, gated green; the saga-correctness suite (16 tests, including the forced `SHIP-FAIL` compensate path and an `AdviceWith` proof that compensation fires exactly once) passes.*

## 25-failure-modes-and-resilience

*Verification status: not applicable — this is a conceptual chapter with no
new runnable example under `examples/`, in the same standing as Chapter 22.
Every artifact it cites is real code and real, dated evidence already in
this repository: `examples/00-monolith/` (`RemoteInventoryClient#reserve`/
`#release` and their deadline/javadoc, `OrderService#placeOrder`/
`#compensateRemoteReservations`, `common/outbox/OutboxEvent`/`OutboxRelay`),
`examples/01-strangler-proxy/` (`StranglerProxyRoute.java`'s
`throwExceptionOnFailure=false` routing and `CUTOVER.md`'s negative-check
timelines for both Notification and Inventory), `examples/03-notification-service/`
(`NotificationService#recordOrderPlaced`, `V3__idempotent_order_id.sql`,
`OrderPlacedConsumerTest`, and `pom.xml`'s `quarkus-smallrye-health`
dependency), `examples/04-inventory-service/` (`InventoryCdcConsumer`,
`InventoryCdcConsumerTest`, and `pom.xml`'s `quarkus-smallrye-health`
dependency). The captured outputs quoted above are taken verbatim from
`CUTOVER.md` and `MIGRATION.md` rather than re-run live here. What a reader's own run should confirm
independently: that no fault-tolerance or circuit-breaker dependency has
been added to any service's build since this chapter was written (the
deferred-items list above is a snapshot, not a permanent ceiling); and that
the deadline value and poll interval cited above still match the committed
`application.properties`/`OutboxRelay` configuration, since both are tuned
constants this book has already asked you to re-verify once, in Chapters 19
and 20 respectively.*

## 26-communication-and-extraction-6-order

*Verification status: <span class="status status--verified">verified</span>. Every artifact cited — `examples/07-order-service/` (CQRS write side plus the same-transaction `order_view` projection), `examples/08-graphql-gateway/`, the collapsed edge router in `examples/01-strangler-proxy/` with `EdgeRouterRoutingTest`, the struck SMELL #2 and SMELL #3 in `SMELLS.md`, the `reference/monolith-before` branch and the `v0-monolith`/`stage/01`…`stage/06` tags, and CI's `order-gateway-contract-gate` — is real code and dated evidence, gated green; the order and gateway test suites (40 and 4 tests) pass.*

## 27-quarkus-microprofile-chassis

*Verification status: not applicable — conceptual chapter, no new runnable
example. Cited: `examples/01-strangler-proxy/` (`StranglerProxyRoute.java`'s
six `@ConfigProperty` base-url fields and its `native` Maven profile),
`examples/02-review-service/` (`ReviewResource.java`'s `@RolesAllowed`,
`application.properties`'s HTTP Basic/embedded-realm block and
`devservices.enabled=false`, `ApiError.java`'s `@RegisterForReflection`,
`pom.xml`'s absence of `quarkus-smallrye-health`), `examples/00-monolith/`
(`security/SecurityConfig.java`, `config/OpenApiConfig.java`, and, from git
history at commit `45618ca`, the pre-decommission
`inventory/RemoteInventoryClient.java`'s `@Value`-injected fields, already
quoted once in Chapter 25), `examples/06-shipping-service/`
(`OrderServiceClient.java`, `application.properties`'s REST client timeouts),
`examples/07-order-service/` (`order/RemoteInventoryClient.java`'s
`@ConfigProperty`-bound deadline and its three `withDeadlineAfter` call
sites, `application.properties`'s `%dev`/`%prod` profile blocks),
`examples/08-graphql-gateway/` (`GatewayApi.java`'s four `@RestClient`
fields and its own `withDeadlineAfter`, `OrderRestClient.java`/
`PaymentRestClient.java`/`ShipmentRestClient.java`/`ReviewRestClient.java`,
`application.properties`'s environment-variable-defaulted client URLs),
`tooling/newman/mea.postman_collection.json` (items 4c/4d), and
`_docs/03-modernization-as-engineering.md` (the Phase A/Phase B native
numbers table) and `_docs/25-failure-modes-and-resilience.md` (the
resilience scorecard this chapter's Fault Tolerance section cross-references
rather than repeats). Re-confirm: a fresh grep of `examples/` for
`@Retry|@Timeout|@CircuitBreaker|@Fallback|@Bulkhead`, `micrometer`,
`smallrye-openapi`, and `smallrye-jwt` across every `pom.xml` and `.java`
file still returns zero hits; the native startup/memory numbers and the
`inventory.grpc.timeout-ms` default still match the committed
`application.properties` and Chapter 3's table, since both are the kind of
measured, re-checkable values this book has already asked you to verify
independently more than once.*

## 28-contracts-and-service-registry

*Verification status: <span class="status status--verified">verified</span>.
`mvn -f examples/09-schema-registry-demo/pom.xml verify` was run and reported
BUILD SUCCESS, including `OrderAvroRoundTripTest` (a real `OrderPlaced` event
emitted and consumed field-for-field-equal over Dev-Services-provisioned
Kafka and Apicurio) and `SchemaCompatibilityTest` (v1 registers; v2, adding
`giftMessage` with a default, accepted with HTTP 200; v3, retyping
`totalCents` from `long` to `string`, rejected with HTTP 409
`RuleViolationException`) — all run against real, ephemeral Testcontainers,
not mocks. `demos/demo-schema-registry.sh` was also run against the live
podman stack (`apicurio` on `:8095`, the shared `kafka` broker) and
reproduced the identical round-trip and v2-accepted/v3-rejected outcomes.
Cited: `examples/09-schema-registry-demo/src/main/avro/order-placed-v1.avsc`,
`src/main/resources/application.properties`, `OrderAvroProducer.java`,
`OrderAvroConsumer.java`, `pom.xml`; `src/test/java/.../OrderAvroRoundTripTest.java`,
`SchemaCompatibilityTest.java`, and the `src/test/resources/avro/`
v2/v3 fixtures; `compose.yaml`'s `apicurio` service block;
`.github/workflows/code-ci.yml`'s `schema-registry-gate` job
(`mvn -f examples/09-schema-registry-demo/pom.xml verify`, no services
block — the module provisions its own Kafka and Apicurio via Dev Services);
and, for the drift this chapter
opened with, `examples/07-order-service/.../OrderPlacedEvent.java` (seven
fields), `examples/05-payment-service/.../OrderPlacedEvent.java` (seven
fields, `paymentMethod` included), and
`examples/03-notification-service/.../OrderPlacedEvent.java` (six fields,
`paymentMethod` absent) — all three read directly, not reconstructed from
memory. Re-confirm by reproducing: `demos/demo-schema-registry.sh` against a
live `docker compose up` stack, and `mvn -f examples/09-schema-registry-demo/pom.xml
verify` independently of the compose stack entirely (Dev Services needs only
a container runtime). The one property worth re-checking on a fresh run:
Apicurio's H2 storage is ephemeral by design, so any artifact registered by
hand against the live stack's registry (as opposed to by the test suite or
the demo script, each of which registers its own fresh artifact) will not
survive a restart of the `apicurio` container.*

## 29-deployment-patterns

*Verification status: <span class="status status--verified">verified</span>
headlessly, <span class="status status--deferred">deferred</span> for live
behavior. `kubectl kustomize deploy/k8s/base` and `kubectl kustomize
deploy/k8s/overlays/minikube` were both run and rendered clean, with no
error, against this repository's actual `deploy/k8s/` tree. `kubectl
kustomize deploy/k8s/overlays/minikube | kubectl apply --dry-run=client -f -`
was run and reported 24 client-side-valid objects (1 Namespace, 2 ConfigMaps,
1 Secret, 10 Services, 8 Deployments, 2 StatefulSets), matching the manifest
inventory this chapter describes. No live minikube cluster was running
during this verification — `kubectl apply` against a live API server, image
builds loaded into a cluster, pod scheduling, probe behavior, rolling-update
behavior, and
an end-to-end request through `strangler-proxy`'s NodePort are explicitly
deferred to Chapter 30. Cited: `deploy/k8s/base/namespace.yaml`,
`app-config.yaml`, `app-secret.yaml`, `strangler-proxy.yaml`,
`review-service.yaml`, `notification-service.yaml`, `inventory-service.yaml`,
`payment-service.yaml`, `shipping-service.yaml`, `order-service.yaml`,
`graphql-gateway.yaml`, `infra/postgres-statefulset.yaml`,
`infra/kafka-statefulset.yaml`, `kustomization.yaml`;
`deploy/k8s/overlays/minikube/kustomization.yaml`; `deploy/k8s/README.md`;
`examples/04-inventory-service/src/main/docker/Dockerfile.jvm`/`.native` and
`examples/05-payment-service/src/main/docker/Dockerfile.jvm`/`.native` (both
pairs diffed byte-identical against `examples/03-notification-service`'s);
`examples/07-order-service/src/main/resources/application.properties`
(`%prod.quarkus.datasource.jdbc.url`); `examples/01-strangler-proxy/src/main/resources/application.properties`
(the six hardcoded `strangler.*.base-url` properties); `compose.yaml`'s own
header comment (the "always ran as plain Java/Quarkus processes outside this
compose stack" statement); and `_docs/27-quarkus-microprofile-chassis.md`
(the six-of-eight `quarkus-smallrye-health` finding and the JVM/native
startup-and-memory table this chapter reuses rather than re-measures).
Re-confirm: re-run both `kubectl kustomize` invocations and the dry-run
pipeline against a fresh clone — the 24-object count is the cheapest signal
that nothing in this tree has drifted; `diff` the two filled-in Dockerfile
pairs against `examples/03-notification-service/src/main/docker/`'s
originals to confirm they're still byte-identical; and grep every
`application.properties` under `examples/01` through `examples/08` for
`localhost` to confirm which properties this chapter's ConfigMap overrides
by ordinal versus which already read a native `${VAR:...}` placeholder,
since that split is asserted here, not re-derived from a fresh search.*

## 30-mesh-observability-tracing

*Verification status: <span class="status status--verified">verified
live</span> against a real minikube cluster for the mesh, mTLS, and the
captured trace/metrics; <span class="status status--deferred">deferred</span>
for Loki log aggregation and Kafka saga-hop tracing. A fresh minikube
cluster (`mea` namespace, podman driver, containerd runtime, Kubernetes
v1.33.3) was brought up, Istio 1.29.2 was installed via `istioctl install -f
deploy/k8s/istio/istio-install.yaml`, and `kubectl apply -k
deploy/k8s/overlays/minikube` was run after uncommenting the chapter's seam
and fixing the six bugs in `deploy/k8s/base/infra/postgres-statefulset.yaml`
and `kafka-statefulset.yaml` this chapter documents. All eight application
pods reached `2/2` Ready (the second container being the native-sidecar
`istio-proxy`); `postgres` and `kafka` reached `1/1` Ready, unmeshed, as
designed. `istioctl x describe pod` against `order-service` reported
`Workload mTLS mode: STRICT` (captured in full at
`deploy/k8s/observability/evidence/ch30-istioctl-mtls.txt`) after
`deploy/k8s/istio/peer-authentication.yaml` was flipped from `PERMISSIVE` to
`STRICT`. Thirty synchronous checkouts (`202`) and fifteen GraphQL
aggregation queries (`200`) were driven against the live cluster. One
resulting trace, ID `af8b2d9f78b2e8a796b1fd2b472aede3`, was pulled from
Tempo and is captured in full at
`deploy/k8s/observability/evidence/ch30-hero-trace.json` — one root span
(`graphql-gateway` `POST /graphql`) fanning out to five branches (order,
inventory via gRPC, review, payment, shipping), with application-level
spans present only in the three `quarkus-opentelemetry`-instrumented
branches (`graphql-gateway`, `order-service`, `inventory-service`) and
Envoy-only spans in the other three, reproduced faithfully in this
chapter's span tree. `istio_requests_total` was queried from Mimir and is
captured in full at `deploy/k8s/observability/evidence/ch30-metrics.txt`,
including every `connection_security_policy="mutual_tls"` edge cited above
and the `review-service` `500`s, which are a real, pre-existing application
defect this chapter reports rather than fixes. Explicitly **not** verified
live: Loki received no application log lines in this run, because no
node-level log-shipping DaemonSet (Alloy or equivalent) is deployed
anywhere in this tree — the in-pod Collector cannot read another pod's
stdout, so application logs for this chapter are documented via `kubectl
logs`, not a Loki query. Kafka saga hops (`payment.captured`,
`shipment.failed`, and the rest, extracted in Chapters 23 and 24) are not
traced by Istio's L7 instrumentation at all — no span in the captured
evidence originates from a Kafka consumer. Cited: `deploy/k8s/istio/istio-install.yaml`,
`peer-authentication.yaml`, `telemetry.yaml`, `kustomization.yaml`;
`deploy/k8s/observability/lgtm-deployment.yaml`, `lgtm-service.yaml`,
`otelcol-configmap.yaml`, `grafana-datasources-configmap.yaml`, `rbac.yaml`,
`kustomization.yaml`; `deploy/k8s/overlays/minikube/kustomization.yaml`
(the uncommented seam and the eight sidecar-inject label patches);
`deploy/k8s/base/infra/postgres-statefulset.yaml` and
`kafka-statefulset.yaml` (all six `ch.30 live-apply fix` comments);
`examples/04-inventory-service/`, `examples/07-order-service/`,
`examples/08-graphql-gateway/` (`pom.xml`'s `quarkus-opentelemetry`
dependency and `application.properties`'s OTLP exporter/service-name/sampler
block in each); and `deploy/k8s/observability/evidence/ch30-hero-trace.json`,
`ch30-metrics.txt`, `ch30-istioctl-mtls.txt`. Re-confirm: re-run `istioctl
install`, `kubectl apply -k deploy/k8s/overlays/minikube`, and `istioctl x
describe pod` against a fresh cluster to confirm the mTLS mode and pod
readiness counts still match; re-query Tempo and Mimir after driving fresh
traffic to confirm the span shape and the `mutual_tls` edges haven't
drifted, since both are live-cluster facts this chapter captured once
rather than values baked into any committed manifest.*

## 31-cicd-gitops-supply-chain

*Verification status: <span class="status status--verified">verified
live</span> for the supply-chain gate, locally and in CI; the CI/GitOps
narrative is <span class="status status--verified">sourced from the committed
workflows</span>. On 2026-10-06, with `syft 1.44.0` and `grype 0.112.0`
(vulnerability DB built 2026-10-06), `examples/10-supply-chain/demo.sh` was run
against this repository and produced the gate summary quoted above, exiting `1`:
`examples/00-monolith/target/monolith.jar` tripped `fail-on: High` with 4
Critical / 6 High / 8 Medium across `spring-webmvc@6.2.19`,
`tomcat-embed-core@10.1.55`, `jackson-core`/`jackson-databind@2.21.4`, and
`postgresql@42.7.11` (captured in `examples/10-supply-chain/evidence/monolith.grype.json`);
`examples/07-order-service/target/quarkus-app` passed with 0 / 0 / 0 over 522
cataloged components (`evidence/order-service.grype.json`). Both targets were
already built (`mvn -o package`); the full regenerated SBOMs are git-ignored,
the grype findings snapshots and `evidence/summary.txt` are committed as the
point-in-time evidence. The CI incarnation, `.github/workflows/supply-chain.yml`,
was then **run on a live GitHub Actions runner** (PR #14, 2026-10-06): it built
order-service on JDK 25, generated the SBOM via Anchore's `sbom-action`, and
scanned it via `scan-action` with `fail-build: true` + `severity-cutoff: high`;
the `sbom-scan (order-service)` check passed green — after one fix, since syft
does not shell-expand a glob in the action's `path`, so the job matrix carries the
explicit service directory. The description of `code-ci.yml`'s
seven jobs and `pages.yml` is sourced directly from the committed workflow files,
not re-run here. The CVE counts are a moving target against grype's daily DB and
will drift — re-run `demo.sh` to refresh. Cited: `.github/workflows/code-ci.yml`
(the seven job names + path filters), `pages.yml`, `supply-chain.yml`;
`examples/10-supply-chain/{demo.sh,policy.yaml,README.md}` and its `evidence/`;
`examples/01-strangler-proxy/src/main/resources/application.properties` (the
retired `strangler.*.enabled` flags).*

## 32-pattern-language-revisited

*Verification status: <span class="status status--verified">synthesis</span> —
this chapter introduces no new code or runnable example; it re-walks and
cross-references claims each verified in its own chapter. Each scorecard verdict
(Figure 32.1) and spectrum position (Figure 32.2) is traceable to the chapter
and example named in it: strangler/ACL (ch.14-16, `examples/01-strangler-proxy`);
bounded contexts (ch.11-13); database-per-service/outbox/CDC (ch.17-20,
`examples/03`,`04`; `scripts/retire-debezium.sh`); CQRS (ch.21,26,
`examples/07-order-service`); choreographed/orchestrated sagas (ch.23-24,
`examples/05`,`06`); GraphQL composition (ch.26, `examples/08-graphql-gateway`);
schema registry (ch.28, `examples/09-schema-registry-demo`); chassis (ch.27);
reliability (ch.25); mesh + tracing (ch.30, verified live on minikube);
supply-chain + CI gates (ch.31, verified live). Deferred items (rewrite, full
event sourcing, LRA/Narayana, GitOps operator + canary, Loki log shipping,
Kafka-aware tracing, image signing) are each named as deferred in the chapter
cited above, consistent with the no-speculative-infrastructure discipline
and the security-by-design scope. No claim in this chapter
should be read as newly verified here; re-confirm any specific verdict against
its source chapter's own verification footer.*

## 33-appendix-openshift

*Verification status: **verified live**, 2026-10-06, on OpenShift Local (CRC
2.64.0, OpenShift 4.22.14, single node, 20 GB / 8 vCPU). All ten workloads reached
`1/1 Running` in namespace `mea` (eight application Deployments + the Postgres and
Kafka StatefulSets). The SCC behaviour was confirmed by reading each pod's
`openshift.io/scc` annotation and effective UID: application pods under
`restricted-v2` with assigned UID `1000660000`, `postgres-0`/`kafka-0` under
`nonroot-v2` with UIDs `70`/`1000`. The GraphQL gateway's `/q/health/ready` returned
`{"status":"UP"}` and the strangler-proxy Route returned seeded inventory JSON, both
over the external `apps-crc.testing` hostnames. The full capture is committed at
`openshift/evidence/verification.txt`. Re-verified 2026-10-09 on the current pins
(UBI 10 images built in the cluster, Postgres 18.6 and Kafka 4.3.1 mirrored with
skopeo): same SCC/UID picture, gateway `UP`, inventory 200, and `/api/reviews`
200 now that `deploy.sh` bootstraps the monolith's `public` schema. **Not verified:** the GitOps
`Application` and any Tekton pipeline were authored but not applied (no operators
installed). The image mirror step is CRC-network-specific; on a cluster with Docker
Hub egress, the upstream `postgres`/`kafka` refs apply unchanged. Cited:
`openshift/helm/mea/**` (the chart), `openshift/README.md`, `openshift/evidence/verification.txt`,
`openshift/gitops/application.yaml`; `deploy/k8s/base/**` (the Kubernetes manifests
this chart ports); `examples/*/src/main/docker/Dockerfile.jvm`. Re-confirm by
re-running `helm upgrade --install` against a fresh `crc start`, then re-driving the
two Route `curl`s and re-reading the pod SCC annotations — the assigned UID will
differ per cluster, but the SCC names and the 200s should not.*
