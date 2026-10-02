-- SMELL[ch.18]: everything below lives in ONE shared Postgres schema, with FKs
-- crossing bounded-context boundaries freely (orders -> customers,
-- order_items -> inventory_items, payments/shipments/notifications -> orders,
-- reviews -> customers + inventory_items). A decomposed system owns one database
-- per context and replaces these joins with CDC-fed local copies or ACL calls.

CREATE TABLE customers (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL
);

CREATE TABLE inventory_items (
    id               BIGSERIAL PRIMARY KEY,
    sku              VARCHAR(64)  NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    price_cents      BIGINT       NOT NULL,
    quantity_on_hand INTEGER      NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);

CREATE TABLE orders (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT       NOT NULL REFERENCES customers (id),
    status           VARCHAR(32)  NOT NULL,
    total_cents      BIGINT       NOT NULL,
    shipping_address VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);

CREATE TABLE order_items (
    id                 BIGSERIAL PRIMARY KEY,
    order_id           BIGINT  NOT NULL REFERENCES orders (id),
    inventory_item_id  BIGINT  NOT NULL REFERENCES inventory_items (id),
    quantity           INTEGER NOT NULL,
    unit_price_cents   BIGINT  NOT NULL
);

CREATE TABLE payments (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES orders (id),
    amount_cents BIGINT      NOT NULL,
    method       VARCHAR(64) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    created_at   TIMESTAMP   NOT NULL
);

CREATE TABLE shipments (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT       NOT NULL REFERENCES orders (id),
    address    VARCHAR(255) NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMP    NOT NULL
);

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT      NOT NULL REFERENCES customers (id),
    order_id    BIGINT      REFERENCES orders (id),
    channel     VARCHAR(32) NOT NULL,
    message     VARCHAR(1024) NOT NULL,
    sent_at     TIMESTAMP   NOT NULL
);

CREATE TABLE reviews (
    id                BIGSERIAL PRIMARY KEY,
    customer_id       BIGINT       NOT NULL REFERENCES customers (id),
    inventory_item_id BIGINT       NOT NULL REFERENCES inventory_items (id),
    rating            INTEGER      NOT NULL,
    comment           VARCHAR(2048),
    created_at        TIMESTAMP    NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_inventory_item_id ON order_items (inventory_item_id);
CREATE INDEX idx_payments_order_id ON payments (order_id);
CREATE INDEX idx_shipments_order_id ON shipments (order_id);
CREATE INDEX idx_notifications_customer_id ON notifications (customer_id);
CREATE INDEX idx_reviews_inventory_item_id ON reviews (inventory_item_id);
