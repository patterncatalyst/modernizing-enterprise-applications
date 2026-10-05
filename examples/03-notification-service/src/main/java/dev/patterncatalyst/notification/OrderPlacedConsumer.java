package dev.patterncatalyst.notification;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * NET-NEW for ch.17 S5 — "idiomatic-from-the-start" (DRQ-035): the monolith
 * never consumed its own {@code order.placed} event (it only ever sent the
 * confirmation synchronously, in-process), so there is no Spring original to
 * lift here. Authored directly on SmallRye Reactive Messaging / Quarkus
 * idioms.
 *
 * <p>Adapted from {@code ~/Dev/datamesh-reference-arch-quarkus/examples/
 * notification-service}'s {@code OrderPlacedConsumer} (DRQ-032's non-trivial
 * pattern): persistence-only, delegating the idempotent write to
 * {@link NotificationService#recordOrderPlaced(OrderPlacedEvent)} rather than
 * writing to the repository directly from the consumer (datamesh's version
 * persists via the Panache active-record entity directly; this service uses
 * the repository pattern — see {@link NotificationRepository} — so the
 * dedupe-then-insert logic lives in exactly one place, the service, shared
 * conceptually with how a future synchronous write path would use it).
 * Unlike datamesh (JSON here, not Avro+Apicurio — DRQ-038), and unlike
 * datamesh (this ch.17 extraction DOES build the outbox on the publishing
 * side; datamesh's producer does not).
 *
 * <p>The {@code order-placed} channel binds to the SHARED,
 * partition-balanced {@code notification-service} consumer group (defaulted
 * from {@code quarkus.application.name} — see {@code application.properties}),
 * so across a replica set exactly one replica persists any given event. The
 * independent WebSocket fan-out path lives in {@link OrderPlacedPushConsumer},
 * a second consumer of the SAME topic via a per-replica UNIQUE group, exactly
 * as in datamesh.
 */
@ApplicationScoped
public class OrderPlacedConsumer {

    private static final Logger LOG = Logger.getLogger(OrderPlacedConsumer.class);

    private final NotificationService service;

    public OrderPlacedConsumer(NotificationService service) {
        this.service = service;
    }

    @Incoming("order-placed")
    public void consume(OrderPlacedEvent event) {
        service.recordOrderPlaced(event);
        LOG.infof("consumed order.placed for order %d (customer %d)", event.orderId(), event.customerId());
    }
}
