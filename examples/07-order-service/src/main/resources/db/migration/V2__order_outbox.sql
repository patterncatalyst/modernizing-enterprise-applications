-- ch.26 S4 (DRQ-068/073, Phase A): RESERVES this service's OWN transactional
-- outbox table, mirroring the monolith's/payment's/shipping's proven outbox
-- shape (own schema, own Flyway history). Nothing writes to this table in
-- Phase A -- no OrderOutboxEvent entity or OrderOutboxRelay exists yet in
-- src/main/java -- that Java-side wiring, plus the order.placed event write
-- inside placeOrder's transaction, is S5's job (which also needs S3's event
-- contract). The table exists now purely so S5 can add the Java mapping
-- without a schema migration of its own.
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(128)  NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
