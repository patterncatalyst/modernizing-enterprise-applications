package dev.patterncatalyst.monolith.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.exception.PaymentDeclinedException;
import dev.patterncatalyst.monolith.order.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit): {@code PaymentService#charge}'s demo decline rule (any method
 * containing "DECLINE", case-insensitively) is the branch {@code OrderService}'s
 * payment-declined scenario exercises — this is the real logic worth a unit
 * test, independent of the god-service orchestration tested in
 * {@code OrderServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository repository;

    private PaymentService paymentService;
    private Order order;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(repository);
        order = new Order(new Customer("Ada Lovelace", "ada@example.com"), "1 Test Way");
    }

    @Test
    void charge_normalMethod_capturesAndSavesPayment() {
        when(repository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = paymentService.charge(order, 3998L, "CARD-VISA");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getAmountCents()).isEqualTo(3998L);
        assertThat(payment.getMethod()).isEqualTo("CARD-VISA");
        verify(repository).save(any(Payment.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD-DECLINE", "card-decline", "DECLINE-ANYTHING", "visa-Decline-test"})
    void charge_methodContainingDecline_throwsPaymentDeclinedExceptionAndNeverSaves(String method) {
        assertThatThrownBy(() -> paymentService.charge(order, 1999L, method))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining(method);

        verify(repository, never()).save(any());
    }
}
