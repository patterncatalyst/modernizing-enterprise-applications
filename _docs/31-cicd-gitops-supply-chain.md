---
title: "CI/CD, GitOps, Progressive Delivery & Supply Chain"
order: 31
part: "Delivering & Reflection"
description: "The GitHub Actions workflows this repo actually ships (the equivalence/contract gates and the site build), the flag-gated progressive delivery the strangler already used, and a new, verified-live supply-chain gate: SBOM + CVE scan + policy-as-code."
duration: 35 minutes
---

Chapter 30 ended on a promise with a person still in the loop: every manifest
under `deploy/k8s/` had been applied to minikube *by hand*, and the closing
teaser pointed at "the GitHub Actions workflows that build these eight images,
push them somewhere real, and roll a change through this same `mea` namespace
without a person running `kubectl apply -k` themselves." This chapter is where
that promise meets what the repository actually ships — and it keeps the book's
habit of saying plainly which parts are real and which are named-but-deferred.
Two of those four verbs — *build* and *roll* a full image-to-cluster chain —
are deferred here, named with the same directness Chapter 30 used for its Loki
gap. The one genuinely new, genuinely verified piece this chapter adds is the
third delivery concern the book has carried as a standing constraint since
Part 2 but never yet shown running: the **supply chain**. We build a real
SBOM, scan it against a real vulnerability database, and fail a build on
policy — and the result turns out to be the whole book's thesis made
measurable.

The code is in `examples/10-supply-chain/`. The run script there builds (if
needed), generates the SBOM, scans it, and applies the policy; its `README.md`
covers what it does and how to drive it.

{% include excalidraw.html file="delivery-pipeline-ci-gates" alt="A git push fans into three real GitHub Actions workflow files: code-ci.yml (seven behavior gates — the equivalence gate on the monolith baseline, the four per-seam equivalence gates, the order/gateway contract gate, and the schema-registry gate), supply-chain.yml (the ch.31 provenance gate: mvn package then syft SBOM then grype scan then policy.yaml fail-on High), and pages.yml (build and deploy the Jekyll site). To the right, three dashed ghost boxes mark the honestly-deferred rest of a GitOps delivery chain: pushing images to a registry, a GitOps operator (Argo CD or Flux) reconciling deploy/k8s into the cluster, and Istio weighted canary." caption="Figure 31.1 — The delivery pipeline this repo ships: behavior gates and a provenance gate that run, drawn against the registry-push/GitOps/canary chain it names but does not yet run" %}

## The pipeline that already exists

Before adding anything, it's worth being precise about what this repository's
CI already does, because the whole book has been leaning on it since the
walking skeleton. Two workflow files live in `.github/workflows/`. `pages.yml`
is the undramatic one: it checks out the repo, builds the Jekyll site, and
deploys it to GitHub Pages — it's why the tutorial you're reading has a URL.
`code-ci.yml` is the one that matters for the migration, and it is large on
purpose. It holds **seven jobs**, and every one of them is a behavior gate: it
boots a real slice of the system against real infrastructure and drives the
behavior-equivalence suite — the Newman/Postman collection introduced in
Chapter 14 — against it.

The seven jobs, by name, are `equivalence-gate` (the monolith baseline),
`notification-equivalence-gate`, `inventory-equivalence-gate`,
`payment-equivalence-gate`, and `shipping-equivalence-gate` (one per extracted
seam that cut over asynchronously), `order-gateway-contract-gate` (the
CQRS-plus-GraphQL seam, which Chapter 26 converted from a live-equivalence
check to a contract check once the monolith was decommissioned), and
`schema-registry-gate` (which builds and tests the isolated Avro + Apicurio
demonstrator from Chapter 28). Each job is **path-filtered**: the workflow's
`on.push.paths` and `on.pull_request.paths` lists mean a change under
`examples/03-notification-service/**` runs the notification gate without paying
to rebuild the schema-registry demonstrator, and vice versa. The shape inside
every job is the same three beats — stand up Postgres (a `postgres:16-alpine`
service container) and, where the seam is event-driven, a Kafka broker; boot
the service(s) under test and wait for a *specific* readiness signal (for the
Kafka consumers, the job waits on the literal "partitions assigned" log line,
not a fixed sleep, because a sleep that's long enough on a fast runner is too
short on a slow one); then run Newman and let a non-zero assertion count fail
the job.

