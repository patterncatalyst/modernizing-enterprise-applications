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
