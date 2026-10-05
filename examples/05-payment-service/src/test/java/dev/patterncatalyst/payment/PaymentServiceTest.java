package dev.patterncatalyst.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit), REFACTORED for r06/ch.23 S5 (Phase B). {@link
 * PaymentService#charge} is retested for its REVISED contract (see that
 * method's javadoc): the same deterministic CARD-DECLINE demo rule now
 * returns a transient DECLINED {@link Payment} instead of throwing {@code
 * PaymentDeclinedException} (deleted -- nothing maps it to an HTTP status
 * anymore, since the choreography emits an event rather than propagating an
 * exception to a caller). Net-new cases cover {@link
 * PaymentService#processOrderPlaced}'s idempotency (DRQ-051) and outbox-write
 * (DRQ-053) behavior at the unit level with mocked collaborators; the full
 * Reactive Messaging + real-outbox-row round trip is covered by {@link
 * OrderPlacedConsumerTest}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository repository;

    @Mock
    private PaymentOutboxRepository outboxRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(repository, outboxRepository, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void charge_normalMethod_returnsTransientCapturedPayment() {
        Payment payment = paymentService.charge(1L, 3998L, "CARD-VISA");

        assertThat(payment.getOrderId()).isEqualTo(1L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getAmountCents()).isEqualTo(3998L);
        assertThat(payment.getMethod()).isEqualTo("CARD-VISA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD-DECLINE", "card-decline", "DECLINE-ANYTHING", "visa-Decline-test"})
    void charge_methodContainingDecline_returnsTransientDeclinedPayment_neverThrows(String method) {
        Payment payment = paymentService.charge(1L, 1999L, method);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payment.getMethod()).isEqualTo(method);
    }

    @Test
    void charge_nullMethod_substitutesUnspecifiedMethod_capturesPayment() {
        // Forward-compatibility case (see OrderPlacedEvent's javadoc): today's
        // monolith order.placed payload does not carry a payment method, and
        // Payment#method is a NOT NULL column -- null must not reach it.
        Payment payment = paymentService.charge(1L, 1000L, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getMethod()).isEqualTo("CARD-UNSPECIFIED");
    }

    @Test
    void getById_unknown_throwsResourceNotFoundException() {
        when(repository.findByIdOptional(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void processOrderPlaced_normalMethod_persistsCapturedPayment_andPaymentCapturedOutboxEvent() {
        when(repository.findByOrderId(500L)).thenReturn(null);

        paymentService.processOrderPlaced(
                new OrderPlacedEvent(500L, 500L, "e@example.com", 2500L, "CARD-VISA", "msg", Instant.now()));

        verify(repository)
                .persist(argThat((Payment p) -> p.getStatus() == PaymentStatus.CAPTURED && p.getOrderId().equals(500L)));
        verify(outboxRepository)
                .persist(argThat((PaymentOutboxEvent e) -> e.getEventType().equals("payment.captured")
                        && e.getAggregateId().equals("500")
                        && e.getPayload().contains("\"status\":\"CAPTURED\"")));
    }

    @Test
    void processOrderPlaced_declineMethod_persistsDeclinedPayment_andPaymentDeclinedOutboxEvent_noFundsCaptured() {
        when(repository.findByOrderId(501L)).thenReturn(null);

        paymentService.processOrderPlaced(
                new OrderPlacedEvent(501L, 501L, "e@example.com", 1500L, "CARD-DECLINE", "msg", Instant.now()));

        verify(repository).persist(argThat((Payment p) -> p.getStatus() == PaymentStatus.DECLINED));
        verify(outboxRepository)
                .persist(argThat((PaymentOutboxEvent e) -> e.getEventType().equals("payment.declined")
                        && e.getPayload().contains("\"status\":\"DECLINED\"")));
    }

    @Test
    void processOrderPlaced_duplicateOrderId_isIdempotentNoOp_doesNotDoubleChargeOrEmit() {
        Payment existing = new Payment(502L, 999L, "CARD-VISA", PaymentStatus.CAPTURED);
        when(repository.findByOrderId(502L)).thenReturn(existing);

        paymentService.processOrderPlaced(
                new OrderPlacedEvent(502L, 502L, "e@example.com", 999L, "CARD-VISA", "msg", Instant.now()));

        verify(repository, never()).persist(any(Payment.class));
        verify(outboxRepository, never()).persist(any(PaymentOutboxEvent.class));
    }
}
