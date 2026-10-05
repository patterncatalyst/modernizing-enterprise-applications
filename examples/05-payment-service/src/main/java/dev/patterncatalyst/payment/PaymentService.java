package dev.patterncatalyst.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * REFACTORED to idiomatic Quarkus (r06/ch.23 S5, Phase B, DRQ-052). The
 * Spring {@code @Service} stereotype is dropped -- plain CDI {@code
 * @ApplicationScoped} plus Quarkus's simplified constructor injection (a
 * single constructor needs no {@code @Inject}), exactly mirroring
 * notification-service's/review-service's Phase B services.
 *
 * <p>{@link #getById(Long)}/{@link #listByOrderId(Long)} are the lifted read
 * paths -- unchanged behavior from Phase A, only the repository call shape
 * changed (Panache instead of Spring Data).
 *
 * <p>{@link #charge(Long, long, String)} is REFACTORED from Phase A's
 * behavior, not merely relocated: Phase A threw {@link
 * PaymentDeclinedException} on the CARD-DECLINE demo rule and never built a
 * {@link Payment} at all (there was no caller to receive a declined outcome
 * except an HTTP 402, and nothing called it yet). This step's choreography
 * needs a DECLINED {@link Payment} row to exist too (so {@code /api/payments}
 * can show the declined attempt, and so {@link #processOrderPlaced} has a
 * {@link Payment} to pass to {@link #buildOutboxEvent}) -- so {@code charge}
 * now returns a transient {@link Payment} with the CAPTURED or DECLINED
 * status decided by the SAME deterministic demo rule, and never throws. The
 * monolith's in-process synchronous 402 behavior does not carry over to the
 * choreography (DRQ-047) -- a decline is now an emitted {@code
 * payment.declined} event, not an exception propagated to an HTTP caller.
 *
 * <p>{@link #processOrderPlaced(OrderPlacedEvent)} is net-new for S5 (DRQ-052:
 * no Spring original to lift, authored idiomatic-from-the-start) -- the
 * choreography's capture-then-emit core. It is the single owner of BOTH
 * guarantees this step's acceptance criteria calls out: idempotency
 * (DRQ-051) and outbox atomicity (DRQ-053).
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class);

    /**
     * Aggregate type recorded on every {@link PaymentOutboxEvent} this
     * service writes -- mirrors the monolith outbox convention of tagging
     * each row with the bounded-context aggregate it describes.
     */
    static final String AGGREGATE_TYPE = "payment";

    static final String PAYMENT_CAPTURED_EVENT_TYPE = "payment.captured";
    static final String PAYMENT_DECLINED_EVENT_TYPE = "payment.declined";

    /**
     * Forward-compatibility fallback (see {@link OrderPlacedEvent}'s javadoc):
     * today's real monolith {@code order.placed} payload does not carry a
     * payment method at all (deferred to S6), so a live event deserializes
     * with {@code paymentMethod == null}. {@link Payment#method} is a
     * NOT NULL column ({@code V1__create_payments_table.sql}), so {@code
     * null} cannot be persisted verbatim -- this literal substitutes for it,
     * deliberately NOT containing {@code DECLINE}, so a method-less (today's
     * real) event always captures rather than silently declining.
     */
    static final String UNSPECIFIED_METHOD = "CARD-UNSPECIFIED";

    private final PaymentRepository repository;
    private final PaymentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentService(
            PaymentRepository repository, PaymentOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Deterministic demo decline rule (unchanged from Phase A/the monolith):
     * any {@code method} value containing {@code DECLINE}, case-insensitively,
     * is declined. A {@code null} method (the forward-compatibility case
     * documented on {@link OrderPlacedEvent} -- today's monolith payload does
     * not yet carry a payment method, deferred to S6) is substituted with
     * {@link #UNSPECIFIED_METHOD} -- treated as a normal, capturable method
     * (never declined) rather than failing the consumer or violating {@link
     * Payment#method}'s NOT NULL column.
     */
    public Payment charge(Long orderId, long amountCents, String method) {
        String effectiveMethod = method != null ? method : UNSPECIFIED_METHOD;
        PaymentStatus status = isDeclineMethod(effectiveMethod) ? PaymentStatus.DECLINED : PaymentStatus.CAPTURED;
        return new Payment(orderId, amountCents, effectiveMethod, status);
    }

    private static boolean isDeclineMethod(String method) {
        return method != null && method.toUpperCase(Locale.ROOT).contains("DECLINE");
    }

    public PaymentDto getById(Long id) {
        Payment payment = repository.findByIdOptional(id)
                .orElseThrow(() -> new ResourceNotFoundException("No payment with id " + id));
        return toDto(payment);
    }

    public List<PaymentDto> listByOrderId(Long orderId) {
        return repository.findAllByOrderId(orderId).stream().map(PaymentService::toDto).toList();
    }

    /**
     * The choreographed-saga core (DRQ-051 idempotency + DRQ-053 outbox
     * atomicity): consumes one {@code order.placed} event, captures (or
     * declines) the payment, and -- in this SAME transaction -- persists the
     * {@link Payment} row and writes the matching {@link PaymentOutboxEvent}
     * row. Either both writes commit or neither does; a crash between this
     * commit and {@link PaymentOutboxRelay} actually publishing to Kafka
     * cannot lose the event (it is durably recorded here first) or duplicate
     * the business effect (the relay only ever republishes, never re-charges).
     *
     * <p><b>Idempotent by {@code orderId} (DRQ-051):</b> the monolith's future
     * publisher of {@code order.placed} (S6) will be, like every outbox relay
     * in this repo, AT LEAST ONCE. A check-then-act lookup by {@code orderId}
     * makes a redelivered event a safe no-op -- it neither double-charges nor
     * emits a second outcome event. The database-level {@code
     * uq_payments_order_id} unique index ({@code V1__create_payments_table.sql})
     * is the backstop for the (currently unlikely, single-partition-consumer)
     * case of two deliveries racing concurrently.
     */
    @Transactional
    public void processOrderPlaced(OrderPlacedEvent event) {
        if (repository.findByOrderId(event.orderId()) != null) {
            LOG.infof("skipping duplicate order.placed for order %d (payment already recorded)", event.orderId());
            return;
        }

        Payment payment = charge(event.orderId(), event.totalCents(), event.paymentMethod());
        repository.persist(payment);

        PaymentOutboxEvent outboxEvent = buildOutboxEvent(payment);
        outboxRepository.persist(outboxEvent);

        LOG.infof(
                "processed order.placed for order %d -> payment %s (outbox event id=%s, type=%s)",
                event.orderId(), payment.getStatus(), outboxEvent.getId(), outboxEvent.getEventType());
    }

    private PaymentOutboxEvent buildOutboxEvent(Payment payment) {
        String eventType;
        String payload;
        Instant now = Instant.now();
        try {
            if (payment.getStatus() == PaymentStatus.CAPTURED) {
                eventType = PAYMENT_CAPTURED_EVENT_TYPE;
                payload = objectMapper.writeValueAsString(new PaymentCaptured(
                        payment.getOrderId(),
                        payment.getId(),
                        payment.getAmountCents(),
                        payment.getMethod(),
                        PaymentCaptured.STATUS,
                        now));
            } else {
                eventType = PAYMENT_DECLINED_EVENT_TYPE;
                payload = objectMapper.writeValueAsString(new PaymentDeclined(
                        payment.getOrderId(),
                        payment.getId(),
                        payment.getAmountCents(),
                        payment.getMethod(),
                        PaymentDeclined.STATUS,
                        "Payment method '%s' was declined".formatted(payment.getMethod()),
                        now));
            }
        } catch (JsonProcessingException e) {
            // The payload records contain only primitives/Strings/Instants --
            // this can only happen from a programming error, not bad runtime
            // data, so failing the transaction loudly is correct (no outbox
            // row with a half-serialized payload should ever be committed).
            throw new IllegalStateException("failed to serialize payment outbox payload", e);
        }
        return new PaymentOutboxEvent(AGGREGATE_TYPE, String.valueOf(payment.getOrderId()), eventType, payload);
    }

    private static PaymentDto toDto(Payment p) {
        return new PaymentDto(p.getId(), p.getOrderId(), p.getAmountCents(), p.getMethod(), p.getStatus(),
                p.getCreatedAt());
    }
}
