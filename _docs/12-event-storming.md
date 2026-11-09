---
title: "Event Storming the Monolith"
order: 12
part: "Finding the Seams"
description: "Discovering seams from behavior instead of schema, and the decomposition backlog a process-level storm of checkout produces."
---

## Learning objectives

By the end of this chapter you can:

- Explain why event storming finds seams from a timeline of past-tense domain events, and what schema hides that behavior reveals.
- Apply the color grammar (domain event, command, actor, aggregate, policy, read model, external system) in the order a session introduces it.
- Choose between big-picture, process-level and design-level altitudes.
- Storm the checkout flow into its six-event timeline: `OrderPlaced`, `StockReserved`, `PaymentCaptured`/`PaymentDeclined`, `ShipmentDispatched`, `OrderConfirmed`, `NotificationSent`.
- Spot the gap between checkout's business order ("place, then reserve") and its code order ("reserve, then place").
- Map each event boundary to the extraction that cuts it, and explain why a wall gives *where* but not *in what order*.

## Why this matters

Subdomains and bounded contexts name the boundaries, but they don't say how to find them in a system that never wrote them down. Event storming is a facilitation technique that discovers bounded contexts and aggregates by narrating what a system does, as facts in the order they occur, instead of reading its schema, class diagram or packages. Applied to `examples/00-monolith/`, it turns one paragraph ("a customer places an order, we check stock, charge the card, ship the box, and tell them") into a timeline whose gaps are the seams to cut.

Every event name below is either a reserved constant in `common/Topics.java` or a natural companion to that set. Every call quoted is from `order/OrderService.java#placeOrder`, the method that carries three of the monolith's deliberate smells. The extractions removed the context packages from the current `examples/00-monolith` tree; the monolith as designed, with `common/`, `order/` and the other contexts, is on the `reference/monolith-before` branch (`git checkout reference/monolith-before`).

{% include excalidraw.html file="event-storm-checkout" alt="Two readings of the checkout flow. Top: today's OrderService#placeOrder as one synchronous call chain inside a single @Transactional across inventory, payment, shipping and notification. Bottom: the same process with the event-storming grammar: an actor and command producing an Order aggregate, a timeline of domain events separated by policies, an external payment gateway, and an Order Status read model fed by the events." caption="Figure 12.1: The checkout flow, read two ways: one call chain today, a timeline of domain events once it's event-stormed" %}

## Why behavior beats schema

A monolith carries a second, unofficial map of itself in its tables, foreign keys and service dependency graph. It's concrete, so you can query and diagram it, and it's the wrong map for deciding where to cut. `V1__init_schema.sql` says `order_items` has a foreign key into `inventory_items`. It doesn't say why, whether the business cares or an engineer found the cheapest way to say "these rows are related," or whether it's one of the monolith's deliberate smells rather than a real invariant. Schema records decisions without their reasons, and reasons separate a critical boundary from an accidental one.

Event storming, introduced by Alberto Brandolini, asks instead what happened, in what order, and who or what caused it. A session starts with a long roll of paper (or, for an agent, a shared document) and orange stickies. The first-pass rule: write down a **domain event**, a past-tense fact someone in the business would recognize, each time one occurs. `OrderPlaced`. `StockReserved`. `PaymentDeclined`. Not `createOrder()` and not `UPDATE orders SET status = ...`. Naming what happened needs no knowledge of the code, so a room of domain experts and engineers can timeline checkout in twenty minutes. You need to have used, sold or supported the system, not read `OrderService.java`.

That makes storming an outside-in method. A bounded-context boundary is a place where vocabulary changes: "reserve" stops meaning "decrement a quantity column" and starts meaning "authorize a charge," or "confirm" stops describing an order's status and starts describing a message reaching an inbox. In the timeline each shift is where one sticky hands off to a different actor, external system or team. The schema hides those shifts: a shared Postgres instance makes every context's tables look like equal neighbors, which is the shared-schema smell. Storming doesn't care where a fact is persisted.

It also fits the agentic lifecycle. The Map phase must establish what the system does, in what order and with what failure modes, before an agent generates extraction code. A workshop with stickies and an agent narrating a known code path onto a structured timeline produce the same artifact, a sequence of named facts, and Plan consumes it. No new tooling is needed.

## The grammar of a session

Each color answers a question the previous pass left open.

