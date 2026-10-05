-- r07/ch.24 S5 (DRQ-063, mirrors payment's V3__payment_outbox.sql,
-- DRQ-053): this service's OWN transactional outbox, in its OWN `shipping`
-- schema (unqualified -- this connection's currentSchema=shipping).
--
-- Written by ShipmentSagaSteps#emitDispatched (shipment.dispatched, the
-- saga's happy-path step 4) and ShipmentSagaSteps#compensate
-- (shipment.failed, the coordinator-invoked compensation route
-- direct:ship-compensate) -- each in the SAME local transaction as the
-- Shipment row's status transition it describes (PENDING->DISPATCHED or
-- PENDING->CANCELLED), so the emitted event is atomic with the state
-- change: either both commit, or neither does. ShipmentOutboxRelay
-- (@Scheduled) then reads unpublished rows and publishes them to Kafka
-- at-least-once, stamping published_at on success.
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(128)  NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

-- The relay's hot query is "give me unpublished rows, oldest first" -- a
-- partial index keeps that scan cheap even as published rows accumulate,
-- same discipline as every other outbox table in this repo.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
