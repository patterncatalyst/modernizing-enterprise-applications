# The Reference Monolith — frozen "before" picture (order-plan.md S10, DRQ-070/DRQ-024)

This module is the "before" picture for the whole book. It originally ran as
a single Spring Boot deployable with six bounded contexts (order, inventory,
payment, shipping, notification, review) sharing one Postgres schema and one
JVM. Six deliberate smells were planted on purpose and tagged in-code; see
`SMELLS.md` for the full catalogue, cure evidence, and the final strike-through
of smells #2 and #3 (ACID → ACD realized for ALL contexts, order-plan.md S10).

## Where to find the running system today

Every one of the six bounded contexts has been extracted to its own Quarkus
service and decommissioned from this module — order was the sixth and last
(order-plan.md S10, DRQ-070, the project's single deliberately irreversible
step). **This module (`main` branch) is now a frozen, still-building shell
with ZERO live bounded contexts** — `mvn -f examples/00-monolith clean verify`
still passes, but every former `/api/**` path 404s (see
`SixContextsSmokeTest`). It is kept permanently in-repo as the behavior
suite's golden-baseline referent (DRQ-024), not as something to run day to
day.

The actual running system today is:

- `examples/02-review-service`, `examples/03-notification-service`,
  `examples/04-inventory-service`, `examples/05-payment-service`,
  `examples/06-shipping-service`, `examples/07-order-service` — the six
  extracted bounded contexts, each independently deployable.
- `examples/08-graphql-gateway` — an additive, data-less aggregation gateway
  with its own front door (order-plan.md DRQ-069).
- `examples/01-strangler-proxy` — originally the strangler-fig proxy fronting
  this monolith; now the system's permanent REST edge router (order-plan.md
  S10), with no monolith left to fall back to.

## Comparing "before" and "after" side by side

To see the **complete, runnable monolith** — all six bounded contexts still
live, exactly as it stood before any extraction began — check out:

- **`reference/monolith-before`** branch (tag **`v0-monolith`**): the complete
  "before" state, runnable end-to-end with no extracted services needed.
- **`stage/NN-*-extracted`** tags: one tag per extraction, stepping through
  the decomposition roadmap in order (review → notification → inventory →
  payment → shipping → order), so any two adjacent stages can be diffed to
  see exactly what one extraction moved.

```bash
# the complete runnable "before":
git worktree add /tmp/mea-before reference/monolith-before   # or: git checkout v0-monolith

# step through one extraction at a time:
git log --oneline stage/01-review-extracted..stage/02-notification-extracted
```

`main` (this branch) is the finished system: six independently deployable
services + the GraphQL gateway + the permanent edge router, with this module
kept alongside them, frozen, as the "before" half of that comparison.

## Re-deriving the golden baseline (break-glass only)

This module is never brought up as part of the normal running topology or any
CI gate anymore (`compose.yaml` never ran it as a container; nothing starts
its process by default post-S10). It remains fully buildable and bootable —
`mvn -f examples/00-monolith clean verify`, or `java -jar target/monolith.jar`
against a throwaway Postgres — strictly as a break-glass audit path for
re-deriving `tooling/newman/GOLDEN-BASELINE.md` from scratch; see
`tooling/newman/README.md` for the full procedure (it uses the
`reference/monolith-before` branch, not this frozen shell, since this shell
has nothing left to serve).
