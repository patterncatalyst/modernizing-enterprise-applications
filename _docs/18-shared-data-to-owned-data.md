---
title: "From Shared Data to Owned Data"
order: 18
part: "Data Across the Seam"
description: "A foreign key is a dependency the database enforces silently. Database-per-service trades it for autonomy, snapshots and eventual consistency."
---

## Learning objectives

By the end of this chapter you can:

- Explain why shared-database coupling is harder to detect and break than a code smell: a foreign key is a dependency the database enforces silently, not one application code declares.
- Weigh what database-per-service buys (independent deployability, scaling and technology choice) against what it costs (joins become network calls or event-fed read models, and cross-service referential integrity disappears).
- Replace a live foreign-key join with a point-in-time snapshot, or with an explicit live call when the data must be current, and decide which fits a relationship.
- Treat duplication as a design choice, and name what bounds each copy's staleness.
- Choose between Review's deferred shared schema and Notification's own schema from the first migration using the two-writer argument.
- Describe a CDC backfill as the alternative to a big-bang schema cutover, and say how an equivalence gate must change to prove the new database is the one answering.

## Why this matters

The coupling analysis ranked shared-database coupling as the worst offender in the monolith: common and content coupling at once, invisible at the point of use because the database enforces the dependency and no code declares it. Splitting one database into many has costs the ranking doesn't show, and no code change covers them. `examples/00-monolith/`, `examples/02-review-service/` and `examples/03-notification-service/` hold the evidence, and the CDC backfill that carries the plan out comes with the inventory extraction. The monolith's entity classes quoted below (`OrderItem`, `Customer`) were deleted from the current tree by later extractions; they are on the `reference/monolith-before` branch (`git checkout reference/monolith-before`). `V1__init_schema.sql` is unchanged in the current tree.

## Why shared-database coupling is the hardest to break

Every other smell lives in application code, where an owner can see it and change it in one pull request. The god `OrderService`'s outbound dependencies sit in one constructor. The missing anti-corruption layer is one method returning a raw entity. The shared schema lives in `V1__init_schema.sql`, a migration that ran once, and in nine `REFERENCES` clauses (eight crossing a bounded-context seam) that Postgres enforces for as long as the tables exist:

```sql
-- V1__init_schema.sql
CREATE TABLE orders (
    ...
    customer_id      BIGINT       NOT NULL REFERENCES customers (id),
    ...
);

CREATE TABLE order_items (
    ...
    order_id           BIGINT  NOT NULL REFERENCES orders (id),
    inventory_item_id  BIGINT  NOT NULL REFERENCES inventory_items (id),
    ...
);

CREATE TABLE payments (
    ...
    order_id     BIGINT      NOT NULL REFERENCES orders (id),
    ...
);
```

`payments`, `shipments` and `notifications` each hold a foreign key into `orders`. `order_items` holds one into `inventory_items`. `reviews` holds two, into `customers` and `inventory_items`. `Customer` is the clearest case: its javadoc calls it a "shared-kernel entity. Not one of the monolith's bounded contexts itself", referenced by `order` through JPA and, at the database level only, by `notifications` and `reviews`. Six tables, five bounded contexts, one schema, and no line of Java says "I depend on inventory's table shape".

{% include excalidraw.html file="monolith-shared-schema-er" alt="The shared-schema entity-relationship diagram: eight tables in one PostgreSQL schema, with customers and inventory_items referenced from multiple context-owned tables and nine foreign keys crossing bounded-context ownership lines freely." caption="Figure 18.1: The FK web database-per-service has to break apart: nine foreign keys, one shared schema" %}

A foreign key isn't a negotiated contract. It's a constraint one migration author wrote once, which every future writer to either table must honor whether or not they know it exists. Nothing fails where the coupling is introduced. Later, somewhere else and usually in someone else's code, inventory drops a column it believes nothing uses and a join written two years ago breaks in production.

Kleppmann's *Designing Data-Intensive Applications* puts the price this way: referential integrity is enforced for you, and every writer to the referenced table is implicitly coupled to every reader that relies on the reference holding. Inside one database that price is close to free, because the coupling and its enforcement are the same mechanism. Across two databases the enforcement stays behind in the old schema and the coupling doesn't go away. It is the distributed-monolith trap in data terms: distance goes up, the dependency stays, and nothing checks it at write time.

## Pattern: Database per Service

The destination is one owned database per bounded context, with no table any other context can query. The benefits are the usual ones:

| Benefit | What it removes |
|---|---|
| Independent deployability | A schema migration no longer needs coordinating with every context that queries the same tables. |
| Independent scaling | `inventory_items` under flash-sale reads doesn't compete with `orders` writes on one connection pool. |
| Independent technology | A key-value access pattern isn't forced onto the engine a transactional context needs. |

The costs are bigger than that pitch admits.

**Joins become API calls or replicated read models.** `OrderItem`'s `@ManyToOne` into `InventoryItem` is a single SQL join today, consistent by construction because there is one database. Once inventory owns its database the join has two replacements, both worse in the same way: a network call, which can fail, time out or return an answer already stale; or a local copy of inventory's data, only as fresh as the last event that updated it. Nothing recovers the original single, atomic, always-current read across two contexts. The shared schema had paid for that property up front by coupling every writer to every reader. Splitting the database stops hiding the cost.