- **Domain events (orange)** come first. The first pass is chaotic: every fact, out of order, duplicated, contradicted. Lining them up into a timeline is the first modeling act, and ordering disagreements expose assumptions a single ACID transaction has been enforcing silently.
- **Commands (blue)**, one per event, are the imperative that triggered the fact. `PlaceOrder` triggers `OrderPlaced`. `CapturePayment` triggers `PaymentCaptured` or `PaymentDeclined`, a branch invisible in the orange-only pass.
- **Actors** (a small yellow figure left of the command) issue commands. A customer issues `PlaceOrder`. A scheduled job, a payment-gateway webhook or a policy can issue a command just as validly, and naming the actor tells you which seam needs an authenticated human-facing API and which needs a service account on an internal topic.
- **Aggregates (large yellow)** come from grouping: which cluster of data had to exist, enforce an invariant and decide whether the command was valid. `Order` decides whether `PlaceOrder` is well-formed (does the customer exist, are the items non-empty). `InventoryItem` decides whether `ReserveStock` can succeed (is there enough on hand). A boundary found this way is a behavioral claim, independently checkable against the tactical aggregate boundaries.
- **Policies (lilac)** are "whenever X, then Y" rules that chain events to commands with no human in the loop: whenever `OrderPlaced`, issue `ReserveStock`; whenever `StockReserved`, issue `CapturePayment`. A policy is the shape a message consumer takes once the system is distributed, and the shape of one step in a choreographed saga.
- **Read models (green)** are views projected forward from events. "What's the status of order #4412" needs an `Order Status` read model fed by several events, not a live join across five tables.
- **External systems (pink)** sit outside the team's control: a card network, a carrier's dispatch API. Naming them forces the question "what if this is slow, down or ambiguous," instead of assuming every collaborator is as reliable as an in-process call, which is what `OrderService#placeOrder` assumes of all four of its collaborators.

{% include excalidraw.html file="event-storming-legend" alt="A seven-row legend of event-storming note types in the order a session introduces them: domain event (orange, a past-tense fact), command (blue, the imperative that triggered it), actor (small yellow figure), aggregate (large yellow, the data cluster enforcing an invariant), policy (lilac, whenever X then issue Y), read model (green, a view projected from events), and external system (pink, outside the team's control)." caption="Figure 12.2: The event-storming note-type legend: the wall's color grammar" %}

A session runs at three altitudes, and conflating them produces a wall too shallow to act on or too narrow to show the seams.

| Altitude | Scope | Output |
|---|---|---|
| Big-picture | A whole business, orange stickies only | Candidate bounded contexts; places where one word means two things |
| Process-level | One process end to end with the full grammar | The decomposition backlog |
| Design-level | Inside one aggregate | Its commands, validation rules and events, handed to tactical DDD |

## Storming checkout

A **Customer** issues **Place Order** with a customer id, shipping address and line items, the payload `OrderCreate` carries. The **Order** aggregate accepts a well-formed command and the first event goes up: **OrderPlaced**. It already exists, unused, as a constant:

```java
// common/Topics.java
public static final String ORDER_PLACED = "order.placed";
```

The class javadoc reserves the names "for the future event-driven extractions," aligned with the sibling services' Kafka topics, with no messaging infrastructure wired up. A storm wall and a reserved-topic file are the same artifact at two levels of formality.

Next is the policy *whenever OrderPlaced, then reserve stock*, and here storming catches what a code read can miss. In `placeOrder` the reserve loop runs before the order is persisted:

```java
// order/OrderService.java#placeOrder
for (OrderCreate.Line line : command.items()) {
    InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
    inventoryService.reserve(line.sku(), line.quantity()); // throws InsufficientStockException, no writes yet
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
order = orderRepository.save(order);
```

The code order is "reserve, then place." The business order is "place, then reserve": the customer's intent is the triggering fact and reservation happens because of it. `InsufficientStockException`'s javadoc half-admits this by saying it is raised "BEFORE any writes happen, so no rollback is needed for this path." Synchronous validation is standing in for an asynchronous reaction to a fact that already happened. Call-stack order is an implementation detail, which is why a code read papers over the gap. The event the wall needs next, **StockReserved**, isn't named anywhere today, because "stock got reserved" is indistinguishable from "a method returned without throwing."

*Whenever StockReserved, then capture payment*, issued to a pink **external system**, the card network. The command yields one of two events, and `PaymentDeclinedException`'s javadoc names the mechanism the migration retires:

```java
// common/exception/PaymentDeclinedException.java (javadoc)
// Because OrderService#placeOrder wraps inventory decrement + order
// persistence + payment + shipment + notification in ONE @Transactional
// (SMELL[ch.22]), raising this mid-method rolls back everything already
// written in this request, including the inventory decrement.
```

**PaymentCaptured** and **PaymentDeclined** are two orange stickies off one blue command. At this point the facilitator asks "when payment is declined, what undoes the stock reservation?" and today's answer is "nothing has to; Postgres's rollback does it." That is the single-transaction smell stated by the wall itself. The answer stops being true the moment either side of the seam gets its own database, which is the premise of every remaining extraction.

Captured payment triggers *dispatch shipment*, performed by `ShippingService.dispatch`. Its doc comment flags the call as synchronous and in-transaction, and it produces **ShipmentDispatched**, the third name reserved in `Topics.java` (`SHIPMENT_DISPATCHED = "shipment.dispatched"`) after `ORDER_PLACED` and `PAYMENT_CAPTURED`. The last policy, *whenever ShipmentDispatched, then notify customer*, meets the synchronous-notification smell. `NotificationService`'s javadoc states the cost: a send inside checkout's transaction couples checkout latency to however long sending takes, and an exception there rolls back the order over something the confirmed order doesn't depend on.

