# CUTOVER.md — Payment choreographed-saga cutover (payment-plan.md S8, ch.23, DRQ-047/049/054/055)

This is the evidence trail for the ch.23 equivalence gate: flipping BOTH
`payment.mode=choreographed` (monolith) and `strangler.payment.enabled=true`
(strangler proxy) together, running the full behavior-equivalence suite
through the proxy, proving reversibility, and proving the bounded-wait /
negative-check discipline (DRQ-037/DRQ-055) genuinely exercises the
choreography rather than passing vacuously (the CUTOVER.md §2 trap this
project has paid for once already, on Review).

See also: `examples/01-strangler-proxy/CUTOVER.md` (Review/Notification/
Inventory's own cutover evidence, the template this mirrors),
`_plans/iterations/payment-plan.md` S8 (the step this executes) and its
HARD PARTS H1 (sync→async contract change) and H3 (Scenario 3 compensation
via choreography — THE crux).

## The two flags

| Flag | Location | Default (committed) | Cutover override used here |
|---|---|---|---|
| `payment.mode` | `examples/00-monolith/src/main/resources/application.yml` (env var `PAYMENT_MODE`) | `synchronous` | `PAYMENT_MODE=choreographed` |
| `strangler.payment.enabled` | `examples/01-strangler-proxy/src/main/resources/application.properties` | `false` | `-Dstrangler.payment.enabled=true` |

Both overrides are **runtime-only** (env var / `-D` system property). Neither
committed default was changed — flipping the committed defaults to make the
cutover permanent is payment-plan **S9** (decommission), explicitly out of
scope here.

## Topology brought up (all against the shared local podman stack)

The podman stack (`mea-postgres`, `mea-kafka`, `mea-connect`, `mea-lgtm`) was
already running (left up by prior steps) and was **not** restarted.

| Service | Command | Port(s) |
|---|---|---|
| inventory-service | `java -jar examples/04-inventory-service/target/quarkus-app/quarkus-run.jar` | `:8084` HTTP, `:9004` gRPC |
| payment-service | `java -jar examples/05-payment-service/target/quarkus-app/quarkus-run.jar` | `:8085` |
| review-service | `java -jar examples/02-review-service/target/quarkus-app/quarkus-run.jar` | `:8081` |
| notification-service | `java -jar examples/03-notification-service/target/quarkus-app/quarkus-run.jar` | `:8083` |
| monolith (choreographed) | `SPRING_DATASOURCE_PASSWORD=monolith_dev_only PAYMENT_MODE=choreographed java -jar examples/00-monolith/target/monolith.jar` | `:8080` |
| strangler proxy (payment flag on) | `java -Dstrangler.payment.enabled=true -jar examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar` | `:8888` |

**Why review-service and notification-service were also started:** the
behavior-equivalence suite is one collection covering all contexts, and the
proxy's own committed defaults (`strangler.review.enabled=true`,
`strangler.notification.enabled=true`, `strangler.inventory.enabled=true`)
already route those paths to the extracted services — a full-suite run
through the proxy 500s on every Review/Notification request if those two
services aren't also up. This is topology, not scope creep: no code or flag
belonging to Review/Notification/Inventory was touched.

