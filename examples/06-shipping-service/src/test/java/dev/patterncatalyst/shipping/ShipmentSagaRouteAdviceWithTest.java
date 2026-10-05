package dev.patterncatalyst.shipping;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import java.time.Instant;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

/**
 * r07/ch.24 S5 (DRQ-059/DRQ-062) -- a Camel AdviceWith/MockEndpoint unit
 * test of the saga ABORT path specifically: weaves a {@code mock:compensated}
 * endpoint onto the end of the {@code ship-compensate} route (without
 * touching its real behavior -- {@code weaveAddLast()} appends, it does not
 * replace), then drives the saga through its deterministic {@code
 * SHIP-FAIL} abort and asserts the compensation route is invoked EXACTLY
 * ONCE. This is independent of {@link PaymentCapturedConsumerTest} (which
 * proves the abort path's END STATE via the database/outbox) -- this test
 * proves the ROUTE ITSELF is what the coordinator reaches on abort, at the
 * Camel level.
 */
@QuarkusTest
class ShipmentSagaRouteAdviceWithTest {

    @Inject
    CamelContext camelContext;

    @Inject
    ProducerTemplate producerTemplate;

    @InjectMock
    OrderReadClient orderReadClient;

    @Test
    void shipFailAddress_abortsSaga_invokesCompensationRouteExactlyOnce() throws Exception {
        AdviceWith.adviceWith(camelContext, "ship-compensate",
                advice -> advice.weaveAddLast().to("mock:compensated"));

        MockEndpoint mockCompensated = camelContext.getEndpoint("mock:compensated", MockEndpoint.class);
        mockCompensated.expectedMessageCount(1);

        Long orderId = 904L;
        when(orderReadClient.fetchShippingAddress(eq(orderId))).thenReturn("SHIP-FAIL");
        PaymentCaptured event = new PaymentCaptured(orderId, 4L, 2500L, "CARD-VISA", "CAPTURED", Instant.now());

        try {
            producerTemplate.sendBodyAndHeader("direct:ship-start", event, "orderId", orderId);
        } catch (RuntimeException expectedAfterCompensation) {
            // Camel's SagaProcessor compensates FIRST, then re-propagates the
            // original ShipFailException to the caller -- expected here,
            // mirrors ShippingService#processPaymentCaptured's own handling.
        }

        mockCompensated.assertIsSatisfied(5_000);
    }
}
