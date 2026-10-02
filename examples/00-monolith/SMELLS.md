# The Six Deliberate Smells

This monolith is the "before" picture for the whole book (`_plans/build-plan.md`
section D). Six smells are planted **on purpose**, each tagged in-code with a
`SMELL[ch.NN]` comment (grep for `SMELL\[ch\.` to find every occurrence) and
mapped below to the chapter(s) that later cure it.

| # | Smell | Where it's planted | Curing chapter(s) |
|---|---|---|---|
| 1 | **Shared schema / cross-context joins** — one Postgres schema with direct JPA `@ManyToOne` FKs crossing bounded-context boundaries (`order_items` → `inventory_items`, `payments`/`shipments`/`notifications` → `orders`, `reviews` → `customers` + `inventory_items`, everything → `customers`). *(Post r02/S10: `reviews` is still in this shared schema and still FK'd to `customers`/`inventory_items` — the table was deliberately NOT dropped because `examples/02-review-service` is still Phase A and reads/writes it directly against the same database; see `common/Customer.java` javadoc. Review's Java code, however, is gone from this module.)* | `common/Customer.java`, `inventory/InventoryItem.java`, `order/Order.java`, `order/OrderItem.java`, `payment/Payment.java`, `shipping/Shipment.java`, `notification/Notification.java`, `db/migration/V1__init_schema.sql` | ch.18 (shared data → owned data), ch.19 (CDC backfill) |
| 2 | **God `OrderService`** — one service is the single orchestration point for checkout and reaches directly into inventory, payment, shipping, and notification's services/repositories/entities. | `order/OrderService.java` | ch.26 (order extraction, hardest/last — every other extraction first removes one of this service's direct dependencies); motivates sagas (ch.23/24) |
| 3 | **One in-process ACID transaction spanning contexts** — `OrderService#placeOrder` wraps inventory decrement + order persistence + payment + shipment + notification in a single `@Transactional`, so any failure anywhere rolls back everything, everywhere. | `order/OrderService.java` (`placeOrder`), `common/exception/PaymentDeclinedException.java`, `common/exception/InsufficientStockException.java` | ch.23 (choreographed saga), ch.24 (orchestrated saga), ch.22 (ACID → ACD) |
| 4 | **Synchronous notification inside the checkout transaction** — the order-confirmation notification is sent via a direct in-process call, inside the same transaction as the rest of checkout, coupling checkout latency and failure domain to an orthogonal concern. | `notification/NotificationService.java` (`sendOrderConfirmation`), `common/Topics.java` (the reserved, currently-unused event names this smell will be replaced by) | ch.17 (notification extraction — transactional outbox + async event-driven consumer) |
| 5 | **No anti-corruption layer / leaky domain model** — `OrderService` receives and holds a raw `InventoryItem` JPA entity from `InventoryService`, not a DTO or contract; there is no translation layer at the seam. | `inventory/InventoryService.java` (`findBySkuOrThrow`), `order/OrderService.java` | ch.16 (content-based routing & the ACL — Camel message translator / content enricher at the seam) |
| 6 | ~~**Review tangled into shared security but genuinely independent**~~ — **CURED in r02/S10.** Review had no runtime dependency on order/inventory/payment/shipping/notification (REST-only, no synchronous collaborator), yet its one authenticated endpoint was governed by the monolith's single global `SecurityFilterChain`, alongside every other context's rules. Review's controller/service/repository/entity have been removed from this module entirely; it is now served by `examples/02-review-service` (its own standalone Basic-auth/OIDC-track security config) behind the strangler proxy, with `strangler.review.enabled=true` as the permanent default. `security/SecurityConfig.java` remains in this module (harmless/vacuous — its `/api/reviews` rule no longer matches any route here) since nothing else in the monolith needs authentication yet. | *(formerly)* `security/SecurityConfig.java`, `review/ReviewController.java`, `review/Review.java`, `review/ReviewService.java` | ch.15 (Review extraction — the walking skeleton; demonstrated end-to-end in r02/S10) |

## Finding them

```sh
grep -rn 'SMELL\[ch\.' examples/00-monolith/src
```

Every smell above appears at least once in that output (smell 6's remaining
`SMELL[ch.15]` tag lives in the now-vacuous `security/SecurityConfig.java`,
since Review's own files were deleted in r02/S10). Smells 1–5 are
exercised end-to-end by the `checkoutFlowSpansAllFiveNonReviewContextsInOneTransaction`
test; smell 6's cure is exercised by `reviewIsNoLongerServedByTheMonolith`
(asserting a 404 from the monolith) plus the behavior-equivalence suite's
"Review Context Contract" folder run against `examples/02-review-service` —
all in/alongside
`src/test/java/dev/patterncatalyst/monolith/smoke/SixContextsSmokeTest.java`.
