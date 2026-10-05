-- r06/ch.23 S4 (DRQ-052): payment-service's OWN schema/table.
--
-- Mirrors the monolith's `payments` table shape EXCEPT `order_id`: the
-- monolith's column is a real FK/join into its `orders` table in the SAME
-- shared schema (SMELL[ch.18]). Here `order_id` is a plain VALUE column --
-- no foreign key, no cross-schema reference -- because this service owns its
-- own `payment` schema and cannot (and must not) reach across into the
-- monolith's `orders` table. It is the event-carried correlation key the
-- future choreography (S5: consume order.placed, emit payment.captured /
-- payment.declined) will use to tie a captured/declined payment back to the
-- order that triggered it.
CREATE TABLE payments (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL,
    amount_cents   BIGINT NOT NULL,
    method         VARCHAR(255) NOT NULL,
    status         VARCHAR(32) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

-- An order is charged at most once in this demo's model (no partial
-- captures/refunds), so a redelivered order.placed (S5, at-least-once Kafka)
-- can be deduped by order_id at the database level, same idempotency
-- discipline as notification-service's uq_notifications_order_id.
CREATE UNIQUE INDEX uq_payments_order_id ON payments (order_id);
