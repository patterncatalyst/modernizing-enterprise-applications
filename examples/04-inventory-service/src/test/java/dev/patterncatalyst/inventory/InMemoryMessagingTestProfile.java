package dev.patterncatalyst.inventory;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import java.util.Map;

/**
 * NET-NEW for r05/ch.19 S5, mirroring notification-service's
 * {@code InMemoryMessagingTestProfile}. Swaps the {@code inventory-cdc}
 * incoming channel from the real {@code smallrye-kafka} connector onto
 * SmallRye's in-memory connector for the duration of
 * {@link InventoryCdcConsumerTest} -- the official pattern for exercising an
 * {@code @Incoming} consumer through the REAL Reactive Messaging pipeline
 * (channel config, String payload handling, offset/ack semantics) without
 * requiring either a live Kafka broker or bypassing the wiring entirely by
 * invoking the consumer's method directly.
 */
public class InMemoryMessagingTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return InMemoryConnector.switchIncomingChannelsToInMemory("inventory-cdc");
    }
}
