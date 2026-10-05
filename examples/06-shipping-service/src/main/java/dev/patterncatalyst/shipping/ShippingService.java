package dev.patterncatalyst.shipping;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

/**
 * REFACTORED to idiomatic Quarkus (r07/ch.24 S5, Phase B, DRQ-063). The
 * Spring {@code @Service} stereotype is dropped -- plain CDI
 * {@code @ApplicationScoped} plus Quarkus's simplified constructor
 * injection, mirroring payment-service's Phase B {@code PaymentService}.
 *
 * <p>{@link #getById(Long)}/{@link #listByOrderId(Long)} are the lifted
 * read paths -- unchanged behavior from Phase A, only the repository call
 * shape changed (Panache instead of Spring Data).
 *
 * <p>{@link #processPaymentCaptured(PaymentCaptured)} is net-new for S5
 * (DRQ-063: no Spring original to lift, authored idiomatic-from-the-start)
 * -- the orchestrated saga's entry point, mirroring payment's {@code
 * processOrderPlaced} shape for the ONE guarantee that belongs at this
 * layer (not inside the Camel route): idempotency (DRQ-064). Everything
 * downstream of "should a saga run at all for this order" -- enrich,
 * dispatch, book carrier, emit, compensate -- is delegated to the Camel
 * Saga EIP coordinator ({@code ShipmentSagaRoute}/{@code ShipmentSagaSteps}),
 * the deliberate ORCHESTRATED contrast to payment's choreography (where a
 * single {@code @Transactional} service method did everything in one
 * place).
 */
@ApplicationScoped
public class ShippingService {

    private static final Logger LOG = Logger.getLogger(ShippingService.class);

    /** Entry point of the Camel Saga EIP coordinator (DRQ-059). */
    static final String SAGA_START_ENDPOINT = "direct:ship-start";

    static final String ORDER_ID_HEADER = "orderId";

    private final ShipmentRepository repository;
    private final ProducerTemplate producerTemplate;

    public ShippingService(ShipmentRepository repository, ProducerTemplate producerTemplate) {
        this.repository = repository;
        this.producerTemplate = producerTemplate;
    }

    public ShipmentDto getById(Long id) {
        return toDto(repository.findByIdOptional(id)
                .orElseThrow(() -> new ResourceNotFoundException("No shipment with id " + id)));
    }

    public List<ShipmentDto> listByOrderId(Long orderId) {
        return repository.findAllByOrderId(orderId).stream().map(ShippingService::toDto).toList();
    }

    /**
     * The orchestrated-saga trigger (DRQ-059/DRQ-064): consumes one
     * {@code payment.captured} event and either (a) starts the Camel Saga
     * EIP coordinator for this order, or (b) no-ops if a shipment already
     * exists for it.
     *
     * <p><b>Idempotent by {@code orderId} (DRQ-064):</b> {@code
     * PaymentCapturedConsumer}'s shared Kafka consumer group makes a
     * redelivery of the same {@code payment.captured} event a real
     * possibility (at-least-once). A check-then-act lookup here makes that
     * redelivery a safe no-op -- it neither starts a second saga run nor
     * creates a second {@link Shipment}. The database-level
     * {@code uq_shipments_order_id} unique constraint
     * ({@code V3__shipments_unique_order_id.sql}) is the backstop for the
     * (currently unlikely, single-partition-consumer) case of two
     * deliveries racing concurrently -- same documented limitation as
     * payment's {@code uq_payments_order_id} (DRQ-051).
     *
     * <p>The saga itself is invoked SYNCHRONOUSLY via {@link ProducerTemplate}
     * on the calling (Reactive Messaging) thread -- deliberately NOT
     * wrapped in {@code @Transactional} here: each saga step
     * ({@code ShipmentSagaSteps}) owns its OWN local transaction boundary
     * (dispatch / emit / compensate), which is what lets the compensation
     * leg see the dispatch step's already-committed {@link Shipment} row.
     * A saga that compensates (the deterministic {@code SHIP-FAIL} path,
     * or a timeout) re-throws its original failure back to this caller
     * AFTER compensation has already run and emitted {@code
     * shipment.failed} via the outbox -- that is an expected, handled
     * business outcome, not a consumer processing error, so it is caught
     * and logged here rather than propagated (which would otherwise cause
     * Reactive Messaging to treat a deterministic {@code SHIP-FAIL} demo
     * order as a transient failure and redeliver it forever).
     *
     * <p>Requires an active CDI request context to run the Panache
     * repository lookup below (this method is deliberately NOT {@code
     * @Transactional} itself -- each saga step owns its own transaction
     * boundary, see above). {@link PaymentCapturedConsumer#consume} is
     * annotated {@code @ActivateRequestContext} for exactly this reason --
     * see that method's javadoc.
     */
    public void processPaymentCaptured(PaymentCaptured event) {
        if (repository.findByOrderId(event.orderId()).isPresent()) {
            LOG.infof("skipping duplicate payment.captured for order %d (shipment already recorded)",
                    event.orderId());
            return;
        }
        try {
            producerTemplate.sendBodyAndHeader(SAGA_START_ENDPOINT, event, ORDER_ID_HEADER, event.orderId());
            LOG.infof("shipping saga completed (dispatched) for order %d", event.orderId());
        } catch (RuntimeException sagaAborted) {
            // The saga's compensation route (direct:ship-compensate) has
            // already run to completion and emitted shipment.failed via
            // the outbox by the time this exception reaches us (Camel's
            // SagaProcessor compensates THEN re-throws the original
            // failure to the caller) -- see this method's javadoc.
            LOG.infof("shipping saga compensated (aborted) for order %d: %s",
                    event.orderId(), sagaAborted.getMessage());
        }
    }

    private static ShipmentDto toDto(Shipment s) {
        return new ShipmentDto(s.getId(), s.getOrderId(), s.getAddress(), s.getStatus(), s.getCreatedAt());
    }
}
