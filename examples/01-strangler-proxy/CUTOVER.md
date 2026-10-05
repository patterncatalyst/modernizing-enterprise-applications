# CUTOVER.md — Review strangler-fig cutover + decommission (r02-plan S10)

This is the evidence trail for the single most load-bearing claim in the r02
walking skeleton: that a feature flag behind a Camel strangler proxy is a
**safe, reversible cutover switch** — right up until the monolith module it
replaces is deliberately, irreversibly decommissioned.

See also: `README.md` in this directory (the proxy and its route), and
`examples/00-monolith/SMELLS.md` (smell #6, now cured).

## The flag

`strangler.review.enabled`, read by `StranglerProxyRoute` from
`src/main/resources/application.properties`. Content-based routing on the
`/api/reviews` path prefix picks the backend:

| Flag | Backend for `/api/reviews/**` | Backend for everything else |
|---|---|---|
| `false` | monolith `:8080` | monolith `:8080` |
| `true` (**committed default**, r02/S10 onward) | review-service `:8081` | monolith `:8080` |

## Timeline of evidence

### 1. Baseline — flag OFF (reversibility window, pre-decommission)

With the flag at its then-default (`false`) and the monolith still carrying
its Review module, the full behavior-equivalence suite
(`tooling/newman/mea.postman_collection.json`, 16 requests / 49 assertions)
was run unchanged through the proxy:

```
demos/demo-equivalence.sh http://localhost:8888
```

**Result: 49/49 assertions green, 0 failed.** Review was served by the
monolith, reached through the proxy — the proxy introduced zero observable
difference.

### 2. Bug found and fixed during the cutover check

The next step — flip the flag on and re-run the suite, expecting Review to now
be served by `examples/02-review-service` — initially also came back
**49/49 green**. That result was **misleading**: a defect in the route's
content-based routing predicate meant Review traffic was *never* actually
reaching the Quarkus service, regardless of the flag.

**Root cause:** `platform-http:/api?matchOnUriPrefix=true` sets
`CamelHttpPath` to the **full** incoming request path (e.g. `/api/reviews`),
not a path relative to the route's own `/api` consumer prefix. The original
predicate checked `${header.CamelHttpPath} startsWith '/reviews'`, which never
matched `/api/reviews` — so the `choice()` always fell through to `otherwise()`
(the monolith), for every request, independent of the flag.

**Why it went unnoticed:** `examples/02-review-service` is still Phase A
(DRQ-029) and reads/writes the exact same shared `reviews` table in the same
Postgres database as the monolith. With both "backends" returning identical
data from the same underlying rows, the equivalence suite stayed green while
silently testing the wrong backend — a false positive.

**How it was caught:** before trusting the "cutover" result, the monolith was
stopped and the Review route was re-tried through the proxy. A correctly
wired cutover should have kept working (Review is a different, still-running
process); instead it failed the same way every other `/api/**` path did,
proving every request — Review included — was still targeting the (now dead)
monolith.

**The fix** (`StranglerProxyRoute.java`): the predicate now checks
`${header.CamelHttpPath} startsWith '/api/reviews'`, matching the path shape
`platform-http` actually presents.

**Re-verification after the fix**, with the monolith stopped:

```
GET /api/reviews?sku=SKU-WIDGET-001 -> 200 (served by review-service; monolith is down)
GET /api/orders                     -> 500 (still targets the dead monolith, as expected)
```

This is a stronger proof than a status-code-only suite pass: it shows the
proxy's routing *decision* is correct, not just that the two backends happen
to agree.

### 3. Decommission — Review removed from the monolith

With the routing fix verified, the monolith's Review application code was
removed:

- Deleted: `review/Review.java`, `review/ReviewRepository.java`,
  `review/ReviewCreate.java`, `review/ReviewService.java`,
  `review/ReviewController.java`, `common/ReviewDto.java`, and the
  corresponding tests (`ReviewControllerTest`, `ReviewServiceTest`).
- The smoke test (`SixContextsSmokeTest`) was updated: the old
  `reviewContextRespondsAndEnforcesSharedSecurity` test (which asserted
  review reads/writes against the monolith) was replaced with
  `reviewIsNoLongerServedByTheMonolith`, asserting the monolith now 404s on
  `/api/reviews`.
- `security/SecurityConfig.java` and the `spring-boot-starter-security` /
  `spring-security-test` Maven dependencies were **deliberately left in
  place** — they're now vacuous (no route in the monolith matches
  `/api/reviews` anymore, so the one authenticated rule never fires) rather
  than deleted, to keep this change scoped to Review's own files.
