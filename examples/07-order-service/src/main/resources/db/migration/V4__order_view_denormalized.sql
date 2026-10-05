-- ch.26 S6 (DRQ-067/074): the REAL denormalized CQRS read model, replacing
-- S4's placeholder shape (`V3__order_view.sql`: order_id/payload/updated_at
-- only). DROP + CREATE (not ALTER) is safe here specifically because the
-- placeholder has been EMPTY and UNWRITTEN through every prior step -- S4
-- reserved it, and S5's MIGRATION.md records "nothing writes to this table"
-- all the way through Phase B (no Java entity ever mapped to it, no
-- projection consumer existed yet). There is no data to preserve across the
-- shape change. V1/V2/V3 are untouched (already-applied migrations are never
-- edited in this repo's discipline, per every prior extraction's precedent)
-- -- this is a NEW migration file, V4, same as any other schema evolution.
DROP TABLE order_view;

-- One row per order, maintained EXCLUSIVELY by OrderViewProjector
-- (OrderViewProjector.java, called in the SAME @Transactional as every
-- write: OrderService#placeOrder's initial PENDING row, and each of
-- OrderSagaListener's four lifecycle reactions) and read EXCLUSIVELY by
-- OrderViewRepository (OrderService#getById/listAll) -- no read caller in
-- this service queries order_service.orders/order_items to answer a query;
-- a broken projection cannot be masked by a silent fallback to the
-- aggregate (DRQ-067).
--
-- Shape is sufficient to serve OrderDto
-- (id/customerId/status/totalCents/createdAt/shippingAddress/
-- items[sku,quantity,unitPriceCents]) BYTE-FOR-BYTE from this table alone.
-- payment_status/shipment_status are the latest PROJECTED outcome of the two
-- cross-service sagas this order participates in -- read-model-internal
-- columns the CQRS shape calls for (DRQ-067), NOT part of the external
-- OrderDto contract (nullable: unknown until the corresponding saga event is
-- consumed, or forever null for payment_status on an order that never left
-- PENDING).
CREATE TABLE order_view (
    order_id         BIGINT PRIMARY KEY,
    customer_id      BIGINT       NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    total_cents      BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    shipping_address VARCHAR(255) NOT NULL,
    items            JSONB        NOT NULL,
    payment_status   VARCHAR(32),
    shipment_status  VARCHAR(32),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Mirrors orders.idx_orders_customer_id (V1__create_order_schema.sql) -- a
-- read model built to answer "this customer's orders" cheaply, same as the
-- write side.
CREATE INDEX idx_order_view_customer_id ON order_view (customer_id);
