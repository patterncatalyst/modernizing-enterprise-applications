# The Six Deliberate Smells

This monolith is the "before" picture for the whole book (`_plans/build-plan.md`
section D). Six smells are planted **on purpose**, each tagged in-code with a
`SMELL[ch.NN]` comment (grep for `SMELL\[ch\.` to find every occurrence) and
mapped below to the chapter(s) that later cure it.

| # | Smell | Where it's planted | Curing chapter(s) |
|---|---|---|---|
| 1 | **Shared schema / cross-context joins** — one Postgres schema with direct JPA `@ManyToOne` FKs crossing bounded-context boundaries (`order_items` → `inventory_items`, `payments`/`shipments`/`notifications` → `orders`, `reviews` → `customers` + `inventory_items`, everything → `customers`). *(Post r02/S10: `reviews` is still in this shared schema and still FK'd to `customers`/`inventory_items`. Phase B of the review-service extraction is complete — it runs on idiomatic Quarkus (REST/Panache/CDI, no Spring-compat shim); see `examples/02-review-service/MIGRATION.md` — but the `reviews` table was deliberately left in the shared schema rather than decomposed alongside the code: the service boundary and the data boundary are separable decisions, and data decomposition for this table is deferred to ch.18/19. `examples/02-review-service` therefore still reads and writes that shared table directly against the same database; see `common/Customer.java` javadoc. Review's Java code, however, is gone from this module. Post r04/S8: the same is now true of `notifications` — its JPA entity (`notification/Notification.java`) was deleted along with the rest of the Notification context's monolith code, but the table itself stays in this shared schema, write-only history now that `examples/03-notification-service` owns its own schema; see SMELL #4 below.)* | `common/Customer.java`, `inventory/InventoryItem.java`, `order/Order.java`, `order/OrderItem.java`, `payment/Payment.java`, `shipping/Shipment.java`, `db/migration/V1__init_schema.sql` | ch.18 (shared data → owned data), ch.19 (CDC backfill) |
| 2 | **God `OrderService`** — one service is the single orchestration point for checkout and reaches directly into inventory, payment, shipping, and notification's services/repositories/entities. | `order/OrderService.java` | ch.26 (order extraction, hardest/last — every other extraction first removes one of this service's direct dependencies); motivates sagas (ch.23/24) |
| 3 | **One in-process ACID transaction spanning contexts** — `OrderService#placeOrder` wraps inventory decrement + order persistence + payment + shipment + notification in a single `@Transactional`, so any failure anywhere rolls back everything, everywhere. | `order/OrderService.java` (`placeOrder`), `common/exception/PaymentDeclinedException.java`, `common/exception/InsufficientStockException.java` | ch.23 (choreographed saga), ch.24 (orchestrated saga), ch.22 (ACID → ACD) |
| 4 | ~~**Synchronous notification inside the checkout transaction**~~ — **CURED in r04/S8.** The order-confirmation notification used to be sent via a direct in-process call (`notification.NotificationService#sendOrderConfirmation`), inside the same `@Transactional` as the rest of checkout, coupling checkout latency and failure domain to an orthogonal concern. `OrderService#placeOrder` now unconditionally writes an `order.placed` row to the transactional outbox (atomically, in the same transaction) instead — the reversibility flag (`notification.mode=synchronous\|outbox`) introduced for the cutover has been removed along with the synchronous branch, so outbox is the only path. Notification's controller/service/repository/entity have been removed from this module entirely; it is now served by `examples/03-notification-service` (consuming `order.placed` off Kafka, asynchronously, via its own SmallRye Reactive Messaging consumer) behind the strangler proxy, with `strangler.notification.enabled=true` as the permanent default. The underlying `notifications` table is deliberately **kept** in the shared schema (write-only history, no longer read anywhere) — true data decomposition for it is deferred to ch.18/19, same treatment as the `reviews` table in SMELL #1. | *(formerly)* `notification/NotificationService.java`, `notification/NotificationController.java`, `notification/Notification.java`, `notification/NotificationRepository.java`, `common/NotificationDto.java`; *(still present)* `order/OrderService.java` (`placeOrder`, now outbox-only), `common/Topics.java`, `common/outbox/OutboxEvent.java`, `common/outbox/OutboxRelay.java`, `common/outbox/OrderPlacedEvent.java` | ch.17 (notification extraction — transactional outbox + async event-driven consumer; cured end-to-end in r04/S8) |
| 5 | **No anti-corruption layer / leaky domain model** — `OrderService` receives and holds a raw `InventoryItem` JPA entity from `InventoryService`, not a DTO or contract; there is no translation layer at the seam. | `inventory/InventoryService.java` (`findBySkuOrThrow`), `order/OrderService.java` | ch.16 (content-based routing & the ACL — Camel message translator / content enricher at the seam) |
| 6 | ~~**Review tangled into shared security but genuinely independent**~~ — **CURED in r02/S10.** Review had no runtime dependency on order/inventory/payment/shipping/notification (REST-only, no synchronous collaborator), yet its one authenticated endpoint was governed by the monolith's single global `SecurityFilterChain`, alongside every other context's rules. Review's controller/service/repository/entity have been removed from this module entirely; it is now served by `examples/02-review-service` (its own standalone Basic-auth/OIDC-track security config) behind the strangler proxy, with `strangler.review.enabled=true` as the permanent default. `security/SecurityConfig.java` remains in this module (harmless/vacuous — its `/api/reviews` rule no longer matches any route here) since nothing else in the monolith needs authentication yet. | *(formerly)* `security/SecurityConfig.java`, `review/ReviewController.java`, `review/Review.java`, `review/ReviewService.java` | ch.15 (Review extraction — the walking skeleton; demonstrated end-to-end in r02/S10) |

## Finding them

```sh
grep -rn 'SMELL\[ch\.' examples/00-monolith/src
```

Every smell above appears at least once in that output (smell 6's remaining
`SMELL[ch.15]` tag lives in the now-vacuous `security/SecurityConfig.java`,
since Review's own files were deleted in r02/S10). Smell 4's `SMELL[ch.17]`
tag is now gone from this module's `src` entirely — it lived only in the
deleted `notification/NotificationService.java` — so its absence from the
grep output above is itself evidence of the cure. Smells 1–3 and 5 are
exercised end-to-end by the `checkoutFlowSpansAllFiveNonReviewContextsInOneTransaction`
test (name kept for history; checkout now spans order/inventory/payment/
shipping plus an outbox write, not a synchronous notification call); smell
4's cure is exercised by `notificationIsNoLongerServedByTheMonolith`
(asserting a 404 from the monolith) plus the behavior-equivalence suite's
"Notification Context Contract" folder (bounded-wait poll) run against
`examples/03-notification-service`; smell 6's cure is exercised by
`reviewIsNoLongerServedByTheMonolith` (asserting a 404 from the monolith)
plus the behavior-equivalence suite's "Review Context Contract" folder run
against `examples/02-review-service` — all in/alongside
`src/test/java/dev/patterncatalyst/monolith/smoke/SixContextsSmokeTest.java`.