That is continuous integration doing its actual job: proving, on every push,
that the system still *behaves* the way the frozen baseline says it must. What
`code-ci.yml` deliberately does not do is build a container image, push it
anywhere, or deploy anything. The services each carry Quarkus-generated
`Dockerfile.jvm` and `Dockerfile.native` files under `src/main/docker/`, so
images *can* be built — but no job in this repo builds or ships them, and the
delivery half of "CI/CD" is, today, the manual `kubectl apply -k` Chapter 30
ran. Saying so plainly is the point: the behavior gates are real and have been
green through six extractions; the image-build-and-deploy pipeline is not yet
written, and this chapter will not pretend otherwise.

## Progressive delivery, the way this migration already did it

"Progressive delivery" usually conjures Istio pouring 5% of traffic onto a new
version and watching error rates before widening the pour. This project
deliberately does not have that — Chapter 30 installed Istio for identity and
observability only and named, in as many words, that it configured no
`VirtualService`, no `DestinationRule`, and no canary weighting. It would be
easy to read that as "this book skipped progressive delivery." It didn't; it
did a different, arguably stronger version of it, and it did it six times.

Every single extraction in Part 5 through Part 7 cut over behind a **feature
flag** in the strangler proxy. The pattern, established in Chapter 15 and
repeated for notification, inventory, payment, shipping, and order, was always
the same: the Camel proxy carried a `strangler.<context>.enabled` flag; with
the flag off, requests for that context's paths went to the monolith; with the
flag on, they went to the extracted Quarkus service; and the behavior-
equivalence suite had to stay green *across the flip*, in both positions, with
the flip reversible at any point until the monolith module was decommissioned.
That is progressive delivery's actual promise — ship the new thing dark,
cut over under a switch you can throw back, and gate the cutover on an
automated behavioral check — expressed as a routing flag and an equivalence
suite rather than as a traffic-weight percentage. It has one honest limitation
next to weighted canary: it's all-or-nothing per context, not a 5%/95% split,
so a bad cutover affects every request for that context the moment the flag
flips, which is exactly why reversibility and the green suite mattered so much.

Those flags are gone now, and that too is in the code. `order-service`'s
cutover was the last one, and `examples/01-strangler-proxy`'s
`application.properties` records the end state directly: all six
`strangler.*.enabled` flags are **retired**, the proxy routes unconditionally
to the six services, "there is no flag left to flip," and the per-context
cutover history — each flag's reversibility window — lives in the file's own
comment block. The progressive-delivery mechanism did its job and was removed
once the migration it was protecting was complete. A flag that outlives its
cutover is just a dead branch and a lie about the system's shape; this one was
cleaned up.

## The supply chain: the constraint that was always there

Decision DRQ-020, recorded in `_plans/decisions.md` back when the plan was
first written, states that *security-by-design is part of the ADLC Verify gate*
— SBOM, CVE/dependency scan, secure-by-default, secrets hygiene — "+ in CI,"
and "not a late audit." Every chapter since has carried that as a standing
constraint without a chapter of its own. This is that chapter. The supply-chain
question is simple to state and uncomfortable to answer: **do you actually know
what is inside the thing you deploy, and whether any of it is known to be
vulnerable?** The answer has three moving parts, and `examples/10-supply-chain`
runs all three over the same two artifacts, so the result is a direct
before/after comparison rather than an abstract capability.

