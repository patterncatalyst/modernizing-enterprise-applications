-- notification-service owns this schema from day one (ch.17 S4, DRQ-035).
-- Distinct from examples/00-monolith's `public.notifications` table: this is
-- a SEPARATE table in a SEPARATE Postgres schema, in the SAME podman-stack
-- Postgres *instance* (localhost:5432, db `monolith`) every example in this
-- repo shares. Flyway (quarkus.flyway.schemas=notification) creates the
-- `notification` schema automatically before running this migration; the
-- explicit CREATE SCHEMA below is defensive/idempotent, not required.
--
-- Why own schema, not shared: ch.15's Review extraction (Phase A) could stay
-- in the monolith's shared schema because Review only ever *reads* a
-- synchronously-consistent REST contract. Notification is event-driven
-- (ch.17 S5 consumes `order.placed` off Kafka asynchronously) — an
-- event-driven read model that shared the upstream aggregate's table would
-- have two writers racing on the same rows (the monolith's now-decommissioned
-- synchronous path, and this service's consumer) with no way to reconcile
-- them. Owning the table from the start avoids that split-brain entirely and
-- is the honest teaching point of DRQ-035's "own-schema" decision.
CREATE SCHEMA IF NOT EXISTS notification;

-- customer_id / order_id are plain BIGINT columns, NOT @ManyToOne/FOREIGN KEY
-- references into the monolith's `customers`/`orders` tables the way the
-- monolith's own Notification.java joins them (see that class's SMELL[ch.18]
-- javadoc). This service's schema must not reach across into tables it does
-- not own, so the Phase-A lift drops the JPA relations and keeps only the
-- foreign *values* — see Notification.java javadoc for the full rationale.
CREATE TABLE IF NOT EXISTS notification.notifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_id    BIGINT,
    channel     VARCHAR(32) NOT NULL,
    message     VARCHAR(500) NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notifications_customer_id
    ON notification.notifications (customer_id);
