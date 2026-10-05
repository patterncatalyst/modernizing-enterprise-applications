package dev.patterncatalyst.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UPDATED for ch.17 Phase B (DRQ-029/DRQ-035): still a plain Mockito unit
 * test with no Quarkus/CDI context, mirroring review-service's
 * {@code ReviewServiceTest}. {@link NotificationRepository} is now a Panache
 * repository, so mocking it is unchanged in spirit (Mockito mocks the
 * concrete class) but the persist call is {@code void} (Panache's
 * {@code persist(entity)}, not Spring Data's {@code save(entity)} returning
 * the saved instance).
 *
 * <p>Also covers {@link NotificationService#recordOrderPlaced(OrderPlacedEvent)}
 * — net-new for S5, no Spring original to lift (DRQ-035) — at the unit level:
 * the idempotent-write decision (skip if {@code findByOrderId} already
 * returns a row) is exercised here without needing a real database; the
 * database-backed proof that redelivery is a true no-op lives in
 * {@code OrderPlacedConsumerTest} (feeds the event through the real
 * Reactive Messaging pipeline).
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository repository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(repository);
    }

    @Test
    void listByCustomerId_mapsEntitiesToDtos() {
        Instant sentAt = Instant.parse("2026-01-07T12:00:15Z");
        Notification notification = new Notification(1L, 1L, "EMAIL", "Order #1 confirmed, total $39.98");
        when(repository.findAllByCustomerId(1L)).thenReturn(List.of(notification));

        List<NotificationDto> dtos = notificationService.listByCustomerId(1L);

        assertThat(dtos).hasSize(1);
        NotificationDto dto = dtos.get(0);
        assertThat(dto.customerId()).isEqualTo(1L);
        assertThat(dto.orderId()).isEqualTo(1L);
        assertThat(dto.channel()).isEqualTo("EMAIL");
        assertThat(dto.message()).isEqualTo("Order #1 confirmed, total $39.98");
        assertThat(dto.sentAt()).isAfterOrEqualTo(sentAt.minusSeconds(60));
    }

    @Test
    void listByCustomerId_noNotifications_returnsEmptyList() {
        when(repository.findAllByCustomerId(404L)).thenReturn(List.of());

        List<NotificationDto> dtos = notificationService.listByCustomerId(404L);

        assertThat(dtos).isEmpty();
    }

    @Test
    void recordOrderPlaced_newOrder_persistsNotification() {
        OrderPlacedEvent event = new OrderPlacedEvent(
                55L, 9L, "customer9@example.com", 1999, "Order #55 confirmed, total $19.99", Instant.now());
        when(repository.findByOrderId(55L)).thenReturn(null);

        notificationService.recordOrderPlaced(event);

        verify(repository).persist(org.mockito.ArgumentMatchers.argThat((Notification n) ->
                n.getCustomerId().equals(9L)
                        && n.getOrderId().equals(55L)
                        && n.getChannel().equals("EMAIL")
                        && n.getMessage().equals("Order #55 confirmed, total $19.99")));
    }

    @Test
    void recordOrderPlaced_duplicateOrder_isNoOp() {
        OrderPlacedEvent event = new OrderPlacedEvent(
                56L, 9L, "customer9@example.com", 1999, "Order #56 confirmed, total $19.99", Instant.now());
        when(repository.findByOrderId(56L))
                .thenReturn(new Notification(9L, 56L, "EMAIL", "Order #56 confirmed, total $19.99"));

        notificationService.recordOrderPlaced(event);

        verify(repository, org.mockito.Mockito.never()).persist(org.mockito.ArgumentMatchers.any(Notification.class));
    }
}