- **The `reviews` Postgres table (and its seed rows) was deliberately NOT
  dropped.** `examples/02-review-service` is still in its Phase A
  (spring-compat-lift) form and reads/writes that table directly in the SAME
  shared `monolith` database (`quarkus.hibernate-orm.schema-management.strategy=none`
  in its `application.properties` — it owns no schema of its own yet).
  Dropping the table would have broken the very service this cutover just
  promoted to be the sole owner of Review traffic. True per-context data
  ownership for Review (its own schema/database) is deferred to ch.18/19
  (shared data → owned data, CDC backfill), same as every other context —
  this is a documented data-layer cleanup, not an oversight. See
  `examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/common/Customer.java`
  javadoc for the in-code version of this note.
- `strangler.review.enabled=true` was made the **committed default** in
  `application.properties` (previously `false`).

**Rebuild + test, post-decommission:**

```
mvn -f examples/00-monolith clean verify
```

Green — zero Review tests remain, and nothing else in the monolith failed to
compile or broke, confirming Review had no other in-process dependents (the
smell it cured: "tangled into shared security but genuinely independent").

**Direct confirmation the monolith no longer serves Review:**

```
GET http://localhost:8080/api/reviews -> 404 Not Found
GET http://localhost:8080/api/orders  -> 200 OK   (unaffected)
```

### 4. Post-decommission — flag ON (permanent), full suite through the proxy

With the proxy rebuilt (routing fix) and restarted with no flag override
(relying purely on the new committed default), the full behavior-equivalence
suite was run through the proxy once more:

```
demos/demo-equivalence.sh http://localhost:8888
```

**Result: 49/49 assertions green, 0 failed.** Review is served by
`examples/02-review-service`; every other request is served by the slimmed,
five-context monolith. The collection itself was never edited — only
`--baseUrl` (never) and the proxy's flag/code changed across the three runs.

### 5. Confirming the reversibility window is genuinely closed

As a final sanity check, the proxy was started once more with
`-Dstrangler.review.enabled=false` (an explicit override of the committed
default):

```
GET /api/reviews?sku=SKU-WIDGET-001 -> 404  (correctly routed to the monolith, which has nothing left to serve)
GET /api/orders                     -> 200  (unaffected)
```

This is the expected, honest post-decommission behavior: the flag still
mechanically works (it is not hardcoded or dead code), but flipping it back to
`false` no longer gets you a working Review endpoint, because the monolith
side of that choice was deliberately, irreversibly removed. Reversibility was
a real, demonstrated property through r02/S7–S9; decommission is the one
deliberate point where that property ends.

## Summary of the three suite runs

| # | Flag | Review served by | Everything else served by | Result |
|---|---|---|---|---|
| 1 | `false` | monolith (pre-decommission) | monolith | **49/49**, 0 failed |
| 2 | `true` | review-service (post-fix, post-decommission) | monolith (slimmed, 5 contexts) | **49/49**, 0 failed |
| 3 | `false` (override, post-decommission) | *(nothing — monolith 404s)* | monolith | n/a — demonstrates the closed window, not a suite run |

