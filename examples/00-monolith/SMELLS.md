# The Six Deliberate Smells

This monolith is the "before" picture for the whole book (`_plans/build-plan.md`
section D). Six smells are planted **on purpose**, each tagged in-code with a
`SMELL[ch.NN]` comment (grep for `SMELL\[ch\.` to find every occurrence) and
mapped below to the chapter(s) that later cure it.

| # | Smell | Where it's planted | Curing chapter(s) |
|---|---|---|---|
| 1 | **Shared schema / cross-context joins** — one Postgres schema with direct JPA `@ManyToOne` FKs crossing bounded-context boundaries (`order_items` → `inventory_items`, `payments`/`shipments`/`notifications` → `orders`, `reviews` → `customers` + `inventory_items`, everything → `customers`). | `common/Customer.java`, `inventory/InventoryItem.java`, `order/Order.java`, `order/OrderItem.java`, `payment/Payment.java`, `shipping/Shipment.java`, `notification/Notification.java`, `review/Review.java`, `db/migration/V1__init_schema.sql` | ch.18 (shared data → owned data), ch.19 (CDC backfill) |
| 2 | **God `OrderService`** — one service is the single orchestration point for checkout and reaches directly into inventory, payment, shipping, and notification's services/repositories/entities. | `order/OrderService.java` | ch.26 (order extraction, hardest/last — every other extraction first removes one of this service's direct dependencies); motivates sagas (ch.23/24) |
| 3 | **One in-process ACID transaction spanning contexts** — `OrderService#placeOrder` wraps inventory decrement + order persistence + payment + shipment + notification in a single `@Transactional`, so any failure anywhere rolls back everything, everywhere. | `order/OrderService.java` (`placeOrder`), `common/exception/PaymentDeclinedException.java`, `common/exception/InsufficientStockException.java` | ch.23 (choreographed saga), ch.24 (orchestrated saga), ch.22 (ACID → ACD) |
| 4 | **Synchronous notification inside the checkout transaction** — the order-confirmation notification is sent via a direct in-process call, inside the same transaction as the rest of checkout, coupling checkout latency and failure domain to an orthogonal concern. | `notification/NotificationService.java` (`sendOrderConfirmation`), `common/Topics.java` (the reserved, currently-unused event names this smell will be replaced by) | ch.17 (notification extraction — transactional outbox + async event-driven consumer) |
| 5 | **No anti-corruption layer / leaky domain model** — `OrderService` receives and holds a raw `InventoryItem` JPA entity from `InventoryService`, not a DTO or contract; there is no translation layer at the seam. | `inventory/InventoryService.java` (`findBySkuOrThrow`), `order/OrderService.java` | ch.16 (content-based routing & the ACL — Camel message translator / content enricher at the seam) |
| 6 | **Review tangled into shared security but genuinely independent** — Review has no runtime dependency on order/inventory/payment/shipping/notification (REST-only, no synchronous collaborator), yet its one authenticated endpoint is governed by the monolith's single global `SecurityFilterChain`, alongside every other context's rules. | `security/SecurityConfig.java`, `review/ReviewController.java`, `review/Review.java`, `review/ReviewService.java` | ch.15 (Review extraction — the walking skeleton; Review gets its own standalone OIDC-protected security config on Quarkus with nothing left to detangle) |

## Finding them

```sh
grep -rn 'SMELL\[ch\.' examples/00-monolith/src
```

Every smell above appears at least once in that output. Smells 1–5 are
exercised end-to-end by the `checkoutFlowSpansAllFiveNonReviewContextsInOneTransaction`
test; smell 6 is exercised by `reviewContextRespondsAndEnforcesSharedSecurity` —
both in
`src/test/java/dev/patterncatalyst/monolith/smoke/SixContextsSmokeTest.java`.
