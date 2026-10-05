package dev.patterncatalyst.notification;

import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * NET-NEW for ch.17 S5 — the "WebSockets.Next" showcase named in
 * build-plan §E row 2. Adapted from datamesh's {@code OrderPlacedPushConsumer}:
 * consumes the SAME {@code order.placed} Kafka topic as
 * {@link OrderPlacedConsumer}, but via a SECOND Reactive Messaging channel
 * ({@code order-placed-push}) bound to a per-replica UNIQUE consumer group
 * id (derived from {@code HOSTNAME}, see {@code application.properties}),
 * instead of the shared, partition-balanced group the persistence consumer
 * uses.
 *
 * <p>Kept modest per the plan: this consumer does ONLY the WebSocket push
 * (never persists, not {@code @Transactional}) — it builds a transient,
 * never-persisted {@link NotificationDto} view straight from the event and
 * fire-and-forgets it to every connection currently open on
 * {@code /ws/notifications} in this JVM, via the injected
 * {@link OpenConnections} registry (datamesh's exact pattern, same
 * {@code quarkus-websockets-next} API, same platform version 3.40.1).
 */
@ApplicationScoped
public class OrderPlacedPushConsumer {

    private static final Logger LOG = Logger.getLogger(OrderPlacedPushConsumer.class);

    @Inject
    OpenConnections connections;

    @Incoming("order-placed-push")
    public void consume(OrderPlacedEvent event) {
        NotificationDto notification = new NotificationDto(
                null, event.customerId(), event.orderId(), "EMAIL", event.confirmationMessage(), event.placedAt());
        connections.listAll().forEach(connection -> connection.sendText(notification)
                .subscribe().with(
                        ignored -> {
                        },
                        failure -> LOG.warnf(failure,
                                "failed to push notification for order %d to a WebSocket client",
                                event.orderId())));
    }
}
