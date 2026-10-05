package dev.patterncatalyst.shipping;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * r07/ch.24 S5 (DRQ-059) -- the orchestrated saga's trigger. No Spring
 * original to lift (DRQ-063): authored directly on SmallRye Reactive
 * Messaging / Quarkus idioms, the same "idiomatic-from-the-start" template
 * every other-service consumer in this repo uses (notification's/payment's
 * {@code OrderPlacedConsumer}): a thin consumer that delegates the
 * transactional/idempotent decision to
 * {@link ShippingService#processPaymentCaptured(PaymentCaptured)} rather
 * than doing it inline.
 *
 * <p>Binds to the SHARED, partition-balanced {@code shipping-service}
 * consumer group (defaulted from {@code quarkus.application.name} -- see
 * {@code application.properties}), so across a replica set exactly one
 * replica runs the saga for any given order.
 */
@ApplicationScoped
public class PaymentCapturedConsumer {

    private static final Logger LOG = Logger.getLogger(PaymentCapturedConsumer.class);

    private final ShippingService service;

    public PaymentCapturedConsumer(ShippingService service) {
        this.service = service;
    }

    /**
     * {@code @ActivateRequestContext} (standard CDI 4+,
     * {@code jakarta.enterprise.context.control}): this method runs off a
     * Reactive Messaging worker thread with NO CDI request context active
     * by default. Unlike a plain {@code @Transactional} method (which
     * activates one as a side effect of starting its JTA transaction),
     * {@link ShippingService#processPaymentCaptured} is deliberately NOT
     * {@code @Transactional} itself -- each saga step owns its own
     * transaction boundary (see {@link ShipmentSagaSteps}'s class javadoc)
     * -- so without this annotation the idempotency guard's Panache
     * repository call throws {@code ContextNotActiveException}. This
     * activates ONLY the request context, not a transaction; each
     * downstream {@code @Transactional} saga step still runs its own
     * independent, separately-committed transaction.
     */
    @Incoming("payment-captured")
    @ActivateRequestContext
    public void consume(PaymentCaptured event) {
        service.processPaymentCaptured(event);
        LOG.infof("consumed payment.captured for order %d", event.orderId());
    }
}
