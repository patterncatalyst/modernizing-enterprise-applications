---
title: "ACID → ACD: Living Without Isolation"
order: 22
part: "Data Across the Seam"
description: "Dirty reads, lost updates and non-repeatable reads come back once services commit independently; what survives the cut, what a saga rebuilds and how to bound the rest."
---

## Learning objectives

By the end of this chapter you can:

- State SMELL #3: one in-process `@Transactional` in `OrderService#placeOrder` spans order, inventory, payment and shipping, and works only because Postgres rolls back automatically.
- Tell a mocked unit test, which shows orchestration order, from a Newman scenario, which shows a real Postgres rollback.
- Reject two-phase commit as the fix for cross-service atomicity: a blocking coordinator, locks held across network round-trips and centralized operational ownership.
- Define ACD (atomic locally, consistent eventually, durable, isolation dropped) and BASE, and say what survives decomposition and what you rebuild by hand.
- Explain a compensating action as a new forward-moving transaction that nothing fires automatically.
- Connect CAP and PACELC to compensation, and define correctness under ACD as bounded convergence.
- Name the isolation anomalies that return once services commit independently, and say why inventory's reservation stays synchronous while payment and shipping get a saga.

## Why this matters

The monolith's checkout answers "did this commit, all of it or none of it" with one word, yes, guaranteed by one `@Transactional` boundary and one database's write-ahead log. Each extraction since Notification has removed a context from that guarantee's reach. A read model that lags its write model by a bounded window is the first visible cost. The rest of the bill is what the shared transaction provided without any code, why it can't cross a process boundary, and what replaces it. Nothing new runs here. The evidence comes from the monolith and its recorded Newman scenarios. Later extractions removed the monolith's checkout from the current tree; `OrderService` as quoted below, after the outbox replaced the notification call, is at the `stage/02-notification-extracted` tag (`git checkout stage/02-notification-extracted`), and the original five-context version is on the `reference/monolith-before` branch.

## SMELL #3: one transaction, four contexts, one rollback

`examples/00-monolith/SMELLS.md` catalogues the smell in one line: **one in-process ACID transaction spanning contexts**. `OrderService#placeOrder` wraps the inventory decrement, order persistence, payment capture and shipment dispatch in a single `@Transactional`, so a failure anywhere rolls back everything. Notification left the boundary when its synchronous call became an outbox write. The outbox row is order-owned, so it stays inside the transaction. What remains is four contexts committing or rolling back as one unit.

```java
// order/OrderService.java#placeOrder
/**
 * SMELL[ch.22]: one in-process ACID {@code @Transactional} spans FOUR bounded
 * contexts: order, inventory, payment, shipping (plus this context's own
 * outbox table write...). It "works" today because Postgres gives us atomic
 * rollback for free across all of them. The moment any one of these becomes
 * its own service with its own database, this rollback-everything behavior
 * disappears and has to be rebuilt explicitly as a saga with compensating
 * actions (choreographed for payment, orchestrated for shipping): that is the
 * ACID -> ACD story.
 */
@Transactional
public OrderDto placeOrder(OrderCreate command) {
```

The body is four calls in one stack frame: `inventoryService.reserve(...)` decrements stock before any of the order is written, `orderRepository.save(order)` persists it, `paymentService.charge(...)` captures payment, and `shippingService.dispatch(...)` books a shipment against an order no other transaction can see yet. Nothing in the code expresses the all-or-nothing promise. The method boundary and one Postgres connection supply it. The checkout author never wrote failure-recovery code, because the database wrote it for every combination of partial failure.

## The free rollback, proven at two tiers

`PaymentDeclinedException` is thrown after the inventory reservation and the order row are written, so a decline is exactly the case a rollback exists to undo. Its sibling `InsufficientStockException` is thrown before any write, so that path needs no rollback.

Two kinds of evidence cover the decline path, and the gap between them is the argument:

| Tier | What it can show | What it can't |
|---|---|---|
| Unit test with every collaborator mocked | `reserve(...)` ran before `charge(...)` threw, and `dispatch(...)` was never reached | A rollback: a mock has no database to undo anything |
| Newman scenario "Payment-Declined" through HTTP | Stock read before the decline equals stock read after it | Nothing about call order |

