-- ch.26 S4 (DRQ-068/073): this service's OWN schema (quarkus.flyway.schemas=
-- order_service; the connection's currentSchema points here too) -- NOT the
-- monolith's shared `monolith` database's shared tables. Store starts EMPTY
-- and is forward-filled only -- deliberately NO CDC backfill from the
-- monolith's existing customers/orders/order_items rows (contrast
-- inventory-service's CDC-fed backfill, DRQ-040): this step's scope is
-- read+command surface + entities + own schema only.
--
-- Schema name note: the natural single-word analog to payment/inventory/
-- shipping's schema names would be `order`, but ORDER is a reserved SQL
-- keyword in Postgres (ambiguous in several unquoted-identifier positions,
-- including schema qualifiers) -- `order_service` is used instead to avoid
-- quoting gymnastics in every migration/query/JDBC URL, same tradeoff this
-- step's application.properties documents.

CREATE TABLE customers (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- FK decomposition (DRQ-068): orders.customer_id is a plain VALUE column, NOT
-- a DB-level FK into customers.id -- even though customers is now co-owned
-- by THIS SAME service's schema (customer was never its own extraction; it
-- comes along with order). The FK is deliberately omitted (not merely the
-- JPA @ManyToOne association) to keep Order's aggregate boundary decoupled
-- from Customer's lifecycle, mirroring the order_items.sku soft-reference
-- precedent (DRQ-043) rather than re-introducing a hidden join dependency.
-- customer_email is a SNAPSHOT captured at order-placement time (see
-- Order.java), so a later customer email change never retroactively alters a
-- historical order.
CREATE TABLE orders (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT       NOT NULL,
    customer_email   VARCHAR(255) NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    total_cents      BIGINT       NOT NULL,
    shipping_address VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);

-- Denormalized snapshot (DRQ-043, lifted unchanged from the monolith's
-- V4__decompose_order_items_fk.sql end state): sku is a soft reference only,
-- no DB FK and no JPA association onto inventory data -- order_id -> orders.id
-- IS a real FK since both tables are owned by this SAME aggregate/service.
CREATE TABLE order_items (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT       NOT NULL REFERENCES orders (id),
    sku              VARCHAR(64)  NOT NULL,
    product_name     VARCHAR(255) NOT NULL,
    quantity         INTEGER      NOT NULL,
    unit_price_cents BIGINT       NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