**Referential integrity across services is gone, by design.** Once payment owns its database, nothing stops it holding an `order_id` for an order that was deleted, archived or never created. Inside one schema `REFERENCES orders (id)` makes that state unreachable. Across two schemas the guarantee is gone permanently. What replaces it is an eventually consistent promise: the owning context publishes the facts that matter, and every other context either consumes a fact once and keeps its own copy, or calls back and accepts that the answer may be stale on arrival. The system has to tolerate states the database no longer prevents, the same trade the move from ACID to ACD makes for transactions as a whole.

**What it buys is autonomy:** one team changes its schema, deploys and scales its storage without a cross-context migration meeting or shared maintenance window. That justifies the first two costs only when the business needs it: a different load profile, a different release cadence, or two teams owning the code. Microservices aren't the goal, and the point applies more strongly to data, because a split database is far harder to undo than a split deployable. Merging two codebases is an afternoon's work. Merging two schemas that have each accumulated migrations, assumptions and data drift is not.

## From foreign key to snapshot

The core move, repeated per relationship, replaces a live join into another context's table with one of two things:

| Need | Replacement |
|---|---|
| The fact as of the moment that mattered to this context | A point-in-time copy of exactly the fields it needs, fed by an event or a backfill. |
| The fact as of this instant | An explicit call across a published contract. |

A live foreign key quietly picks the first answer for every relationship, whether or not it was right. `OrderItem`'s javadoc already named the destination:

```java
/**
 * SMELL[ch.18]: {@code OrderItem} holds a direct JPA {@code @ManyToOne} FK/join
 * onto {@code inventory.InventoryItem}: an order-context table referencing an
 * inventory-context table in the one shared schema. Once inventory owns its own
 * database, this join is replaced by a denormalized copy of the fields the
 * order context actually needs (sku, name, price-at-time-of-order), kept current
 * via CDC.
 */
```

`OrderItem` already stores `unitPriceCents` as its own column, copied at placement time instead of read live off `inventoryItem.getPriceCents()`. That part was correct before any service boundary existed, because a receipt shows what the customer paid, not what the SKU costs today. The SKU and product name aren't denormalized yet. Both are reached by following the live `@ManyToOne` into the current `inventory_items` row, so an order placed last year inherits whatever name that row holds today. If inventory renames "Deluxe Gadget" to "Deluxe Gadget Pro", every historical order relabels itself with no record that anything changed. That bug exists independently of any decomposition. The shared schema hid it by making "read the current row" the path of least resistance.

The target shape, as an illustration written before the split:

```java
// Illustrative: the target shape the inventory extraction builds, not yet in examples/.
// order_items gains its own columns; the @ManyToOne into InventoryItem
// is removed once inventory owns its own database.
@Entity
@Table(name = "order_items")
public class OrderItem {
    // ...
    @Column(name = "inventory_item_sku", nullable = false)
    private String sku;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productNameSnapshot;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents; // already owned today, unchanged
}
```

```sql
-- Illustrative: order_items after the FK is removed
ALTER TABLE order_items DROP CONSTRAINT order_items_inventory_item_id_fkey;
ALTER TABLE order_items ADD COLUMN inventory_item_sku VARCHAR(64) NOT NULL;
ALTER TABLE order_items ADD COLUMN product_name_snapshot VARCHAR(255) NOT NULL;
```

The inventory extraction shipped its version as `examples/00-monolith/src/main/resources/db/migration/V4__decompose_order_items_fk.sql`: it adds `sku` and `product_name`, backfills them from `inventory_items`, sets them `NOT NULL`, then drops the foreign key and the `inventory_item_id` column.

{% include excalidraw.html file="shared-db-to-database-per-service" alt="Before and after: on the left, one shared schema where order_items holds a live FK join into inventory_items, which is why a SKU rename silently relabels every historical order's display; on the right, inventory owns its own database, the FK is dropped, order_items gains sku and product-name snapshot columns written once at order-placement time, and a CDC connector backfills inventory's new database by tailing the old schema's write-ahead log." caption="Figure 18.2: order_items -> inventory_items: a live FK join today, a snapshot plus a CDC event feed after the split" %}

Three fields replace one join. SKU and name become snapshots written once at placement, as `unitPriceCents` already is, and the foreign key disappears along with the guarantee that the `inventory_items` row exists. Order no longer needs inventory's current stock, price or name, because it captured what mattered at the moment it mattered. For what order does need live, such as whether a SKU can still be reserved at checkout, the answer is a synchronous call across the seam (`quarkus-grpc` for inventory), because a stock check is exactly where a snapshot would be wrong.

## Data duplication as a design choice

Relational training treats duplicated data as a defect. Inside one database that's right: normalization is nearly free and one transaction keeps every copy consistent. Across two owned databases the alternative to duplication isn't consistency. It's a live cross-service call on every read, paying a network hop and a new failure mode for every join a single schema answered in microseconds.

