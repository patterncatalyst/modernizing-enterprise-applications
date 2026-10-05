package dev.patterncatalyst.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit), REFACTORED for r07/ch.24 S5 (Phase B). The read paths
 * ({@link ShippingService#getById(Long)}/{@link ShippingService#listByOrderId(Long)})
 * are retested against the Panache repository's {@code findByIdOptional}
 * shape (Spring Data's {@code findById}, unchanged return contract). Net-new
 * cases cover {@link ShippingService#processPaymentCaptured}'s idempotency
 * guard (DRQ-064) at the unit level with a mocked {@link ProducerTemplate}
 * -- the real Camel Saga EIP route (enrich/dispatch/book-carrier/emit/
 * compensate) is covered end-to-end by {@code PaymentCapturedConsumerTest}
 * and the route-level AdviceWith test ({@code ShipmentSagaRouteAdviceWithTest}).
 */
@ExtendWith(MockitoExtension.class)
class ShippingServiceTest {

    @Mock
    private ShipmentRepository repository;

    @Mock
    private ProducerTemplate producerTemplate;

    private ShippingService shippingService;

    @BeforeEach
    void setUp() {
        shippingService = new ShippingService(repository, producerTemplate);
    }

    @Test
    void getById_found_returnsShipmentDtoShape() {
        Shipment shipment = new Shipment(1L, "1 Analytical Engine Way, London", ShipmentStatus.DISPATCHED);
        setId(shipment, 7L);
        when(repository.findByIdOptional(7L)).thenReturn(Optional.of(shipment));

        ShipmentDto dto = shippingService.getById(7L);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.orderId()).isEqualTo(1L);
        assertThat(dto.address()).isEqualTo("1 Analytical Engine Way, London");
        assertThat(dto.status()).isEqualTo(ShipmentStatus.DISPATCHED);
        assertThat(dto.createdAt()).isNotNull();
    }

    @Test
    void getById_unknown_throwsResourceNotFoundException() {
        when(repository.findByIdOptional(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shippingService.getById(999_999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No shipment with id 999999");
    }

    @Test
    void listByOrderId_existingOrder_returnsDtoList() {
        Shipment shipment = new Shipment(42L, "221B Baker Street, London", ShipmentStatus.DISPATCHED);
        setId(shipment, 9L);
        when(repository.findAllByOrderId(42L)).thenReturn(List.of(shipment));

        List<ShipmentDto> dtos = shippingService.listByOrderId(42L);

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).orderId()).isEqualTo(42L);
        assertThat(dtos.get(0).status()).isEqualTo(ShipmentStatus.DISPATCHED);
    }

    @Test
    void listByOrderId_unknownOrder_returnsEmptyList() {
        when(repository.findAllByOrderId(anyLong())).thenReturn(List.of());

        assertThat(shippingService.listByOrderId(1_000_000L)).isEmpty();
    }

    @Test
    void processPaymentCaptured_newOrder_startsSagaViaProducerTemplate() {
        when(repository.findByOrderId(700L)).thenReturn(Optional.empty());
        PaymentCaptured event = new PaymentCaptured(700L, 1L, 1999L, "CARD-VISA", "CAPTURED", Instant.now());

        shippingService.processPaymentCaptured(event);

        verify(producerTemplate).sendBodyAndHeader(eq("direct:ship-start"), eq(event), eq("orderId"), eq(700L));
    }

    @Test
    void processPaymentCaptured_duplicateOrderId_isIdempotentNoOp_doesNotStartSaga() {
        Shipment existing = new Shipment(701L, "1 Analytical Engine Way, London", ShipmentStatus.DISPATCHED);
        when(repository.findByOrderId(701L)).thenReturn(Optional.of(existing));
        PaymentCaptured event = new PaymentCaptured(701L, 2L, 1999L, "CARD-VISA", "CAPTURED", Instant.now());

        shippingService.processPaymentCaptured(event);

        verify(producerTemplate, never()).sendBodyAndHeader(anyString(), any(), anyString(), any());
    }

    @Test
    void processPaymentCaptured_sagaAborts_doesNotPropagateException() {
        when(repository.findByOrderId(702L)).thenReturn(Optional.empty());
        PaymentCaptured event = new PaymentCaptured(702L, 3L, 1999L, "SHIP-FAIL", "CAPTURED", Instant.now());
        doThrow(new RuntimeException("saga compensated"))
                .when(producerTemplate)
                .sendBodyAndHeader(eq("direct:ship-start"), eq(event), eq("orderId"), eq(702L));

        assertThatCode(() -> shippingService.processPaymentCaptured(event)).doesNotThrowAnyException();
    }

    /** Reflection helper: {@code id} is JPA-generated, no public setter. */
    private static void setId(Shipment shipment, Long id) {
        try {
            var field = Shipment.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(shipment, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
