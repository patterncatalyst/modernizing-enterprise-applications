package dev.patterncatalyst.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit), mirrors the monolith's {@code PaymentServiceTest}
 * byte-for-byte in intent: {@code charge}'s deterministic CARD-DECLINE demo
 * rule is the behavior worth proving in isolation. The only change from the
 * monolith's original is the fixture -- {@code charge} now takes a plain
 * {@code orderId} value instead of an {@code Order} entity (FK decomposed,
 * see {@link Payment}'s javadoc), so no {@code Order}/{@code Customer}
 * fixture setup is needed at all.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository repository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(repository);
    }

    @Test
    void charge_normalMethod_capturesAndSavesPayment() {
        when(repository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = paymentService.charge(1L, 3998L, "CARD-VISA");

        assertThat(payment.getOrderId()).isEqualTo(1L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getAmountCents()).isEqualTo(3998L);
        assertThat(payment.getMethod()).isEqualTo("CARD-VISA");
        verify(repository).save(any(Payment.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD-DECLINE", "card-decline", "DECLINE-ANYTHING", "visa-Decline-test"})
    void charge_methodContainingDecline_throwsPaymentDeclinedExceptionAndNeverSaves(String method) {
        assertThatThrownBy(() -> paymentService.charge(1L, 1999L, method))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining(method);

        verify(repository, never()).save(any());
    }

    @Test
    void getById_unknown_throwsResourceNotFoundException() {
        when(repository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> paymentService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }
}
