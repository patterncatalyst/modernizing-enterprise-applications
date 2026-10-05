-- ch.17 (r04/S3): the transactional OUTBOX for the notification extraction.
--
-- Cures SMELL[ch.17] (NotificationService#sendOrderConfirmation called
-- synchronously, in-process, inside the checkout @Transactional) WITHOUT yet
-- removing the synchronous path — that decommission is S8, after the new
-- consumer (S5) and the cutover (S7) are proven. Today this table is written
-- only when notification.mode=outbox (see OrderService#placeOrder); in the
-- default notification.mode=synchronous it stays empty.
--
-- Pattern taught here (DRQ-034): a SIMPLE POLLING RELAY (OutboxRelay,
-- @Scheduled), not Debezium/CDC. The write below happens in the SAME
-- @Transactional as the order/payment/shipment writes, so it is atomic with
-- the order — a payment decline rolls the outbox row back too, same as every
-- other write in that transaction. The relay then reads unpublished rows and
-- publishes them to Kafka at-least-once, stamping published_at on success.
-- CDC (reading the WAL instead of polling this table) is deferred to ch.19.

CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(128)  NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT now(),
    published_at    TIMESTAMP
);

-- The relay's hot query is "give me unpublished rows, oldest first" — a
-- partial index keeps that scan cheap even as published rows accumulate
-- (DRQ-034's acknowledged tradeoff: polling costs a periodic read, unlike
-- CDC's WAL tailing; this index is how we keep that cost small).
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
