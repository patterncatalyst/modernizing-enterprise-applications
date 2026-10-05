package dev.patterncatalyst.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Plain Mockito unit test — no Spring/Quarkus context — mirroring
 * review-service's Phase A {@code ReviewServiceTest}. Covers the
 * {@code toDto} mapping adaptation documented in {@link Notification}'s
 * javadoc (plain {@code customerId}/{@code orderId} columns, not
 * {@code @ManyToOne} relations).
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
}