The unit test's own comment states its limit: it proves the orchestration order, not the rollback. The Newman scenario reads stock, sends a checkout with `paymentMethod: "CARD-DECLINE"`, expects `402` with `error=PAYMENT_DECLINED`, and reads stock again.

```javascript
// mea.postman_collection.json, "Scenario 3: Payment-Declined"
// 3a. capture stock before the declined checkout
pm.collectionVariables.set('widgetStockBeforeDecline', json.quantityOnHand);

// 3b. checkout with paymentMethod: "CARD-DECLINE" -> expect 402
pm.test('Declined payment returns 402 Payment Required', function () {
    pm.response.to.have.status(402);
});
pm.test('Documented error contract: error=PAYMENT_DECLINED', function () {
    pm.expect(json.error).to.eql('PAYMENT_DECLINED');
});

// 3c. re-read stock after the decline
var before = Number(pm.collectionVariables.get('widgetStockBeforeDecline'));
pm.test('Quantity on hand is unchanged: the reservation rolled back with the ' +
    'declined payment (same ACID transaction, SMELL[ch.22])', function () {
    pm.expect(json.quantityOnHand).to.eql(before);
});
```

The stock is unchanged because Postgres undid the `UPDATE` that `reserve(...)` issued the moment `charge(...)` threw. Only this black-box check is evidence of a real rollback. The mocked one is evidence that the code would ask for one.

## Why the free rollback can't cross a process boundary

The textbook mechanism for atomicity across databases is **two-phase commit**. In the prepare phase, a coordinator asks each participant (order, inventory, payment and shipping databases) to ready its local transaction and vote. A yes voter holds its locks and prepared state until told otherwise. In the commit phase, the coordinator tells everyone to commit only if every vote was yes, and to abort otherwise.

Kleppmann's treatment in *Designing Data-Intensive Applications* gives the failure modes that rule it out here:

- **The coordinator is a single point of failure.** If it crashes after collecting votes and before broadcasting the decision, every yes voter is stuck "in doubt", holding locks, until the coordinator recovers. That can take unbounded time.
- **It blocks by construction.** Local locks are held across two network round-trips for as long as the slowest participant takes, which is the latency and availability cost decomposition is meant to shed.
- **It recentralizes ownership.** Every participant must speak the same protocol and trust the same coordinator. A payment service another team owns and deploys on its own schedule can't be asked to hold database locks for an order it has never heard of, waiting on a coordinator it doesn't operate.

The monolith's rollback worked because one process held one connection to one database. Two-phase commit recreates that illusion where it was never physically true, and the held locks, fragile coordinator and lower availability cost more than designing for its absence.

## ACD: what survives the cut

