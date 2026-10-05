-- ch.26 S4 (DRQ-067/073, Phase A): RESERVES the CQRS read-model table for the
-- future order-service projection (S6). Empty and unwritten in Phase A -- no
-- Java entity maps to it yet, and no projection consumer exists. Reserving it
-- now (rather than adding it in a later migration) means this service owns
-- the migration file from day one; S6 is free to ALTER this table's shape as
-- the projection's actual read needs become concrete, which is a normal
-- Flyway migration, not a schema-ownership change.
--
-- Columns are a deliberately minimal placeholder: order_id (the projection
-- key) + a JSONB payload (the eventually-consistent read-model document) +
-- updated_at (last-projected-at, for staleness/debugging). S6 defines the
-- real shape.
CREATE TABLE order_view (
    order_id    BIGINT PRIMARY KEY,
    payload     JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
