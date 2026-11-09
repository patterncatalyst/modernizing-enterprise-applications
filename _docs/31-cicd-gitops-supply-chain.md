---
title: "CI/CD, GitOps, Progressive Delivery & Supply Chain"
order: 31
part: "Delivering & Reflection"
description: "The GitHub Actions gates the repo ships, the flag-gated cutovers as progressive delivery, and a supply-chain gate (SBOM, CVE scan, policy-as-code) that fails the monolith and passes order-service."
duration: 35 minutes
---

## Learning objectives

By the end of this chapter you can:

- Name the seven behavior gates in `code-ci.yml` and say why each is path-filtered to one seam's `examples/` directory.
- Explain why six flag-gated strangler cutovers are progressive delivery, and how an all-or-nothing reversible flag differs from a weighted canary.
- Build a CycloneDX software bill of materials with `syft`, and say why the scanner runs against the SBOM and not the artifact a second time.
- Configure a policy-as-code gate (`policy.yaml`'s `fail-on` threshold) that turns a Critical or High finding into a non-zero exit.
- Explain why a CVE scan is a point-in-time claim that should run on every push.
- Name the GitOps parts the project does not run (registry push, Argo CD or Flux, Istio weighted canary) and why.

## Why this matters

Every manifest under `deploy/k8s/` was applied to minikube by hand with `kubectl apply -k`. Building images, pushing them and rolling a change through the `mea` namespace without a person at the keyboard is the delivery half of CI/CD, and the repository ships only part of it. The behavior gates run on every push. Progressive delivery was done with flags. The new piece is the supply chain: a real SBOM, scanned against a real vulnerability database, failing a build on policy. The result puts a number on the migration itself.

The code is in `examples/10-supply-chain/`; its `README.md` covers the run script.

{% include excalidraw.html file="delivery-pipeline-ci-gates" alt="A git push fans into three GitHub Actions workflow files: code-ci.yml (seven behavior gates: the monolith equivalence gate, four per-seam equivalence gates, the order/gateway contract gate and the schema-registry gate), supply-chain.yml (mvn package, then syft SBOM, then grype scan, then policy.yaml fail-on High) and pages.yml (build and deploy the Jekyll site). To the right, three dashed boxes mark the deferred rest of a GitOps chain: pushing images to a registry, a GitOps operator (Argo CD or Flux) reconciling deploy/k8s into the cluster, and Istio weighted canary." caption="Figure 31.1: The delivery pipeline the repo ships: behavior gates and a provenance gate that run, drawn against the registry-push, GitOps and canary chain it names but does not run" %}

## The behavior gates

`.github/workflows/` holds `pages.yml`, which builds the Jekyll site and deploys it to GitHub Pages, and `code-ci.yml`, which holds seven jobs. Each boots a real slice of the system against real infrastructure and drives the Newman behavior-equivalence collection against it:

| Job | Proves |
|---|---|
| `equivalence-gate` | the monolith baseline |
| `notification-equivalence-gate`, `inventory-equivalence-gate`, `payment-equivalence-gate`, `shipping-equivalence-gate` | one per extracted seam that cut over asynchronously |
| `order-gateway-contract-gate` | the CQRS and GraphQL seam, a contract check since the monolith was decommissioned |
| `schema-registry-gate` | the isolated Avro and Apicurio demonstrator |

Each job is path-filtered through `on.push.paths` and `on.pull_request.paths`, so a change under `examples/03-notification-service/**` runs the notification gate without rebuilding the schema-registry demonstrator. Every job has the same shape: stand up Postgres (a `postgres:18.6-alpine` service container) and, for event-driven seams, a Kafka broker; boot the services and wait for a specific readiness signal (the Kafka consumers wait for the "partitions assigned" log line, because a sleep long enough for a fast runner is too short for a slow one); run Newman and fail the job on a non-zero assertion count.

No job builds a container image, pushes it or deploys anything. The services carry Quarkus-generated `Dockerfile.jvm` and `Dockerfile.native` under `src/main/docker/`, so images can be built, but delivery is still the manual `kubectl apply -k`.

## Progressive delivery with flags

Weighted canary, with Istio sending 5% of traffic to a new version, is not available here: Istio is installed for identity and observability only, with no `VirtualService`, no `DestinationRule` and no weighting. The migration used a different mechanism six times.

Every extraction cut over behind a `strangler.<context>.enabled` flag in the Camel proxy. With the flag off, that context's paths went to the monolith; with it on, to the extracted Quarkus service. The equivalence collection stayed green in both positions, and the flip was reversible until the monolith module was decommissioned. That is the promise of progressive delivery: ship dark, cut over under a switch you can throw back, gate the cutover on an automated check.

The limit is granularity. The flag is all-or-nothing per context, so a bad cutover hits every request for that context the moment it flips. Reversibility and a green suite carry the risk that a traffic split would otherwise spread.

The flags are retired. `examples/01-strangler-proxy`'s `application.properties` records the end state: all six `strangler.*.enabled` flags are gone, the proxy routes unconditionally to the six services, and the cutover history of each flag is in the file's comment block. A flag that outlives its cutover is a dead branch that misdescribes the system.

## The supply chain gate

Security by design belongs in the ADLC Verify step (SBOM, CVE and dependency scan, secure defaults, secrets hygiene), in CI, not in a late audit. The question is whether you know what is inside the thing you deploy and whether any of it is known to be vulnerable. `examples/10-supply-chain` answers it with three tools and a policy file, run over the same two artifacts so the result is a before and after.

{% include excalidraw.html file="supply-chain-gate-before-after" alt="One three-step gate with two inputs. The monolith fat jar (before) and the order-service Quarkus runtime closure (after) both flow into syft, which emits a CycloneDX SBOM; into grype, which scans that SBOM against its vulnerability database (built 2026-10-06, a moving target); into policy.yaml with fail-on High. The monolith branch ends in FAIL (4 Critical, 6 High, 8 Medium, 78 components, non-zero exit), the order-service branch in PASS (0/0/0, 522 components, exit 0)." caption="Figure 31.2: The same gate over the before and the after: the monolith fails it, the modernized service passes it" %}

`policy.yaml` is the single source of truth. It holds one `fail-on:` threshold (`High`, so any High or Critical finding fails) and a list of `targets`, each with a `name`, a `kind` (`before` or `after`) and a `path`. Because the rule is committed, reviewed and diffable, it goes through the same review as code, and the same file drives the local run and CI, so the two cannot diverge. The targets are:

- `before`: `examples/00-monolith/target/monolith.jar`, the Spring Boot fat jar the migration started from.
- `after`: `examples/07-order-service/target/quarkus-app`, the extracted service's runtime closure. It's the directory of jars that ships, not the source tree, which also holds build-only plugin dependencies.

`demo.sh` runs three steps per target:

1. **SBOM.** `syft "$REPO_ROOT/$path" -o cyclonedx-json=$sbom` auto-detects the source type, so one line handles the monolith file (it reads the archive and the jars nested in it) and the order-service directory. CycloneDX is an OWASP standard that an auditor, a customer's security team or a scanner can read whatever tool produced it.
2. **Scan.** `grype "sbom:$sbom" -o json` scans the SBOM, not the artifact again. The bill of materials is generated once, and the scanner, an archived audit record and a human all reason about the same component list.
3. **Policy.** `jq` tallies Critical, High and Medium, and `fail-on: High` marks the target `FAIL`. The loop doesn't abort on the first failure: it records it, scans the rest and exits `1` at the end. A gate that hides the second half of its results once the first fails is a worse gate. The non-zero exit is the whole CI integration.

The script reads `fail-on` with a one-line `grep | awk` because the file's shape is fixed, and prints the grype database build date first, because every verdict is true only as of that database.

### The CI workflow

`.github/workflows/supply-chain.yml` is the same three steps as a separate, additive job. It sets up JDK 25, runs `mvn package`, generates the SBOM from the built `quarkus-app` with Anchore's `sbom-action`, and scans it with `scan-action` set to `fail-build: true` and `severity-cutoff: high`, the mirror of `fail-on: High`. A job matrix gates `order-service` as the representative service and scales by adding entries, and each run uploads the SBOM as a build artifact. The monolith isn't gated in CI: it's frozen and decommissioned, and `demo.sh` scans it only to show the check firing.

## GitOps, named and scoped

A full GitOps chain adds three things the repo doesn't have, drawn as the dashed boxes in Figure 31.1:

| Missing part | What it would do |
|---|---|
| Registry push | a job that pushes built images to `ghcr.io` or `quay.io` |
| GitOps operator | Argo CD or Flux in the cluster, watching `deploy/k8s`, so a merge changes the running system and not a person running `kubectl apply` |
| Istio weighting | `VirtualService` and `DestinationRule` traffic splits for infrastructure-level progressive rollout |

A GitOps operator is a component to run, secure, upgrade and monitor, and standing one up to reconcile a single-node minikube cluster builds machinery for a load that doesn't exist. The manifests are GitOps-ready (declarative, in git, and they apply cleanly), and the provenance gate is written and verified. The operator is a named next step.

> **Decision**
>
> Use a reversible flag when a cutover is all-or-nothing per context and an equivalence check can gate both positions, and retire it when the cutover completes. Add weighted canary only when you can afford the operator and need partial traffic. Gate the supply chain on every push, because a scan is true only as of its database.

> **🤖 Explore with your agent**
>
> "In `examples/10-supply-chain`, add `examples/04-inventory-service/target/quarkus-app` as a third `after` target in `policy.yaml`, run `./demo.sh`, and tell me whether it passes `fail-on: High`. Then add the same service to the matrix in `.github/workflows/supply-chain.yml`, mirroring the threshold."
>
> Check: `./demo.sh` prints a third summary line with the new target's own PASS or FAIL verdict; the exit stays `1` while the monolith target fails.

## Build, run, observe

```bash
cd examples/10-supply-chain && ./demo.sh
```

Recorded on 2026-10-06 with both artifacts built:

```
monolith       [before] components=78   Critical=4  High=6  Medium=8  FAIL
order-service  [after ] components=522  Critical=0  High=0  Medium=0  PASS
GATE: FAIL -- at least one artifact breaches policy (fail-on: High).
```

The monolith fat jar carries four Critical and six High findings; `evidence/monolith.grype.json` names them across `spring-webmvc`, `tomcat-embed-core`, `jackson-core` and `jackson-databind`, and the `postgresql` JDBC driver. The extracted order-service, on the Quarkus 3.40.1 platform BOM, carries none against the same database on the same day. The modernization wasn't only a supply-chain improvement, but it measurably was one.

Two facts qualify the numbers:

- The monolith's versions aren't ancient (`jackson` 2.21.x and `spring-webmvc` 6.2.x are recent). The findings are largely freshly disclosed CVEs, because the grype database was built the day of the scan. A clean artifact can fail tomorrow, which is why the supply-chain job runs on every push and not once at release.
- The component counts (78 against 522) differ because a Spring Boot fat jar and a Quarkus runtime-closure directory present different catalogs to syft. The comparison is whether each artifact breaches policy, not which has more components.

Experiment: change `fail-on` in `policy.yaml` to `Critical` and rerun. The monolith still fails on its four Critical findings, and order-service still passes.

## For deeper coverage

- [SLSA: Supply-chain Levels for Software Artifacts](https://slsa.dev/): the provenance framework the SBOM, scan and policy gate here is a partial instance of.
- [CycloneDX](https://cyclonedx.org/): the SBOM standard `syft` emits and `grype` scans in `demo.sh` and the CI workflow.
- [Argo CD documentation](https://argo-cd.readthedocs.io/): the pull-based GitOps operator named here as the deferred next step.
- Martin Fowler, [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html): the flag-gated, reversible cutover that served as progressive delivery for all six cutovers.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): independent deployability and per-service delivery pipelines, the broader pattern the seven path-filtered CI gates are one project's answer to; see the [Reading List](/bibliography/).
