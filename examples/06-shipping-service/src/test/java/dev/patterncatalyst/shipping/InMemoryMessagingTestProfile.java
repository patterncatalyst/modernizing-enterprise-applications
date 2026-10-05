package dev.patterncatalyst.shipping;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import java.util.HashMap;
import java.util.Map;

/**
 * r07/ch.24 S5 -- mirrors payment-service's {@code InMemoryMessagingTestProfile}:
 * swaps the {@code payment-captured} incoming channel AND the {@code
 * shipment-dispatched}/{@code shipment-failed} outgoing channels from the
 * real {@code smallrye-kafka} connector onto SmallRye's in-memory connector
 * for the duration of {@link PaymentCapturedConsumerTest} -- the official
 * pattern for exercising both the {@code @Incoming} consumer AND the outbox
 * relay's {@code @Channel} emitters through the REAL Reactive Messaging
 * pipeline, without a live Kafka broker.
 */
public class InMemoryMessagingTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides =
                new HashMap<>(InMemoryConnector.switchIncomingChannelsToInMemory("payment-captured"));
        overrides.putAll(
                InMemoryConnector.switchOutgoingChannelsToInMemory("shipment-dispatched", "shipment-failed"));
        return overrides;
    }
}