{% include excalidraw.html file="supply-chain-gate-before-after" alt="One three-step gate with two inputs. The monolith fat jar (before) and the order-service Quarkus runtime closure (after) both flow into step 1, syft, which emits a CycloneDX SBOM; into step 2, grype, which scans that SBOM against its vulnerability database (built 2026-10-06, labelled a moving target); into step 3, policy.yaml with fail-on High. The monolith branch ends in a FAIL box (4 Critical, 6 High, 8 Medium, 78 components, non-zero exit), the order-service branch in a PASS box (0/0/0, 522 components, exit 0)." caption="Figure 31.2 — The same gate over the 'before' and the 'after': the monolith fails it, the modernized service passes it — the modernization made measurable" %}

### How the code works

The whole gate is `examples/10-supply-chain/demo.sh`, and it is deliberately
small enough to read in one sitting, because the point is that a supply-chain
gate is not exotic — it is three command-line tools and a policy file. Two
inputs to understand before the steps:

- **`policy.yaml`** is the single source of truth, and it is the "policy-as-code"
  part taken literally. It holds one `fail-on:` threshold (`High`, meaning any
  High or Critical finding fails the gate) and a list of `targets`, each a real
  deployable artifact this repo builds with a `name`, a `kind` (`before` or
  `after`), and a `path`. Keeping the threshold in a committed, reviewed,
  diffable file — rather than as a flag buried in a shell invocation or a CI
  YAML step — is what makes it *code*: the security rule goes through the same
  review as everything else, and the identical file drives both the local demo
  and the CI workflow, so "it passed on my laptop" and "it passed in CI" cannot
  diverge.
- The **two targets** are chosen to make the comparison honest. The `before` is
  `examples/00-monolith/target/monolith.jar` — the Spring Boot fat jar, the
  pre-migration artifact. The `after` is
  `examples/07-order-service/target/quarkus-app` — the extracted Quarkus
  service's *runtime closure*, the directory of jars that actually ships, not
  the source tree (which also contains build-only plugin dependencies that
  never reach production).

The script reads `fail-on` out of `policy.yaml` with a one-line `grep | awk`
rather than pulling in a YAML parser, because the file's shape is fixed and
small; over-engineering the parser would be its own smell. It then prints the
grype database's build date up front, because every verdict below is only true
*as of that database* — a fact the script makes visible rather than hiding.

The core is the `scan_target` function, run once per target, and it is exactly
the three steps the chapter promised:

1. **SBOM.** `syft "$REPO_ROOT/$path" -o cyclonedx-json=$sbom` generates a
   CycloneDX JSON bill of materials. `syft` auto-detects the source type, which
   is why the same line works for both targets: handed the monolith *file* it
   reads the archive and the jars nested inside it; handed the order-service
   *directory* it walks the runtime closure in place. CycloneDX is the format
   choice because it's an OWASP standard an SBOM consumer (an auditor, a
   customer's security team, the scanner in the next step) can read without
   caring which tool produced it — the SBOM is a portable contract, not a
   syft-private file.
2. **Scan.** `grype "sbom:$sbom" -o json` scans *the SBOM*, not the artifact
   again. That indirection is deliberate and is the reason the SBOM exists as a
   separate step at all: the bill of materials is generated once and is the
   thing everything downstream agrees on, so the scanner, an archived audit
   record, and a human all reason about the identical component list. grype
   matches each component against its vulnerability DB and tags every hit with
   a severity.
3. **Policy.** The script tallies Critical/High/Medium with `jq` and applies
   `fail-on`: with `fail-on: High`, any Critical *or* High flips the target's
   verdict to `FAIL` and sets a `gate_failed` flag. Crucially, the loop does
   **not** abort on the first failing target — it records the failure and
   continues, so the final summary reports every artifact, and the script exits
   non-zero only at the very end if any target breached. A gate that hides the
   second half of its results the moment the first thing fails is a worse gate.

