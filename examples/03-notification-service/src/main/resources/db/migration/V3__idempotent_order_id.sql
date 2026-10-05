-- ch.17 Phase B (r04/S5): the SmallRye order.placed consumer
-- (OrderPlacedConsumer) is now this schema's real write path (DRQ-035) --
-- supersedes the Phase A (S4) demo seed row (V2__seed_demo_notification.sql),
-- which would otherwise sit at order_id=1/customer_id=1 and collide with a
-- REAL checkout against the demo customer/order ids used throughout this
-- repo's demos, defeating the dedupe-by-order-id idempotency check below
-- (a real order #1 would look like an already-recorded duplicate).
DELETE FROM notification.notifications WHERE customer_id = 1 AND order_id = 1 AND channel = 'EMAIL';

-- Database-level backstop for the consumer's idempotent dedupe
-- (NotificationService#recordOrderPlaced's check-then-insert): the
-- monolith's OutboxRelay is AT LEAST ONCE, so the same order.placed event
-- can be redelivered. A PARTIAL unique index (ignoring NULL order_id, since
-- not every notification need originate from an order) guarantees the
-- database itself rejects a second row for the same order, even in the
-- (currently unlikely, single-partition-consumer) case of two deliveries
-- racing concurrently.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_order_id
    ON notification.notifications (order_id)
    WHERE order_id IS NOT NULL;
