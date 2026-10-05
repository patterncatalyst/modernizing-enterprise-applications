package dev.patterncatalyst.payment;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * NET-NEW for r06/ch.23 S5 (DRQ-052) -- the monolith's synchronous {@code
 * OrderService} never consumed its own {@code order.placed} event (it only
 * ever charged in-process), so there is no Spring original to lift here.
 * Authored directly on SmallRye Reactive Messaging / Quarkus idioms, the same
 * "idiomatic-from-the-start" template notification-service's {@code
 * OrderPlacedConsumer} used for ch.17/S5 (DRQ-035): a thin consumer that
 * delegates the transactional, idempotent work to {@link
 * PaymentService#processOrderPlaced(OrderPlacedEvent)} rather than doing it
 * inline, so there is exactly one place that decides "have I already charged
 * this order" and "what do I write to the outbox."
 *
 * <p>Binds to the SHARED, partition-balanced {@code payment-service} consumer
 * group (defaulted from {@code quarkus.application.name} -- see {@code
 * application.properties}), so across a replica set exactly one replica
 * charges any given order.
 */
@ApplicationScoped
public class OrderPlacedConsumer {

    private static final Logger LOG = Logger.getLogger(OrderPlacedConsumer.class);

    private final PaymentService service;

    public OrderPlacedConsumer(PaymentService service) {
        this.service = service;
    }

    @Incoming("order-placed")
    public void consume(OrderPlacedEvent event) {
        service.processOrderPlaced(event);
        LOG.infof("consumed order.placed for order %d (amount=%d)", event.orderId(), event.totalCents());
    }
}
