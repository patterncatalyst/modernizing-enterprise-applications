-- r06/ch.23 S5 (DRQ-053): this service's OWN transactional outbox, mirroring
-- the monolith's proven V3__outbox.sql (ch.17/r04/S3) in its OWN `payment`
-- schema (unqualified -- this connection's currentSchema=payment, same as
-- V1__create_payments_table.sql).
--
-- Written by PaymentService#processOrderPlaced in the SAME @Transactional as
-- the payments row write (see Payment.java), so the payment.captured/
-- payment.declined event is atomic with the capture/decline it describes.
-- PaymentOutboxRelay (@Scheduled) then reads unpublished rows and publishes
-- them to Kafka at-least-once, stamping published_at on success.
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
-- same discipline as the monolith's idx_outbox_unpublished.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
