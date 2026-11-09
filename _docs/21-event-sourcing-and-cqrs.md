---
title: "Event Sourcing & CQRS"
order: 21
part: "Data Across the Seam"
description: "CQRS splits a write model from event-fed read models; event sourcing makes the log the record and is kept light and optional here. The costs of each."
---

## Learning objectives

By the end of this chapter you can:

- Define CQRS and say when a separate read model pays off.
- Identify `examples/03-notification-service` as a running CQRS read model: its own schema, no foreign key into the monolith, populated by idempotently folding `order.placed` events.
- Distinguish a CQRS read model (Kleppmann's materialized view) from event sourcing, where the log is the system of record and state is computed by replay.
- Name the ongoing costs of event sourcing: event schema evolution (upcasting), snapshots to bound replay, and tooling and team familiarity.
- Choose CQRS-lite (event-fed read models) and explain why each read model owns its storage.
- Verify a read model is current within a stated window using a bounded-wait poll plus a negative check.
- Explain rebuild-from-events as a real but bounded property: valid within the broker's retention window, with the write side the durable source of truth.

## Why this matters

An atomic outbox write, a polling relay, a Kafka topic and an idempotent consumer answer "how do I get a fact out of one service and onto a log reliably." The next question sits on the other side of the log: once the fact is out there, what do you do with it, and when is a second model built around reacting to facts the right call rather than a self-inflicted wound? CQRS and event sourcing both answer it, in two different and often-confused ways. The fastest way in is the codebase itself: `examples/03-notification-service` is already a CQRS read model, built that way without anyone using the term.

The code is in `examples/00-monolith/` and `examples/03-notification-service/`. Nothing new is built here; the job is to name what that code already does. The monolith's `OrderService` with its outbox write is at the `stage/02-notification-extracted` tag; on the current tree the extracted order service (`examples/07-order-service`) is the write side and feeds the same consumer. The service's `README.md` covers how to run it.

## Pattern: CQRS

Command Query Responsibility Segregation, Greg Young's name for the pattern, says that the model you use to change data and the model you use to read it need not share a shape, storage, scale or consistency schedule. A CRUD service collapses both into one table, entity and repository, serving an `INSERT` that enforces an invariant and a `SELECT` that renders a dashboard. That is free when the two want the same shape, which is most of the time, and it is why `OrderService` in the monolith and `review-service` after its extraction are plain CRUD and correct to be.

CQRS pays for its complexity when the assumption breaks. The write side enforces an invariant over a small, normalized cluster of fields (can this customer place this order, is there enough stock). The read side answers a different question, shaped differently, at a different scale: every order a customer has placed, with shipping status and notification history on one screen, at ten times checkout's request rate. Force both through one model and you get a write model polluted with read-optimized denormalization, or a read path doing expensive joins across tables never designed for it. CQRS keeps the write model as normalized and invariant-focused as it should be and builds **read models**, purpose-shaped, independently stored and scaled views, fed by published events.

| | Benefit | Cost |
|---|---|---|
| Coupling | Writer and reader are loosely coupled | Two models and a pipeline between them |
| Scaling | Notification reads never share a connection pool with checkout writes | Distributed data management a single-table service never faces |
| Schema | Each read model fits its own query | The read model is never instantaneously current |

Feeding the read model from Kafka instead of a batch ETL job doesn't remove the trade-off. CQRS is the decision to split the models; event-driven plumbing is one way to keep them in sync.

## `notification-service` is a CQRS read model

Read `NotificationService#recordOrderPlaced` with "is this CQRS" as the question. The write side, `OrderService#placeOrder` in the monolith, is one model: an `Order` aggregate on relational rows whose job is to decide whether a checkout is valid and enforce it transactionally. `examples/03-notification-service` is a second, independently stored and scaled model: its own Postgres schema (`notification`, not the monolith's `public`), its own Flyway history, and a `Notification` entity with plain `Long` columns instead of the `@ManyToOne` relations a shared schema would invite. It is built for one query shape, "what has this customer been told, and when." It enforces none of `Order`'s invariants, can't reject a checkout, and knows about stock, price or payment only what the `order.placed` payload carried.

```java
// examples/03-notification-service/.../Notification.java
@Entity
@Table(name = "notifications", schema = "notification")
public class Notification {
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_id")
    private Long orderId;
    // channel, message, sentAt: shaped for "what did we tell this
    // customer," nothing else. No FK into the monolith's orders table;
    // populated once, from the event payload, not looked up live.
}
```

What it isn't matters just as much. The service doesn't replay `order.placed` from the beginning to compute state on each request, and it isn't the authoritative record that an order was placed: the monolith's `orders` table is. `recordOrderPlaced` is a single idempotent check-then-insert reaction to one event, producing a row that represents a fact the write side already decided. That is a **materialized view**, Kleppmann's term in *Designing Data-Intensive Applications*: a derived answer kept current by folding in new facts, rebuildable from the source facts, never the source of truth. It is a CQRS read model, not an event-sourced aggregate.

{% include excalidraw.html file="cqrs-write-read-split" alt="OrderService.placeOrder's Order aggregate as the write model, publishing order.placed through the outbox; notification-service's Notification entity as the CQRS read model it feeds; a dashed, not-yet-built box marks the GraphQL gateway read model." caption="Figure 21.1: CQRS: one write model, one or more read models, grounded in notification-service." %}

## Pattern: Event Sourcing

Event sourcing takes a stronger position, and the two are conflated because they are so often deployed together. CQRS says to separate the read model from the write model. Event sourcing is about the write model: don't store current state as mutable rows; store the append-only sequence of events that produced it and treat that sequence as the only system of record. Current state is computed by replaying an aggregate's events in order, from the start or from the last snapshot, which exists only to bound replay. An event-sourced `Order` would have no `orders` row with a status column updated in place. It would have a stream (`OrderPlaced`, `PaymentCaptured`, `ShipmentDispatched`, `OrderConfirmed`, the timeline an event-storming wall draws), and its status would be the fold of that stream:

```java
// Illustrative only: no such class exists in this codebase, by design.
// An event-sourced Order's "current state" is a fold over its own history,
// not a stored field.
OrderState current = eventStore.loadEvents(orderId).stream()
        .reduce(OrderState.empty(), OrderState::apply, (a, b) -> b);
```

Kleppmann's framing of derived data follows: a log is a totally ordered, append-only sequence of facts, and every materialized view, index, cache and read-optimized table is a deterministic function of it. CDC and the outbox both manufacture a log out of a system not designed around one. Event sourcing runs the other direction: design around the log first and derive the queryable table from it. Kleppmann calls this turning the database inside out.

That is why the two are paired. A raw event stream is a poor thing to query: "all orders over $500 placed by this customer last month" means refolding the relevant history on every question, so an event-sourced write side almost always needs a CQRS read model projected off it. The reverse doesn't hold. CQRS doesn't require event sourcing. A read model can be fed by events while the write side stays a conventional, mutable, relationally enforced aggregate, which is what `OrderService` and `notification-service` do today.

## Why CQRS-lite and not event sourcing

Event sourcing is easy to underestimate from a diagram and felt only after you've paid for it:

- **Schema evolution of every event type, forever.** `OrderPlaced` v1 is permanent history once the first order is placed. Every later change must deserialize and interpret every version ever emitted, including those written by code that no longer exists. That discipline, upcasting, has no equivalent in a mutable-row system, where an `ALTER TABLE` and a migration retire the old shape.
- **Bounded replay.** An aggregate with years of history and no snapshot strategy loads slower with every event. The failure is invisible on day one.
- **Tooling and familiarity.** Ad hoc SQL against an `orders` table is something every engineer can do. Ad hoc questions against an event store generally need a purpose-built read model, so each new question may mean a new projection.

Reach for it when you have a specific need a mutable table with change events can't satisfy as cleanly: an audit trail that must reconstruct any historical state exactly, or a domain where "what happened and in what order" is the business question, like a ledger or a trading system. This migration has no such need. Every read-model consumer needs "the current state, derived from recent facts": notification needs "has this customer been told," and a GraphQL gateway needs "what does this order look like across five services." None needs "replay this aggregate's history to reconstruct any past instant," the one question event sourcing exists to answer well.

What gets built instead is **CQRS-lite**, event-fed read models. The write side stays a conventional aggregate on conventional rows. The only new pieces are the outbox and its downstream consumers, which let other services react to what the write side decided, asynchronously, without querying its database and without the write side knowing who listens. The notification service's `README.md` states why it owns its schema: a read model sharing the upstream aggregate's table would have two writers racing on the same rows with no protocol to reconcile them. Owning the projection's storage is what makes "lite" safe rather than merely convenient.

{% include excalidraw.html file="cqrs-lite-vs-event-sourcing" alt="Side by side: CQRS-lite, where the Order aggregate stays mutable rows and notification-service folds one published fact into a row, versus full event sourcing, drawn as illustrative only, where an append-only log is the system of record and current state is computed by replay." caption="Figure 21.2: CQRS-lite against full event sourcing, shown for contrast only." %}

## Eventual consistency and rebuild-from-events

A read model is never instantaneously current. The order is committed in the monolith's database the instant `placeOrder` returns; the relay has up to its poll interval before it publishes; the consumer adds processing latency. Closing that window would give the read model synchronous knowledge of every write, the coupling CQRS exists to avoid. So bound it, state it and test it. The technique is a bounded-wait poll (retry on a miss up to a stated budget) plus a negative check (kill the consumer and confirm the assertion goes red rather than timing out softly). It generalizes to any read model, because "the write eventually shows up in the read model" is a different claim from "the write shows up," and a check that can't tell them apart either flakes under load or passes for the wrong reason.

**Rebuild-from-events** is a real strength with a limit. The table is derived data, so it can be dropped and rebuilt: reset the consumer group's offset to the earliest retained record and `OrderPlacedConsumer` idempotently re-derives the same rows. The same idempotency that makes redelivery safe works for you. That makes the read model's persistence disposable rather than precious. But it holds only as far as the log retains history. Kafka's retention window is finite and configured, unlike a dedicated event store. Replay rebuilds notification cleanly only for orders inside the window; beyond it the topic no longer has the fact, and the system survives because the write side (the monolith's `orders` table, later the order service's rows) stays authoritative and durable regardless of what Kafka retains. An event-sourced system has no such fallback, because for it the log is the only copy.

## Forward: the gateway read side

The Order extraction separates a command side (an `Order` aggregate enforcing the invariants it enforces today, extracted but not re-architected around events) from a query side: a GraphQL gateway aggregating read models projected from order, inventory, shipping and notification into one queryable graph. A client asking what its order looks like now is served from purpose-built, independently scaled projections instead of forcing the order service to reach synchronously into four others. That is the CQRS argument scaled from one read model to several, with the same feeding pipeline, schema ownership and bounded-wait proof. What it gives up is isolation: dirty reads, lost updates and non-repeatable reads, which ACID gave a single `@Transactional` and an eventually consistent read model can't.

> **Decision**
>
> Split a read model from the write model when commands and queries need different shapes, scaling or consistency, and give each read model its own storage fed by idempotent event handling. Adopt event sourcing only for a named need to reconstruct any historical state exactly; otherwise keep the write side conventional.

> **🤖 Explore with your agent**
>
> "Review `examples/03-notification-service` as a CQRS read model: list every place it reads or writes the monolith's `public` schema, and confirm `Notification` has no foreign key into `orders`. Then add an `order.cancelled` consumer that is idempotent on `orderId` and writes to the `notification` schema only."
>
> Check: `./mvnw test` in `examples/03-notification-service` passes, and `grep -rn "public\." examples/03-notification-service/src/main` returns only the two comments (in `application.properties` and `V1__create_notifications_table.sql`) stating the service never touches `public.notifications`.

## Build, run, observe

No new example. The notification demo runs the write side (the order service and its outbox), the read model on `:8083` and the bounded-wait check through the edge router on `:8888`:

```bash
demos/demo-notification-cutover.sh
```

- Against the asynchronous read model, the bounded-wait poll in the Notification Context Contract retries: several attempts return `200` with no matching notification before the match appears, where the synchronous monolith answered on the first GET (`examples/01-strangler-proxy/CUTOVER.md`).
- Stop the notification service and re-run the Notification folder: it fails with a `500` and a JSON parse error, not a soft timeout. Restart it and the backlog drains and the folder passes again.

## For deeper coverage

- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the materialized-view and "turning the database inside out" framing behind the CQRS versus event-sourcing distinction; see the [Reading List](/bibliography/).
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the CQRS pattern catalog entry that an event-fed read model is a lighter version of.
- Martin Fowler, [*CQRS*](https://martinfowler.com/bliki/CQRS.html): the short, canonical statement of the pattern's intent and its most common pitfall, overusing it where a single model would do.
- Sam Newman, *Building Microservices* (2nd ed., O'Reilly, 2021): service boundaries and independent scaling, the argument for giving a read model its own schema and deploy cycle.
- Cloud Native Design Patterns, [Event-Driven](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/05-event-driven.md): the companion book's treatment of the event-driven plumbing that feeds a CQRS read model.
