package dev.patterncatalyst.notification;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import java.util.Map;

/**
 * NET-NEW for ch.17 S5. Swaps both {@code order-placed} and
 * {@code order-placed-push} incoming channels from the real
 * {@code smallrye-kafka} connector onto SmallRye's in-memory connector for
 * the duration of {@link OrderPlacedConsumerTest} — the official pattern for
 * exercising an {@code @Incoming} consumer through the REAL Reactive
 * Messaging pipeline (channel config, deserialization-by-contract, offset
 * handling semantics) without requiring either a live Kafka broker or
 * bypassing the wiring entirely by invoking the consumer's method directly.
 */
public class InMemoryMessagingTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return InMemoryConnector.switchIncomingChannelsToInMemory("order-placed", "order-placed-push");
    }
}
