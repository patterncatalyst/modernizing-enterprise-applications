package dev.patterncatalyst.inventory;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * r05/ch.19 S5 (DRQ-040, DRQ-044-style net-new). Exercises
 * {@link InventoryCdcConsumer} through the REAL {@code @Incoming("inventory-cdc")}
 * Reactive Messaging pipeline by feeding Debezium-shaped JSON envelopes into
 * SmallRye's in-memory connector (see {@link InMemoryMessagingTestProfile}),
 * not by calling {@code consumer.consume(json)} directly -- so the channel
 * wiring itself (String payload binding, the thing a live Kafka broker would
 * otherwise provide) is also under test.
 *
 * <p>Envelopes below use the FLAT shape the real connector emits
 * ({@code key/value.converter.schemas.enable=false} --
 * infra/debezium/README.md): {@code {"before":...,"after":...,"op":...,"ts_ms":...}},
 * no outer {@code {"schema":...,"payload":{...}}} wrapper.
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class InventoryCdcConsumerTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    InventoryRepository repository;

    @Test
    void snapshotReadEvent_backfillsRow_andIsServedByReadSurface() {
        InMemorySource<String> source = connector.source("inventory-cdc");

        // op=r -- the initial-snapshot backfill event Debezium emits once per
        // existing row at connector registration (snapshot.mode=initial).
        String snapshotEvent = """
                {"before":null,"after":{"id":401,"sku":"SKU-CDC-SNAPSHOT","name":"Snapshot Widget",\
                "price_cents":2999,"quantity_on_hand":42,"updated_at":1767600000000},\
                "source":{"table":"inventory_items"},"op":"r","ts_ms":1767600001000}""";

        source.send(snapshotEvent);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                        QuarkusTransaction.requiringNew().call(() -> repository.findBySku("SKU-CDC-SNAPSHOT")))
                        .isPresent());

        InventoryItem saved = QuarkusTransaction.requiringNew()
                .call(() -> repository.findBySku("SKU-CDC-SNAPSHOT"))
                .orElseThrow();
        assertThat(saved.getId()).isEqualTo(401L);
        assertThat(saved.getName()).isEqualTo("Snapshot Widget");
        assertThat(saved.getPriceCents()).isEqualTo(2999L);
        assertThat(saved.getQuantityOnHand()).isEqualTo(42);

        given()
                .when().get("/api/inventory/SKU-CDC-SNAPSHOT")
                .then()
                .statusCode(200)
                .body("sku", is("SKU-CDC-SNAPSHOT"))
                .body("quantityOnHand", is(42));
    }

    @Test
    void updateEvent_isIdempotentUnderRedelivery() {
        InMemorySource<String> source = connector.source("inventory-cdc");

        String createEvent = """
                {"before":null,"after":{"id":402,"sku":"SKU-CDC-UPDATE","name":"Update Widget",\
                "price_cents":1500,"quantity_on_hand":10,"updated_at":1767600000000},\
                "source":{"table":"inventory_items"},"op":"c","ts_ms":1767600001000}""";
        source.send(createEvent);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                        QuarkusTransaction.requiringNew().call(() -> repository.findBySku("SKU-CDC-UPDATE")))
                        .isPresent());

        // op=u -- a streamed change decrementing stock from 10 to 7.
        String updateEvent = """
                {"before":{"id":402,"sku":"SKU-CDC-UPDATE","name":"Update Widget","price_cents":1500,\
                "quantity_on_hand":10,"updated_at":1767600000000},\
                "after":{"id":402,"sku":"SKU-CDC-UPDATE","name":"Update Widget","price_cents":1500,\
                "quantity_on_hand":7,"updated_at":1767600002000},\
                "source":{"table":"inventory_items"},"op":"u","ts_ms":1767600003000}""";

        // Send the SAME update event twice -- the at-least-once redelivery
        // scenario DRQ-040's idempotency requirement targets. The native
        // ON CONFLICT upsert must leave exactly one row at quantity 7, not
        // apply the decrement twice or duplicate the row.
        source.send(updateEvent);
        source.send(updateEvent);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(QuarkusTransaction.requiringNew()
                        .call(() -> repository.findBySku("SKU-CDC-UPDATE").orElseThrow().getQuantityOnHand()))
                        .isEqualTo(7));

        // The redelivered duplicate must not re-apply on top of itself (e.g. a
        // buggy delta-based decrement would land at 4, not 7) or spawn a
        // second row (the id/sku UNIQUE constraints make that impossible at
        // the DB level, but assert the application-level view agrees): the
        // quantity stays at exactly 7, continuously, for a window.
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(8))
                .until(() -> QuarkusTransaction.requiringNew()
                        .call(() -> repository.findBySku("SKU-CDC-UPDATE").orElseThrow().getQuantityOnHand()) == 7);
    }

    @Test
    void deleteEvent_removesRowFromOwnedSchema() {
        InMemorySource<String> source = connector.source("inventory-cdc");

        String createEvent = """
                {"before":null,"after":{"id":403,"sku":"SKU-CDC-DELETE","name":"Delete Widget",\
                "price_cents":500,"quantity_on_hand":3,"updated_at":1767600000000},\
                "source":{"table":"inventory_items"},"op":"c","ts_ms":1767600001000}""";
        source.send(createEvent);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                        QuarkusTransaction.requiringNew().call(() -> repository.findBySku("SKU-CDC-DELETE")))
                        .isPresent());

        // op=d -- Debezium carries the deleted row's key/full image in
        // 'before' (REPLICA IDENTITY FULL, per infra/debezium/README.md);
        // 'after' is null.
        String deleteEvent = """
                {"before":{"id":403,"sku":"SKU-CDC-DELETE","name":"Delete Widget","price_cents":500,\
                "quantity_on_hand":3,"updated_at":1767600000000},\
                "after":null,"source":{"table":"inventory_items"},"op":"d","ts_ms":1767600004000}""";
        source.send(deleteEvent);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                        QuarkusTransaction.requiringNew().call(() -> repository.findBySku("SKU-CDC-DELETE")))
                        .isEmpty());

        given()
                .when().get("/api/inventory/SKU-CDC-DELETE")
                .then()
                .statusCode(404);
    }
}
