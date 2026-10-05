-- r07/ch.24 S5 (DRQ-064): idempotency backstop for the saga consumer.
--
-- Phase A (V1) deliberately did NOT add a uniqueness constraint on
-- order_id -- no producer/consumer existed yet, so nothing could redeliver
-- a duplicate. Now that PaymentCapturedConsumer/ShippingService exist (at
-- least once Kafka delivery of payment.captured), a database-level UNIQUE
-- constraint is the backstop for the application-level check-then-act
-- guard in ShippingService#processPaymentCaptured -- the same idempotency
-- discipline payment's uq_payments_order_id applies (DRQ-051/DRQ-064): at
-- most one shipment per order in this demo's model (no partial/split
-- shipments).
--
-- The pre-existing non-unique idx_shipments_order_id (V1) is superseded --
-- the unique constraint's own backing index serves the identical
-- "find shipment by orderId" query the read surface and the saga/
-- compensation steps already use.
DROP INDEX IF EXISTS idx_shipments_order_id;

ALTER TABLE shipments ADD CONSTRAINT uq_shipments_order_id UNIQUE (order_id);
