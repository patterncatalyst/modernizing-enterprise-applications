# Example 10 — The supply-chain gate (SBOM + CVE scan + policy-as-code)

This example is the runnable companion to **Chapter 31**. It is an *isolated,
additive* demonstrator: it does not change any service. It takes two artifacts
this repository already builds — the Spring Boot **monolith** (the "before") and
the extracted Quarkus **order-service** (the "after") — generates a software
bill of materials (SBOM) for each, scans each SBOM against the current
vulnerability database, and applies a committed policy-as-code threshold.

The headline result is the whole book's thesis made measurable: the monolith
fails the gate; the modernized service passes it.

## What it does

For each target in [`policy.yaml`](policy.yaml), `demo.sh` runs three steps:

1. **SBOM** — `syft` produces a CycloneDX JSON bill of materials (the complete
   list of components and versions actually inside the artifact).
2. **Scan** — `grype` scans that SBOM against its vulnerability DB and reports
   every match with a severity.
3. **Policy** — the `fail-on` threshold in `policy.yaml` decides pass/fail. The
   same file, the same three steps, run here and in
   `.github/workflows/supply-chain.yml`.

## Prerequisites

- [`syft`](https://github.com/anchore/syft) and [`grype`](https://github.com/anchore/grype)
  on your `PATH` (`syft version`, `grype version`).
- `jq`.
- The two target artifacts built. `demo.sh` builds them with `mvn -o package`
  if they are missing; to build them yourself first:
  `mvn -q -o package -DskipTests` in `examples/00-monolith` and
  `examples/07-order-service`.

grype downloads/refreshes its vulnerability DB on first use (one network call).

## Run it

```bash
cd examples/10-supply-chain
./demo.sh
```

Expected output (component counts and the monolith's CVE tally move as the
vulnerability DB updates daily — the *shape* is the point):

```
monolith       [before] components=78   Critical=4  High=6  Medium=8  FAIL
order-service  [after ] components=522  Critical=0  High=0  Medium=0  PASS
GATE: FAIL -- at least one artifact breaches policy (fail-on: High).
```

`demo.sh` exits non-zero when any target breaches policy. That non-zero exit is
exactly what fails a CI job — the monolith's `FAIL` is the gate *working*, not a
bug. Evidence is written to `evidence/`: the grype findings JSON per target and
a `summary.txt`. The full SBOMs are regenerated each run and git-ignored.

## How to read the result

- **order-service (after): PASS.** Built on the current Quarkus 3.40.1 platform
  BOM, its 522-component runtime closure carries no High or Critical finding
  against today's DB.
- **monolith (before): FAIL.** Its older `spring-webmvc`, `tomcat-embed-core`,
  `jackson-*`, and `postgresql` driver versions carry 4 Critical and 6 High
  findings. Per-service ownership means you can patch one service's dependency
  and redeploy only it, instead of re-releasing a whole monolith.

A scan is a point-in-time claim against a moving database: a clean artifact
today can fail tomorrow when a new CVE is disclosed. That is why the gate runs
on every push, not once.

## Verification status

**Verified live** on 2026-10-06 with `syft 1.44.0` and `grype 0.112.0`
(vulnerability DB built 2026-10-06). `./demo.sh` produced the gate summary above
and exited `1`: the monolith artifact tripped `fail-on: High` (4 Critical / 6
High), the order-service runtime closure passed (0 / 0). The committed
`evidence/*.grype.json` files are that run's findings snapshots. **Unverified:**
the CI incarnation in `.github/workflows/supply-chain.yml` has not been run on a
live GitHub Actions runner (this iteration did not push); the workflow mirrors
`demo.sh`'s three steps and is authored to run them unchanged. CVE counts will
drift as the grype DB updates — re-run `demo.sh` to refresh the snapshot.
</content>
</invoke>