The driver at the bottom iterates the `targets` from `policy.yaml` — reading
the fixed three-line `name`/`kind`/`path` blocks — calls `scan_target` on each,
prints the summary table, and exits `1` if `gate_failed` is set. That non-zero
exit is the entire integration with CI: a job step that runs `./demo.sh` and
exits non-zero *is* a failed gate, no special CI glue required.

### Build, run, observe

```bash
cd examples/10-supply-chain && ./demo.sh
```

Run against this repository on 2026-10-06, with both artifacts already built,
the output is unambiguous:

```
monolith       [before] components=78   Critical=4  High=6  Medium=8  FAIL
order-service  [after ] components=522  Critical=0  High=0  Medium=0  PASS
GATE: FAIL -- at least one artifact breaches policy (fail-on: High).
```

Read that pair slowly, because it is the point of the whole book arriving as a
number. The **monolith** — the Spring Boot fat jar the migration started from —
carries four Critical and six High findings: the captured
`evidence/monolith.grype.json` names them, across `spring-webmvc`,
`tomcat-embed-core`, `jackson-core`/`jackson-databind`, and the `postgresql`
JDBC driver. The **extracted order-service**, built on the current Quarkus
3.40.1 platform BOM, carries zero High or Critical against the same database on
the same day. The gate fires on the artifact the book set out to replace and
passes on the artifact the book produced. The modernization wasn't *only* a
supply-chain improvement — but it was measurably one, and this is the first
place in the book that claim is a captured fact rather than an assertion.

Two honesty notes the evidence forces, both of which the book would be poorer
for hiding. First: the monolith's dependency versions are not ancient — some,
like `jackson` 2.21.x and `spring-webmvc` 6.2.x, are recent. The findings
against them are largely *freshly disclosed* CVEs, because the grype DB was
built the same day the scan ran. That is the real lesson of CVE scanning, not a
caveat to it: a scan is a point-in-time claim against a database that moves
daily, so an artifact that is clean today can fail tomorrow when a new
vulnerability is published — which is precisely why the gate runs on **every
push**, not once at release. Second: the component counts differ (78 vs. 522)
because the two artifacts are packaged differently — a Spring Boot fat jar
presents a different catalog surface to syft than a Quarkus runtime-closure
directory — so the comparison is "does each artifact breach policy," not "which
has more components." More components is not more risk; a known-Critical
component is.

### The CI incarnation

`.github/workflows/supply-chain.yml` is the same three steps as a GitHub
Actions job, and it is **additive** — a separate file from `code-ci.yml`, in
keeping with every other capability this book added without disturbing what
already worked. Where `code-ci.yml` proves behavior, `supply-chain.yml` proves
provenance. It checks out the repo, sets up JDK 25, builds the service with
`mvn package`, generates the CycloneDX SBOM from the built `quarkus-app`
runtime closure with Anchore's `sbom-action`, and scans it with Anchore's
`scan-action` configured `fail-build: true` and `severity-cutoff: high` — the
workflow mirror of `policy.yaml`'s `fail-on: High`. It gates `order-service` as
the representative service via a job matrix that scales to the rest by adding
entries, and it uploads the SBOM as a build artifact so every run leaves an
auditable bill of materials behind. It does not gate the monolith: the monolith
is frozen and decommissioned in-repo, so there is nothing to protect by gating
it in CI — `demo.sh` scans it only to show the gate firing on the
pre-modernization artifact, which is a teaching job, not a delivery job.

## GitOps, named and scoped

That leaves the two deferred verbs from Chapter 30's teaser, drawn as the
dashed boxes in Figure 31.1, named here rather than quietly dropped. A complete
GitOps delivery chain would add three things this repo does not have: a job
that **pushes** the built images to a registry (`ghcr.io` or `quay.io`); a
pull-based **GitOps operator** — Argo CD or Flux — running *in* the cluster,
watching `deploy/k8s`, and reconciling the cluster to match the committed
manifests so that a merge to `deploy/k8s`, not a human running `kubectl apply`,
is what changes the running system; and, to make that rollout *progressive* at
the infrastructure layer, the Istio `VirtualService`/`DestinationRule` traffic
weighting Chapter 30 explicitly deferred.

