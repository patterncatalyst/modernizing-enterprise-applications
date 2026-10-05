package dev.patterncatalyst.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * NET-NEW for r05/ch.19 S5 (DRQ-040, DRQ-044-style: no Spring original to
 * lift -- the monolith never consumed its own {@code inventory_items}
 * change stream). Authored directly on SmallRye Reactive Messaging / Quarkus
 * idioms, mirroring notification-service's {@code OrderPlacedConsumer}
 * (persistence-only, delegating the write to a repository method rather than
 * a Panache active-record entity).
 *
 * <p><b>Role: initial-snapshot backfill + streaming sync.</b> The Debezium
 * Postgres connector ({@code infra/debezium/inventory-connector.json},
 * {@code snapshot.mode=initial}) first emits one {@code op=r} ("read") event
 * per existing row in the monolith's {@code public.inventory_items} table --
 * this backfills this service's own {@code inventory.inventory_items} copy
 * from scratch -- then streams {@code op=c/u/d} events as the monolith's
 * table changes, keeping the copy current during the transition window
 * (DRQ-040). All four ops funnel through the same idempotent
 * {@link InventoryCdcWriter#upsert}/{@link InventoryCdcWriter#deleteById}
 * so redelivery (Kafka's at-least-once default) never double-applies a
 * change.
 *
 * <p><b>Envelope shape.</b> The connector runs with
 * {@code key/value.converter.schemas.enable=false}
 * (infra/debezium/README.md), so Kafka Connect's JsonConverter emits the
 * Debezium envelope FLAT on the wire -- {@code {"before":...,"after":...,
 * "source":...,"op":...,"ts_ms":...}} -- with no outer
 * {@code {"schema":...,"payload":{...}}} wrapper. This channel is therefore
 * bound to a plain {@code String} payload (see {@code application.properties}'s
 * {@code value.deserializer=StringDeserializer}) rather than a typed record
 * Quarkus could auto-deserialize, and the envelope is parsed by hand with
 * Jackson -- {@link #unwrapPayload} defensively also accepts a
 * {@code {"payload": {...}}}-wrapped shape, in case {@code schemas.enable}
 * is ever flipped back on.
 */
@ApplicationScoped
public class InventoryCdcConsumer {

    private static final Logger LOG = Logger.getLogger(InventoryCdcConsumer.class);

    /**
     * Debezium represents a Postgres {@code TIMESTAMP} column as either
     * epoch MILLIS ({@code io.debezium.time.Timestamp}) or epoch MICROS
     * ({@code io.debezium.time.MicroTimestamp}) depending on column
     * precision -- serialized as a bare JSON number once
     * {@code schemas.enable=false} strips the field-schema metadata that
     * would otherwise disambiguate the two. Any value larger than this
     * ceiling (epoch millis for roughly the year 5138) cannot be a sane
     * millis timestamp, so it is treated as micros instead.
     */
    private static final long EPOCH_MILLIS_CEILING = 100_000_000_000_000L;

    private final InventoryCdcWriter writer;
    private final ObjectMapper objectMapper;

    public InventoryCdcConsumer(InventoryCdcWriter writer, ObjectMapper objectMapper) {
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    @Incoming("inventory-cdc")
    @Transactional
    public void consume(String json) {
        if (json == null || json.isBlank()) {
            // Debezium tombstone (null value, published after a delete once
            // the log-compaction key is processed). tombstones.on.delete is
            // configured false in this connector, so we should not normally
            // see one -- guard defensively anyway.
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            LOG.errorf(e, "inventory-cdc: failed to parse Debezium envelope JSON: %s", json);
            return;
        }

        JsonNode envelope = unwrapPayload(root);
        String op = envelope.path("op").asText(null);
        if (op == null) {
            LOG.warnf("inventory-cdc: envelope missing 'op', skipping: %s", json);
            return;
        }

        switch (op) {
            case "r", "c", "u" -> upsert(envelope.get("after"));
            case "d" -> delete(envelope.get("before"));
            default -> LOG.warnf("inventory-cdc: unhandled Debezium op '%s', skipping", op);
        }
    }

    private static JsonNode unwrapPayload(JsonNode root) {
        return root.has("payload") ? root.get("payload") : root;
    }

    private void upsert(JsonNode after) {
        if (after == null || after.isNull()) {
            LOG.warn("inventory-cdc: upsert op with no 'after' payload, skipping");
            return;
        }
        long id = after.get("id").asLong();
        String sku = after.get("sku").asText();
        String name = after.get("name").asText();
        long priceCents = after.get("price_cents").asLong();
        int quantityOnHand = after.get("quantity_on_hand").asInt();
        Instant updatedAt = parseDebeziumTimestamp(after.get("updated_at"));

        writer.upsert(id, sku, name, priceCents, quantityOnHand, updatedAt);
        LOG.infof("inventory-cdc: upserted id=%d sku=%s quantityOnHand=%d", id, sku, quantityOnHand);
    }

    private void delete(JsonNode before) {
        if (before == null || before.isNull() || before.get("id") == null) {
            LOG.warn("inventory-cdc: delete op with no 'before.id', skipping");
            return;
        }
        long id = before.get("id").asLong();
        writer.deleteById(id);
        LOG.infof("inventory-cdc: deleted id=%d", id);
    }

    private static Instant parseDebeziumTimestamp(JsonNode node) {
        if (node == null || node.isNull()) {
            return Instant.now();
        }
        long value = node.asLong();
        return value > EPOCH_MILLIS_CEILING
                ? Instant.ofEpochMilli(value / 1_000L)
                : Instant.ofEpochMilli(value);
    }
}
