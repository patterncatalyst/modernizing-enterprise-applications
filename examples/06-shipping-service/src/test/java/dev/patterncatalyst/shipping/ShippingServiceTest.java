package dev.patterncatalyst.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit). r07/ch.24 S4 (DRQ-063, Phase A). Mirrors
 * examples/05-payment-service's {@code PaymentServiceTest} style for the
 * lifted read paths: a plain Mockito test of {@link ShippingService}'s
 * {@link ShippingService#getById(Long)}/{@link ShippingService#listByOrderId(Long)}
 * against a mocked {@link ShipmentRepository}, proving the DTO mapping
 * (FK-decomposed {@code orderId} value, not a JPA relationship traversal)
 * and the 404 contract.
 */
@ExtendWith(MockitoExtension.class)
class ShippingServiceTest {

    @Mock
    private ShipmentRepository repository;

    private ShippingService shippingService;

    @BeforeEach
    void setUp() {
        shippingService = new ShippingService(repository);
    }

    @Test
    void getById_found_returnsShipmentDtoShape() {
        Instant createdAt = Instant.parse("2026-01-07T12:00:10Z");
        Shipment shipment = new Shipment(1L, "1 Analytical Engine Way, London", ShipmentStatus.DISPATCHED);
        setId(shipment, 7L);
        when(repository.findById(7L)).thenReturn(Optional.of(shipment));

        ShipmentDto dto = shippingService.getById(7L);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.orderId()).isEqualTo(1L);
        assertThat(dto.address()).isEqualTo("1 Analytical Engine Way, London");
        assertThat(dto.status()).isEqualTo(ShipmentStatus.DISPATCHED);
        assertThat(dto.createdAt()).isNotNull();
    }

    @Test
    void getById_unknown_throwsResourceNotFoundException() {
        when(repository.findById(anyLong())).thenReturn(Optional.empty());

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