None of that is written here, and the reason is the same discipline the book
has held since Chapter 3: no speculative infrastructure. A GitOps operator is a
real operational commitment — a component to run, secure, upgrade, and
monitor — and standing one up to reconcile a single-node minikube cluster that
is itself a teaching artifact would be building machinery for a load that
doesn't exist. The honest state is: the manifests are GitOps-*ready* (they're
declarative, in git, and apply cleanly, as Chapter 30 proved live), the
provenance gate that any serious delivery pipeline needs is written and
verified, and the operator that would close the loop is a named next step whose
cost this project doesn't yet have a reason to pay. That is a more useful thing
to hand a reader than a half-wired Argo install that works in a screenshot and
nowhere else.

## What you learned

- **This repo's CI is seven behavior gates plus a site build** —
  `code-ci.yml`'s `equivalence-gate`, the four per-seam
  `*-equivalence-gate`s, `order-gateway-contract-gate`, and
  `schema-registry-gate`, each path-filtered and each booting real
  infrastructure to drive the behavior-equivalence suite; plus `pages.yml`. It
  proves behavior on every push; it does not build or deploy images, and this
  chapter says so rather than implying a pipeline that isn't there.
- **The strangler's feature flags were progressive delivery** — six cutovers,
  each flag-gated, reversible, and gated on a green equivalence suite across
  the flip; an all-or-nothing-per-context version of canary that traded the
  traffic-weight split for reversibility and an automated behavioral gate. The
  flags were retired once the migration they protected was complete.
- **A supply-chain gate is three tools and a policy file** — syft for the
  CycloneDX SBOM, grype to scan it, and a committed `policy.yaml` `fail-on`
  threshold that turns a finding into a non-zero exit. Scanning the SBOM rather
  than the artifact makes the bill of materials the shared contract everything
  downstream agrees on.
- **The modernization is a measurable supply-chain improvement** — the same
  gate, same day, same database: the monolith fat jar fails with 4 Critical and
  6 High; the extracted Quarkus service passes with zero. And a scan is a
  moving target — clean today is not clean forever — which is why the gate runs
  continuously, not at release.
- **GitOps is named, not faked** — the registry push, the Argo/Flux operator,
  and Istio weighted canary are the honest next steps, deferred under the same
  no-speculative-infrastructure rule the whole book has followed.

Chapter 32 closes the book by re-walking the pattern map against the completed
migration — which patterns earned their place, which were deliberately left on
the shelf, and where the sensible horizons are.

---

*Verification status: <span class="status status--verified">verified
live</span> for the supply-chain gate; <span class="status
status--unverified">unverified</span> for its CI incarnation; the CI/GitOps
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
point-in-time evidence. **Not** verified live: `.github/workflows/supply-chain.yml`
has not run on a GitHub Actions runner (this iteration did not push); it mirrors
`demo.sh`'s three steps via Anchore's `sbom-action`/`scan-action` with
`fail-build: true` + `severity-cutoff: high`. The description of `code-ci.yml`'s
seven jobs and `pages.yml` is sourced directly from the committed workflow files,
not re-run here. The CVE counts are a moving target against grype's daily DB and
will drift — re-run `demo.sh` to refresh. Cited: `.github/workflows/code-ci.yml`
(the seven job names + path filters), `pages.yml`, `supply-chain.yml`;
`examples/10-supply-chain/{demo.sh,policy.yaml,README.md}` and its `evidence/`;
`examples/01-strangler-proxy/src/main/resources/application.properties` (the
retired `strangler.*.enabled` flags); `_plans/decisions.md` (DRQ-020,
DRQ-001).*
</content>
</invoke>
