package dev.patterncatalyst.order;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import java.util.HashMap;
import java.util.Map;

/**
 * ch.26 S5 -- mirrors payment-service's/shipping-service's {@code
 * InMemoryMessagingTestProfile}: swaps this service's FOUR incoming saga
 * channels ({@code payment-captured}/{@code payment-declined}/{@code
 * shipment-dispatched}/{@code shipment-failed}) AND its one outgoing outbox
 * channel ({@code order-placed}) from the real {@code smallrye-kafka}
 * connector onto SmallRye's in-memory connector -- the official pattern for
 * exercising both the {@code @Incoming} reactions AND the outbox relay's
 * {@code @Channel} emitter through the REAL Reactive Messaging pipeline,
 * without a live Kafka broker.
 */
public class InMemoryMessagingTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(InMemoryConnector.switchIncomingChannelsToInMemory(
                "payment-captured", "payment-declined", "shipment-dispatched", "shipment-failed"));
        overrides.putAll(InMemoryConnector.switchOutgoingChannelsToInMemory("order-placed"));
        return overrides;
    }
}
