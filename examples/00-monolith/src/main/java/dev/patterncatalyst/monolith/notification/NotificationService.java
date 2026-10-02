package dev.patterncatalyst.monolith.notification;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.NotificationDto;
import dev.patterncatalyst.monolith.order.Order;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * SMELL[ch.17]: Sends the order-confirmation notification SYNCHRONOUSLY, as a
     * direct in-process call from {@code order.OrderService#placeOrder}, inside the
     * checkout's own {@code @Transactional}. Two problems this plants on purpose:
     * (1) checkout latency is now coupled to however long "sending" a notification
     * takes, even though the customer doesn't need to wait for it; (2) if sending
     * ever threw, it would roll back the whole order along with it. ch.17
     * (notification extraction) replaces this with a transactional outbox + an
     * async event-driven consumer, decoupling both latency and failure domains.
     */
    public Notification sendOrderConfirmation(Customer customer, Order order) {
        String message = "Order #%d confirmed, total $%.2f".formatted(order.getId(), order.getTotalCents() / 100.0);
        return repository.save(new Notification(customer, order, "EMAIL", message));
    }

    public List<NotificationDto> listByCustomerId(Long customerId) {
        return repository.findAllByCustomerId(customerId).stream().map(NotificationService::toDto).toList();
    }

    private static NotificationDto toDto(Notification n) {
        return new NotificationDto(
                n.getId(),
                n.getCustomer().getId(),
                n.getOrder() != null ? n.getOrder().getId() : null,
                n.getChannel(),
                n.getMessage(),
                n.getSentAt());
    }
}