**ACD** is atomic (inside each service's own database), consistent (eventually, across services, once every step has completed or been compensated), durable (a committed local write survives a crash), with the **I**, isolation across the whole operation, dropped. **BASE** (basically available, soft state, eventually consistent) describes the same trade-off from the availability side. ACD names the property by contrast with the acronym the monolith already taught.

What survives: each extracted service keeps full local ACID. Inventory, payment and shipping each wrap their own write in a real `@Transactional` (or its Quarkus/Panache equivalent) against their own schema. A service that can't commit its own write atomically is broken, decomposition or not.

What doesn't survive: atomicity across services. The order write, inventory decrement, payment capture and shipment dispatch become four commits on four connections to four databases, and each can fail independently of the others.

A saga restores the guarantee that when a later step fails, an explicit **compensating action** undoes every step that already succeeded. It isn't a database rollback. It's a new forward-moving transaction, written by the service that owns the data, that produces the opposite business effect. The monolith's `reserve(...)` decrement has an extracted counterpart, a `release` or `cancelReservation` operation the saga calls explicitly when payment fails. Nothing fires it automatically. You write it, name it, and test its failure path as carefully as the happy path, because the one transaction manager that used to do this is no longer in the room.

{% include excalidraw.html file="acid-to-acd" alt="The ACID-to-ACD spine: on top, OrderService#placeOrder's single transaction rolling back every extracted context automatically, with no compensating code written for it; on the bottom, each service keeping full local transactions inside its own database while cross-service atomicity and isolation are gone, replaced by an explicit compensating action a saga runs instead of a database rollback." caption="Figure 22.1: What ACID gives up at the seam, and what a saga rebuilds" %}

## CAP, PACELC and what correctness means now

CAP says a distributed data system facing a network partition must choose between consistency and availability. Four databases reachable only over a network that can delay, time out and partition are the system it describes. PACELC covers the common case where nothing is broken: **E**lse, the system still trades **L**atency against **C**onsistency on every request, because keeping every replica of a payment status current means blocking the caller until all agree.

A saga's compensation model answers that every-day question by choosing low latency and local availability on each step and accepting a bounded window where overall state hasn't converged. Correctness at this scale means the system converges to one consistent state within a bounded window and never settles on a state nobody intended. An order that briefly shows "placed" before payment is captured is normal under ACD. An order that settles permanently as "placed" after a decline, with no compensation ever running, is the failure to guard against.

## The isolation anomalies the monolith never had to name

Inside one transaction boundary the checkout never met the classic anomalies. They return at the seam:

- **Dirty read.** Another reader, such as a reporting view, an order-status page or the GraphQL gateway's read model, can observe "payment captured" before the shipping step of the same saga has run or failed. The monolith exposed nothing until the whole transaction committed.
- **Lost update.** Two sagas touching the same inventory row through independent service-owned transactions have no single lock manager coordinating them.
- **Non-repeatable read.** A saga step that reads another service's state before acting on it sees that state change between the read and the dependent write. This is the ordinary case.

These are the named cost of trading one transaction manager for several, not sloppy engineering. The answer is the same each time: bound the window, make staleness visible rather than silent, and verify convergence explicitly. The Notification extraction does this with a bounded-wait poll (up to ten retries at a fixed interval) instead of asserting instant consistency, which is enough there because one event, one idempotent consumer and no compensation can't fail checkout. Payment and shipping face the harder version, several steps that can each fail and each need a compensation, under the same rule: a bounded window is acceptable, an unbounded or silently inconsistent one isn't.

## One rule, four treatments

ACD isn't applied uniformly, because contexts differ in the cost of a late consistency check.

| Context | Treatment | Why |
|---|---|---|
| Notification | Fully asynchronous, no compensation | A confirmation arriving seconds late is invisible, and a late message has nothing to undo |
| Inventory | Stock check and reservation stay synchronous over gRPC; only what follows is asynchronous | Overselling a physical unit to two customers can't be fully repaired: you can refund a card but not produce a second widget |
| Payment | Choreographed saga: `order.placed` triggers payment's attempt, `payment.captured` or `payment.declined` triggers the next reaction, no central coordinator | Tolerates a bounded window and needs compensation when something downstream fails |
| Shipping | Orchestrated saga using the Camel Saga EIP | Same need, opposite control style: a central coordinator sequences steps and invokes the matching compensation on failure |

A captured payment whose shipment fails to dispatch has to be refunded, not merely logged. Putting both saga styles on one codebase makes the choreography-versus-orchestration trade-off concrete before either is declared the winner.

> **Decision**
>
> Keep exactly as much consistency and as much synchrony as the business invariant demands, and no more. If a failure can't be undone after the fact (overselling stock), keep the call synchronous. If it can be compensated (a refund, a release), go asynchronous and write the compensating action as carefully as the happy path. Never reach for two-phase commit across independently owned databases.

> **🤖 Explore with your agent**
>
> "Check out the stage/02-notification-extracted tag. In `examples/00-monolith`, read `SMELLS.md` and `src/main/java/dev/patterncatalyst/monolith/order/OrderService.java#placeOrder`. For each of the four calls inside the `@Transactional` (inventory reserve, order save, payment charge, shipping dispatch), decide whether it needs a synchronous call or can become asynchronous with a compensating action, using this rule: keep it synchronous only if a failure can't be undone afterward. Name the compensating action for each asynchronous one and the isolation anomaly (dirty read, lost update or non-repeatable read) a client could observe while the saga is in flight."
>
> Check: the reply keeps the inventory reservation synchronous, names a refund for a captured payment whose shipment then fails and a release of the stock reservation when a later step fails, and names one anomaly per asynchronous step. Compare it with the Payment-Declined folder in `tooling/newman/mea.postman_collection.json`.

## For deeper coverage

- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): two-phase commit's failure modes and the CAP and PACELC framing; see the [Reading List](/bibliography/).
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the saga pattern and compensating-transaction vocabulary.
- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): what you give up when splitting a shared database, including cross-service transactions.
- Cloud Native Design Patterns, [Appendix D: Sagas](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/17-appendix-d-sagas.md): compensating-action saga design.