**Operational note (pre-existing, not introduced by this step):** the
monolith's `application.yml` default datasource password (`monolith`) does
not match the podman-stack Postgres password actually configured in `.env`
(`monolith_dev_only`, `POSTGRES_PASSWORD`). The monolith refuses to start
(Flyway `FATAL: password authentication failed`) without
`SPRING_DATASOURCE_PASSWORD=monolith_dev_only` set. This is unrelated to the
payment choreography and was not touched (it is a config file, out of this
step's scope) — just documented here so the command above is reproducible.

**Data note:** the shared dev Postgres had accumulated enough prior test
traffic that `inventory.inventory_items.quantity_on_hand` for
`SKU-WIDGET-001` (the sku both Scenario 1 and Scenario 3 exercise) was down
to **3** on hand before this run — not enough to survive the ~4 checkout
cycles a full cutover + reversibility + negative-check sequence requires.
It was topped up with a plain data `UPDATE` (no Flyway migration touched, no
checksum risk):
```sql
UPDATE inventory.inventory_items SET quantity_on_hand = 100 WHERE sku = 'SKU-WIDGET-001';
```

## 1. CUTOVER run — both flags flipped, full suite through the proxy

With the topology above up and both overrides applied, the full suite was
run three times for stability:

```
demos/demo-equivalence.sh http://localhost:8888
```

**Results:**

| Run | Assertions | Failed | Duration |
|---|---|---|---|
| 1 | 89 | 2 | 8.0s |
| 2 (rerun) | 90 | 2 | 7.9s |
| 3 (final sanity, after negative-check cycle) | 85 | 2 | 5.8s |

(The small assertion-count variance across runs is the bounded-wait polls
retrying a different number of times per run — the same documented variance
pattern as Inventory's own CUTOVER.md §2 — not a correctness difference.)

**The two load-bearing checkout scenarios, both GREEN every run:**

- **Scenario 1 (happy path):** `POST /api/orders` → `202 Accepted` + `Location`,
  body `status: PENDING` (DRQ-047). The bounded-wait poll of
  `GET /api/orders/{id}` retried **6 attempts** (~3s) before observing
  `CONFIRMED` — genuine `order.placed` → payment-service capture →
  `payment.captured` → `OrderSagaListener.onPaymentCaptured` → confirm latency,
  not an instant synchronous hit. Total/items unchanged
  (`totalCents=1999`, one `SKU-WIDGET-001` line). Stock decremented by
  exactly 1 (1d).
- **Scenario 3 (payment-declined, THE crux, H3):** `POST /api/orders` with
  `paymentMethod: CARD-DECLINE` → `202 Accepted`, body `status: PENDING`. The
  bounded-wait poll of `GET /api/orders/{id}` retried **8 attempts** (~4s)
  before observing `PAYMENT_DECLINED` (`payment.declined` →
  `OrderSagaListener.onPaymentDeclined` → `order.declinePayment()` +
  compensating gRPC `Release`). A second bounded-wait poll of
  `GET /api/inventory/SKU-WIDGET-001` then confirmed stock returned to
  **net-zero** — on the first attempt in this run, because by the time 3d
  executes the Release (triggered by 3c's own polling) has typically already
  landed.
- **Scenario 2 (out-of-stock)** stayed fully synchronous: `POST /api/orders`
  for 999999 units of `SKU-GIZMO-003` → `409 Conflict` immediately (Reserve
  is still synchronous; checkout never reaches the payment step).

**Explicit before/after stock numbers for a forced decline (the H3 crux,
proven with real numbers, not just a green assertion)** — a `CARD-DECLINE`
checkout placed directly (outside the suite) through the proxy, polling
`:8084` directly:

| Step | `SKU-WIDGET-001` on hand | Order status |
|---|---|---|
| Before checkout | **96** | — |
| Immediately after `POST` (synchronous gRPC `Reserve` already committed) | **95** | `PENDING` (202 response) |
| After the bounded-wait observes the decline (choreographed `Release` fired) | **96** | `PAYMENT_DECLINED` |

Net-zero: **96 → 95 → 96**, exactly reversing the reservation. Repeated a
second time later in this evidence run (after the payment-service restart in
§3 below) for reconfirmation:

| Step | `SKU-WIDGET-001` on hand | Order status |
|---|---|---|
| Before checkout | **88** | — |
| Immediately after `POST` | **87** | `PENDING` |
| After the bounded-wait observes the decline | **88** | `PAYMENT_DECLINED` |

**Payment Context Contract — verified MANUALLY, not via newman (see "Gap
found" below):**

```
GET http://localhost:8888/api/payments?orderId=167
-> [{"id":89,"orderId":167,"amountCents":1999,"method":"CARD-DECLINE","status":"DECLINED","createdAt":"2026-10-05T16:47:55.397228Z"}]

GET http://localhost:8085/api/payments?orderId=167   (direct to payment-service — identical body)
-> [{"id":89,"orderId":167,...}]

GET http://localhost:8080/api/payments?orderId=167   (direct to the monolith — proves genuine routing)
-> []   (200 OK, empty — the monolith never charged order 167 in-line in choreographed mode,
         so its own frozen Payment table has nothing for this order; the proxy's
         non-empty, byte-identical-to-the-service response proves /api/payments
         traffic is genuinely reaching examples/05-payment-service, not silently
         falling through to the monolith — the exact false-positive check
         CUTOVER.md's Review §2 established the discipline for.)

GET http://localhost:8888/api/payments/84
-> {"id":84,"orderId":162,"amountCents":9998,"method":"CARD-DECLINE","status":"DECLINED","createdAt":"2026-10-05T16:31:31.115722Z"}
```

**GAP FOUND — not fixed here (out of this step's scope):** payment-plan.md
S2's acceptance criteria calls for adding a **"Payment Context Contract"**
folder to `tooling/newman/mea.postman_collection.json` asserting
`/api/payments?orderId=` and `/api/payments/{id}` shapes (mirroring the
existing Review/Notification/Inventory Context Contract folders). That
folder **does not exist** in the collection as committed — confirmed by
searching the collection for "Payment Context Contract" (no match) and by
walking every folder name in the collection (Smoke, Scenario 1/2/3, Review/
Notification/Inventory Context Contract — no Payment folder). This step's
scope is flag config + evidence only (CUTOVER.md + demo script); editing the
Newman collection is explicitly out of scope (it is the project's
equivalence gate, single-writer discipline). The manual curl verification
above substitutes for it and the shapes are confirmed correct and
byte-identical across the proxy and the service, but **this is a real,
unresolved gap in the automated suite** that should be closed (either by
revisiting S2 or as a small follow-up) before this folder's absence is
assumed to be permanently acceptable.

## 2. Reversibility — both flags flipped back

The choreographed monolith and cutover proxy were stopped; the monolith was
restarted with **no** `PAYMENT_MODE` override (back to its default
`synchronous`) and the proxy with **no** `-D` override (back to its default
`strangler.payment.enabled=false`):

```
SPRING_DATASOURCE_PASSWORD=monolith_dev_only java -jar examples/00-monolith/target/monolith.jar
java -jar examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar
```

A direct sanity checkout confirmed the synchronous contract was back
immediately: `POST /api/orders` with `CARD-VISA` → `201 Created`, body
`status: CONFIRMED` at POST time (no polling needed).

Full suite re-run:

```
demos/demo-equivalence.sh http://localhost:8888
```

**Result: 79/79 assertions green, 0 failed, 2.7s total duration** (vs. ~6-8s
in the cutover state) — every bounded-wait poll matched on its **first**
attempt (0 retries), exactly the synchronous-backend shape DRQ-037/DRQ-055
predict. Scenario 1 → `201`/`CONFIRMED` immediately; Scenario 3 → `402`
immediately with the documented `error=PAYMENT_DECLINED` error body (no
order resource ever created, exactly the pre-saga contract); Scenario 2 →
`409` (unchanged, as always). This proves the reversibility window payment-
plan S8 is supposed to leave open is genuinely open — flipping both flags
back is a config change and a restart, nothing more, right up until
decommission (S9).

## 3. Negative check (DRQ-055, H2) — the payment consumer stopped, the assertion MUST go RED

Both flags were flipped back to the cutover state (monolith
`PAYMENT_MODE=choreographed`, proxy `-Dstrangler.payment.enabled=true`), and
the payment-service process (its `order.placed` **consumer** and its
`payment.captured`/`payment.declined` **producer** — the same process) was
killed.

A checkout was placed through the proxy with `CARD-VISA` (a normal, capture-
bound order), and just the Scenario 1 folder was re-run:

```
newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json \
    --env-var "baseUrl=http://localhost:8888" \
    --folder "Scenario 1 — Happy-Path Checkout" \
    --reporters cli
```

**Result: RED — newman exit code 1, 1 of 23 assertions failed:**

```
1. AssertionError  Order reaches CONFIRMED within the bounded-wait budget (10 x 500ms)
                   order 177 status after 10 attempt(s): expected 'PENDING' to deeply equal 'CONFIRMED'
```

Confirmed directly: `GET /api/orders/177` stayed `PENDING` and
`GET /api/inventory/SKU-WIDGET-001` stayed decremented (the gRPC `Reserve`
at checkout is synchronous and independent of the payment consumer — only
the *confirmation* is blocked). This is exactly the DRQ-055 negative check:
with the choreography's one and only consumer of `order.placed` dead, the
order is stuck `PENDING` forever and the bounded-wait assertion genuinely
fails, closing the false-equivalence gap a vacuous "accept 202 and move on"
suite would have papered over.

The payment-service process was then restarted. Within seconds of startup
(no manual intervention), order 177 drained from the backlog and reached
`CONFIRMED` — `order.placed` is at-least-once delivered and the payment
service's consumer group picked up the stale, uncommitted offset exactly as
Kafka's delivery semantics predict. Re-running the same folder on a **fresh**
checkout, ~13s after the restart:

**Result: GREEN — 20/20 assertions, newman exit code 0.**

**A real bug found and fixed WHILE SCRIPTING this (`demos/demo-payment-
cutover.sh`) — in the script's process-management, not in any extracted
service — honestly recorded, not papered over:** the first two attempts at
automating this negative check's GREEN re-verification produced a
reproducible **RED** on the freshly-placed order (`order 192 ... expected
'PENDING' to deeply equal 'CONFIRMED'`, then again as `order 201 ...` even
after adding a plain stabilization `sleep`), while the exact same sequence
done manually (the walkthrough immediately above) went green first try.
Root-caused by inspecting Postgres directly: order 201's `order.placed`
outbox row published fine (`published_at` ~1s after creation, the monolith
relay's normal cadence), but **payment-service never captured it at all** —
no row ever appeared in `payment.payments` or `payment.outbox` for that
order, even well after the test window closed. The payment-service log
showed its Kafka consumer connecting normally, but a `Close timed out with
2 pending requests to coordinator` warning on shutdown pointed at the real
cause: the script's own `stop_named` helper sent `SIGTERM` and then
unconditionally `SIGKILL`ed the process after a flat 2-second sleep,
**before the JVM's Kafka client had necessarily finished sending a
`LeaveGroupRequest`**. A consumer-group member killed that abruptly is only
forgotten by the broker after `session.timeout.ms` elapses — so the NEXT
consumer instance to join the SAME `payment-service` consumer group (every
restart reuses the same, intentionally-stable group id) can be stalled
waiting for a partition reassignment that depends on the stale, already-dead
previous member timing out server-side — comfortably longer than the
suite's 5s (`10 x 500ms`) bounded-wait budget, and not something a few extra
seconds of `sleep` **after** the new instance starts can fix (the stall is
on the broker side, keyed to the OLD member, not the new one's readiness).
**Fix (in `demos/demo-payment-cutover.sh` only — no service code touched):**
`stop_named` now polls for the process to exit on its own (up to 10s) before
ever sending `SIGKILL`, giving the Kafka client room to leave the group
cleanly. After this fix, a full re-run of the script (§ below) produced the
expected **GREEN — 20/20 assertions** on the very first attempt, with no
backlog to drain. This is recorded here because it's a genuinely useful,
general lesson for anyone scripting restarts of a Kafka-consuming service
for a demo or test harness — not a defect in `OrderPlacedConsumer`,
`PaymentOutboxRelay`, or the choreography itself, all three of which behaved
exactly as designed once given a clean consumer-group handoff.

## 4. Forced decline in the restored-cutover state, with explicit stock numbers (H3 crux, reconfirmed)

With payment-service back up and the cutover state otherwise unchanged, a
second forced `CARD-DECLINE` checkout was placed and polled directly:

| Step | `SKU-WIDGET-001` on hand | Order status |
|---|---|---|
| Before checkout | **88** | — |
| Immediately after `POST` | **87** | `PENDING` |
| After the bounded-wait observed the decline (attempt 6 of 10, ~3s) | **88** | `PAYMENT_DECLINED` |

Net-zero confirmed again: **88 → 87 → 88**.

## Summary of all runs

| # | `payment.mode` | `strangler.payment.enabled` | Result |
|---|---|---|---|
| 1 | `choreographed` | `true` | **89/89−2, 90/90−2, 85/85−2** across 3 runs — Scenario 1/2/3 all green every time; net-zero proven with real numbers twice; Payment read contract verified manually (newman folder missing, see Gap above) |
| 2 | `synchronous` (reverted) | `false` (reverted) | **79/79**, 0 failed, 0 retries — reversibility window genuinely open |
| 3a | `choreographed`, payment-service **down** | `true` | **RED** — 1/23 failed; negative check proves the choreography is genuinely exercised (DRQ-055) |
| 3b | `choreographed`, payment-service restarted | `true` | **GREEN** — 20/20, 0 failed |
| 4 | `choreographed` (unchanged) | `true` (unchanged) | forced decline, explicit stock 88→87→88, net-zero reconfirmed |

## A known, honest gap (surfaced, not papered over)

**Every cutover run above has exactly 2 failing assertions, isolated to
"Notification Context Contract / 5a. Place order to trigger a confirmation
notification -> 201 Created":**

```
1. AssertionError  Order created returns 201
                   expected response to have status code 201 but got 202
2. AssertionError  Order status transitions straight to CONFIRMED
                   expected 'PENDING' to deeply equal 'CONFIRMED'
```

**Root cause:** the Notification Context Contract folder (authored in
notification-plan.md S2, before this extraction existed) places its OWN
checkout (step 5a, to generate a notification to observe in 5b) and asserts
the OLD synchronous contract directly — `201 Created` + `status: CONFIRMED`
at POST time — the same client assumption H1 names as "every client
assumption that the POST tells me if the order is confirmed breaks."
Payment-plan S2's scope, per the plan, was **Scenario 1 and Scenario 3
only** ("Scenario 2 untouched"); step 5a is a third, independent checkout
call outside those two scenarios that makes the identical assumption and was
never adapted.

**This is not a defect in the payment choreography.** Proof: 5b (the actual
notification check, immediately following 5a in the same folder) **passes**
in every run — the order 5a created DOES eventually reach `CONFIRMED` (over
the choreography) and the confirmation notification DOES fire and get
observed, exactly as the saga is supposed to behave. Only 5a's own
synchronous-POST assertions are stale. Confirmed independently: in the
reversibility run (§2, synchronous mode restored), the identical 5a
assertions pass (`201`/`CONFIRMED` immediately) — proving the assertions
are correct for a synchronous backend and simply were never updated for the
choreographed one.

**Why this is surfaced here rather than fixed:** fixing it means editing
`tooling/newman/mea.postman_collection.json` (adding the same `201|202` +
bounded-wait tolerance Scenario 1/3 already have, or having 5a reuse an
already-confirmed order instead of placing a new one) — explicitly out of
this step's scope ("flag config + evidence only... do NOT touch... the
Newman collection is the equivalence gate, single-writer discipline"). It is
recorded here, honestly, as a **gap inherited from payment-plan S2's scope**
(S2 adapted Scenario 1 and 3 but missed this collateral assumption in an
unrelated folder) that should be closed in a small follow-up before the
suite can be called unconditionally green in the cutover state. **The
load-bearing claims of this step — Scenario 1 bounded-wait to CONFIRMED,
Scenario 3 bounded-wait to PAYMENT_DECLINED + stock net-zero via the
choreographed Release, Scenario 2 synchronous 409, and the Payment read
contract — are all independently proven green above and are unaffected by
this gap.**

## `demos/demo-payment-cutover.sh` — full scripted reproduction

After the `stop_named` fix above, a complete, unattended run of
`demos/demo-payment-cutover.sh` (bringing up all six processes from cold,
flipping both flags, running the cutover suite, reversibility, and the full
negative check) produced:

| Section | Result |
|---|---|
| Cutover full suite | 89 assertions, 2 failed (the same documented Notification 5a gap — see above), 8.6s |
| Forced decline, explicit stock | `72 -> 71 -> 72`, net-zero |
| Reversibility full suite | 77/77, 0 failed, 2.5s, 0 retries |
| Negative check — payment-service down, Scenario 1 folder | RED, newman exit code 1 |
| Negative check — payment-service restarted, Scenario 1 folder | **GREEN — 22/22, 0 failed, 4.3s** |

All six processes the script started were confirmed stopped on exit; ports
`8080/8081/8083/8084/8085/8888/9004` confirmed free; the podman stack
(`mea-postgres`/`mea-kafka`/`mea-connect`/`mea-lgtm`) was left running.

## Process hygiene

All six application processes (inventory-service, payment-service,
review-service, notification-service, monolith, strangler proxy) started
during this evidence run were stopped at the end; ports `8080`, `8081`,
`8083`, `8084`, `8085`, `8888`, and `9004` were confirmed free. The podman
stack (`mea-postgres`, `mea-kafka`, `mea-connect`, `mea-lgtm`) was left
running, as required — no infra was torn down.