This policy yields two events the code conflates in one call. **OrderConfirmed** is the business fact that checkout succeeded, independent of whether the customer has been told. **NotificationSent** is the narrower fact that a message left the system. Today's code calls `order.confirm()` right after `paymentService.charge(...)` and before `shippingService.dispatch(...)`. The wall places `OrderConfirmed` at the end of the timeline as a business-order reading, not a transcription of the call stack. Splitting the two lets checkout commit `OrderConfirmed` and move on while `NotificationSent` is produced by a separate asynchronous consumer on its own schedule. The **Order Status** read model, the wall's one green sticky, folds `OrderPlaced`, `PaymentCaptured`, `ShipmentDispatched` and `OrderConfirmed` forward, so a "where's my order" screen doesn't join five contexts' live tables on every load.

## From the wall to the backlog

Read left to right, a finished process-level wall is the decomposition backlog. Everywhere a policy's reaction crosses into another context as a plain Java call, the boundary is a synchronous call today and a published event once cut.

| Boundary | Seam | Mechanism |
|---|---|---|
| `OrderPlaced` to `StockReserved` | Inventory | CDC backfill and a decomposed database |
| `StockReserved` to `PaymentCaptured`/`PaymentDeclined` | Payment | Choreographed saga: the lilac policy becomes, almost unchanged, a Kafka consumer |
| `PaymentCaptured` to `ShipmentDispatched` | Shipping | Orchestrated saga with the Camel Saga EIP: one coordinating definition instead of each service discovering the next event |
| `ShipmentDispatched` to `OrderConfirmed`/`NotificationSent` | Notification (cut first) | Transactional outbox feeding an asynchronous consumer |

{% include excalidraw.html file="storm-wall-to-backlog" alt="A four-row table mapping each event boundary the storm found to its seam and mechanism: OrderPlaced to StockReserved is inventory, CDC backfill plus a decomposed database; StockReserved to PaymentCaptured or PaymentDeclined is payment, a choreographed saga; PaymentCaptured to ShipmentDispatched is shipping, an orchestrated saga using the Camel Saga EIP; ShipmentDispatched to OrderConfirmed or NotificationSent is notification, cut first, a transactional outbox. A footnote notes OrderService is cut last, and that an anti-corruption layer is the precondition at every row." caption="Figure 12.3: From the storm wall to the extraction backlog: each boundary and its mechanism" %}

An anti-corruption layer has to exist at each boundary before the event can carry contract-shaped data instead of a raw JPA entity. The wall silently assumes one every time it draws an arrow between two stickies.

The wall says where a boundary exists, not which to cut first. Given five seams, which has the least coupling pulling against it, which is most urgent, and which has a blast radius that mistakes won't escape are questions for coupling analysis. Storming answers "where," coupling answers "in what sequence," and the decomposition order is the product of both. Each cut is then gated: the behavior-equivalence suite runs against both the monolith and the extracted service before the old code is decommissioned.

The wall also explains why order is extracted last. `OrderService` isn't one box on it. It's the aggregate that issues every policy's triggering command in this flow: reserve, capture, dispatch and notify, all direct, synchronous and in one transaction. It can't be extracted until each of those positions fires an event instead of a call. The wall enumerates, in order, the edges to cut before the hardest extraction becomes possible.

> **Decision**
>
> Storm the process-level behavior before choosing seams. Every place a policy crosses a context as a direct call is a seam; every place the code order differs from the business order is a hidden assumption to resolve before you cut.

> **🤖 Explore with your agent**
>
> "Check out the reference/monolith-before branch, read examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/order/OrderService.java#placeOrder and produce a process-level event-storm timeline for checkout: domain events in past tense, the command and actor for each, the policies between them, and the external systems. Mark every place the code order differs from the business order, and map each event boundary to the seam it cuts. Don't edit any file."
>
> Check: the timeline contains `OrderPlaced`, `StockReserved`, `PaymentCaptured`/`PaymentDeclined`, `ShipmentDispatched`, `OrderConfirmed` and `NotificationSent`, and flags that `order.confirm()` is called before shipping is dispatched.

## For deeper coverage

- Alberto Brandolini, [EventStorming](https://www.eventstorming.com/): the canonical reference for the facilitation technique and color grammar.
- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): domain events and aggregates, the tactical vocabulary the wall produces independently; see the [Reading List](/bibliography/).
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the message-based choreography the "whenever X then issue Y" policies anticipate.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the saga patterns the policies become once payment's and shipping's seams are cut.
- Cloud Native Design Patterns, [Event-Driven](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/05-event-driven.md): the target shape the storm's policies move toward.
