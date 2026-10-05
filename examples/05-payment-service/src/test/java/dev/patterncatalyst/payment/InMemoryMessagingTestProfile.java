package dev.patterncatalyst.payment;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import java.util.HashMap;
import java.util.Map;

/**
 * NET-NEW for r06/ch.23 S5 -- mirrors notification-service's {@code
 * InMemoryMessagingTestProfile} (ch.17/S5): swaps the {@code order-placed}
 * incoming channel AND the {@code payment-captured}/{@code payment-declined}
 * outgoing channels from the real {@code smallrye-kafka} connector onto
 * SmallRye's in-memory connector for the duration of {@link
 * OrderPlacedConsumerTest} -- the official pattern for exercising both the
 * {@code @Incoming} consumer AND the outbox relay's {@code @Channel} emitters
 * through the REAL Reactive Messaging pipeline, without a live Kafka broker.
 */
public class InMemoryMessagingTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(InMemoryConnector.switchIncomingChannelsToInMemory("order-placed"));
        overrides.putAll(InMemoryConnector.switchOutgoingChannelsToInMemory("payment-captured", "payment-declined"));
        return overrides;
    }
}