Run #2 above folds together what r02-plan S10 calls out as two separate
checkpoints — "cutover verified" and "post-decommission verified" — because
the routing bugfix (§2 above) happened between the original, invalidated
cutover attempt and the decommission step. The original flag-OFF baseline
(run #1) remains valid evidence on its own: the routing defect only ever
caused requests to go to the monolith regardless of the flag, which is
already the correct behavior when the flag is `false`.

---

## Notification cutover (notification-plan.md S6/S7, ch.17, DRQ-036/DRQ-037)

A second flag, `strangler.notification.enabled`, was added to this proxy
alongside Review's — same content-based-routing shape, same full-path
predicate discipline (`/api/notifications`, learned the hard way from §2
above). This is the **read-side** half of a two-flag reversibility story: the
monolith's `notification.mode=synchronous|outbox` config is the write-side
half (a different file — `examples/00-monolith/src/main/resources/
application.yml` — not touched by this proxy). A real cutover flips both
together.

### 1. Reversibility baseline — notification flag OFF, monolith synchronous

All four backends up (monolith `:8080` default/synchronous, review-service
`:8081`, notification-service `:8083`, proxy `:8888`), proxy flags at their
S6/S10 defaults (`strangler.review.enabled=true`,
`strangler.notification.enabled=false`):

```
demos/demo-equivalence.sh http://localhost:8888
```

**Result: 58/58 assertions green, 0 failed.** `/api/notifications` served
synchronously by the monolith (unmodified behavior); the bounded-wait poll in
the "Notification Context Contract" folder matched on its very first GET —
exactly the synchronous-backend shape DRQ-037 predicts.

### 2. CUTOVER — both flags flipped

Monolith restarted with `NOTIFICATION_MODE=outbox` (checkout now writes an
outbox row instead of sending a synchronous confirmation); proxy restarted
with `strangler.notification.enabled=true`. Full suite re-run, unmodified,
through the proxy:

```
demos/demo-equivalence.sh http://localhost:8888
```

**Result: 70/70 assertions green, 0 failed.** This time the bounded-wait poll
in the Notification folder *genuinely retried* — several `GET
/api/notifications` attempts came back `200` with no matching notification
yet before the match appeared — real, observed outbox -> Kafka -> consumer
latency, not an instant synchronous hit. `/api/notifications` is now served
by `examples/03-notification-service`; checkout folders (Scenarios 1-3) and
the Review folder were unaffected and stayed green, exactly as DRQ-037
predicted for the async backend.

### 3. Negative check — the consumer stopped, the assertion MUST go RED

With the cutover state still active, the notification-service process (its
Kafka consumer **and** its `/api/notifications` read surface — they are the
same process) was killed. Only the "Notification Context Contract" folder
was re-run through the proxy:

```
newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json \
    --env-var "baseUrl=http://localhost:8888" \
    --folder "Notification Context Contract"
```

**Result: RED — newman exit code 1.** The exact failing assertions:

```
1. AssertionError  Notification read surface returns 200
                    expected response to have status code 200 but got 500
2. AssertionError  Content-Type is application/json
                    expected 'text/plain; charset=utf-8' to include 'application/json'
3. JSONError        No data, empty input at 1:1
```

(`GET /api/notifications?customerId=1` came back `500` because the proxy's
`notification` target was unreachable — connection refused, forwarded with
`throwExceptionOnFailure=false` as a plain-text 500 body — so the test
script's `pm.response.json()` call threw before the bounded-wait retry
branch was ever reached.) This is a **stronger** failure than a soft
budget-exhaustion (empty-list-after-10-retries): it proves the suite does not
quietly pass when the entire async pipeline — consumer included — is down,
closing the exact false-equivalence gap CUTOVER.md §2 identified for Review.

The notification-service process was then restarted. Re-running the same
folder:

**Result: GREEN — 11/11 assertions, newman exit code 0.** On restart the
service immediately drained the backlog (`consumed order.placed for order 50
(customer 1)` in its logs — the event published by the relay while the
consumer was down, delivered at-least-once once a consumer was listening
again), and the freshly-created order from this re-run was also observed
within budget.

### 4. Committed default flipped to true (post-cutover)

With all three results recorded, `strangler.notification.enabled=true` was
made the committed default in `application.properties` (mirroring Review's
S10 flip), the proxy was rebuilt and restarted relying purely on that
default (no `-D` override), and the full suite was run once more as a final
sanity check:

```
demos/demo-equivalence.sh http://localhost:8888
```

**Result: 64/64 assertions green, 0 failed.**

### Summary of the notification-cutover runs

| # | Notification flag | Monolith `notification.mode` | `/api/notifications` served by | Result |
|---|---|---|---|---|
| 1 | `false` | `synchronous` (default) | monolith | **58/58**, 0 failed (reversibility baseline) |
| 2 | `true` (override) | `outbox` | notification-service | **70/70**, 0 failed (cutover; poll genuinely retried) |
| 3a | `true` | `outbox`, consumer **down** | *(nothing — 500)* | **RED** — negative check, proves the async path is real |
| 3b | `true` | `outbox`, consumer restarted | notification-service | **GREEN** — 11/11, 0 failed |
| 4 | `true` (committed default) | `outbox` | notification-service | **64/64**, 0 failed (final sanity, post-flip) |

Unlike Review's S10 flip, this is **not** the point where reversibility
closes — the monolith's synchronous notification path and
`/api/notifications` read surface are untouched (decommission is
notification-plan S8, out of scope for this cutover step). Flipping this flag
back to `false` today still reaches a working monolith notification surface.