Kleppmann's treatment of derived data names the reframe: a cache, an index, a materialized view and a denormalized copy of another service's data are the same idea, a redundant copy of an upstream fact in a shape optimized for this reader. Its correctness depends on how reliably it's kept in sync, not on whether a copy exists. So the question stops being whether to duplicate, since it will happen once two services need the fact, and becomes what keeps the copy accurate and how stale it may get before that's a bug. For inventory's data the answer is CDC: tailing the source's write-ahead log keeps a derived copy current with a stronger freshness guarantee than an application remembering to publish an event on every write.

### Read models fed by events

The outbox built for notification is this idea at its smallest. `OutboxEvent` is a durable, ordered record of one fact (an order was placed), published at-least-once to every consumer. The `Notification` table in `examples/03-notification-service` is a read model built only from that stream, never from a query against order's tables. That is the read side of CQRS in miniature: a purpose-built copy of the fields one consumer needs, kept current by consuming events. The GraphQL gateway's read side is one more event-fed projection of the same kind, assembled from more streams.

## Review and Notification: two answers to one question

Two extracted services in the repository make opposite choices under different constraints.

`examples/02-review-service` was extracted in its Phase A form with its schema still shared. Its `README.md` says it persists against "the SAME compose-stack Postgres the monolith uses... reading/writing the existing `reviews` table plus minimal read-only projections of the shared `customers`/`inventory_items` tables," and "owns **no** schema of its own yet". `Customer.java`'s javadoc explains why that is safe: dropping the `reviews` table from the shared schema today "would break the very service just cut over to." The service boundary and the data boundary are separable decisions, and Review scoped the service cut first.

`examples/03-notification-service` owned its schema from the first migration, and `V1__create_notifications_table.sql` gives the reason. Review only ever read a synchronously consistent REST contract, with one writer at a time (first the monolith, then the review service after cutover). Notification is event-driven. A read model sharing the upstream aggregate's table would have two writers, the monolith's synchronous path and the service's consumer, racing on the same rows for the whole cutover window with no way to reconcile them. Owning the schema avoids that split-brain by construction instead of by hoping nobody races. The aftermath, per `Customer.java`'s javadoc: the monolith's `notifications` table "is kept in this shared schema, now write-only history that nothing in this module reads or writes anymore". It is orphaned with no runtime dependency, a cleaner ending than Review's, which left a live reader to protect.

| Service | Schema | Writers during cutover | Why |
|---|---|---|---|
| Review | Shared, deferred | One at a time | Deferral is safe when you can state why it doesn't yet create a second writer. |
| Notification | Owned from the first migration | Two (monolith path, consumer) | A second independent write path is coming. |

## Planning the split

A schema live since `V1__init_schema.sql` has to split without a maintenance window. The naive way is stop the monolith, export inventory's rows, stand up a new database, import and restart pointed at it. That is a big-bang cutover with exactly the failure profile the strangler discipline exists to avoid.

The CDC way treats the split as a backfill. A connector tails the shared schema's write-ahead log and replicates every change to `inventory_items` into a new, independently owned database in near-real time, while the monolith keeps writing the old table. Once the replica has caught up and proven current, traffic cuts over: reads first, behind a flag, with the same reversibility as every extraction since Review. Only after the cut holds does the old table's write path retire. The schema splits gradually underneath a running system, the strangler lesson applied to data.

The equivalence gate has to change as well. Until now "equivalent" meant the same answer came back, with both backends reading the same rows or agreeing by coincidence, the false-equivalence trap that already bit Review. With two databases, a green run has to prove the new database is the one answering, not that it agrees with a shared table it never touched. The extension is the negative check from the notification extraction applied to the database split: kill the dependency, force red, restore it, confirm green.

> **Decision**
>
> Own the data from the first migration when a second writer is coming. Defer only when you can say why deferring creates no second writer. For each foreign key that crosses a boundary, snapshot the fields the consumer needs at the moment they mattered, and call across the seam only for facts that must be current.

> **🤖 Explore with your agent**
>
> "Review `examples/00-monolith/src/main/resources/db/migration/V1__init_schema.sql` and list every `REFERENCES` clause that crosses a bounded-context seam. For each, say whether the consuming context needs the value as of this instant or as of the moment it mattered, and propose a snapshot column or a seam call. Don't edit any file."
>
> Check: the reply finds nine foreign keys, eight of them crossing a seam, and proposes snapshot columns for `order_items` (SKU, product name) while keeping `unit_price_cents`.

## For deeper coverage

- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the derived-data framing ("a cache, an index, a materialized view, and a denormalized copy are the same idea") behind duplication as a choice.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the database-per-service pattern.
- Sam Newman, *Building Microservices*, 2nd ed. (O'Reilly, 2021): service boundaries and data ownership, and why a split database is far harder to undo than a split deployable.
- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): the shared-kernel concept behind `Customer`'s cross-context reach, and bounded-context ownership.
- [Debezium: Connector documentation](https://debezium.io/documentation/): the change-data-capture mechanism the plan relies on.
- Cloud Native Design Patterns, [Data](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/04-data.md): the companion book's treatment of data ownership and replication.
