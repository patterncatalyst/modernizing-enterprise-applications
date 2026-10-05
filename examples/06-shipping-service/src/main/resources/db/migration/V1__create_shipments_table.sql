-- r07/ch.24 S4 (DRQ-063): shipping-service's OWN schema/table.
--
-- Mirrors the monolith's `shipments` table shape EXCEPT `order_id`: the
-- monolith's column is a real FK/join into its `orders` table in the SAME
-- shared schema (SMELL[ch.18], see monolith shipping.Shipment's javadoc).
-- Here `order_id` is a plain VALUE column -- no foreign key, no cross-schema
-- reference -- because this service owns its own `shipping` schema and
-- cannot (and must not) reach across into the monolith's `orders` table. It
-- is the event-carried correlation key the future orchestrated saga (S5:
-- consume payment.captured, dispatch, emit shipment.dispatched /
-- shipment.failed) will use to tie a shipment back to the order that
-- triggered it. Same FK-decomposition discipline payment's Payment entity
-- applied (DRQ-052).
--
-- `status` is VARCHAR(32), wide enough for the extended ShipmentStatus set
-- (PENDING, DISPATCHED, CANCELLED, FAILED) this service's entity declares --
-- a superset of the monolith's single DISPATCHED value, needed by the S5
-- saga's compensation leg (cancel a shipment on a forced failure).
CREATE TABLE shipments (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL,
    address        VARCHAR(255) NOT NULL,
    status         VARCHAR(32) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_shipments_order_id ON shipments (order_id);
