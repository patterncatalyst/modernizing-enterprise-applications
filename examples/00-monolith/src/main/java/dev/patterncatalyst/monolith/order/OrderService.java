package dev.patterncatalyst.monolith.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.CustomerRepository;
import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.Topics;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.common.outbox.OrderPlacedEvent;
import dev.patterncatalyst.monolith.common.outbox.OutboxEvent;
import dev.patterncatalyst.monolith.common.outbox.OutboxRepository;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import dev.patterncatalyst.monolith.inventory.InventoryService;
import dev.patterncatalyst.monolith.notification.NotificationService;
import dev.patterncatalyst.monolith.payment.PaymentService;
import dev.patterncatalyst.monolith.shipping.ShippingService;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SMELL[ch.26]: this is the "god service" — it is the single orchestration point
 * for checkout and it reaches directly into inventory, payment, shipping, and
 * notification's services/repositories/entities instead of those contexts being
 * independently deployable collaborators reached over a stable contract. Order is
 * deliberately the HARDEST and LAST extraction in the roadmap (ch.26) precisely
 * because every other context's extraction has to first remove one of this
 * service's direct dependencies.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final NotificationService notificationService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * ch.17 (r04/S3, DRQ-036) — the reversibility flag. {@code synchronous}
     * (the default) is today's unchanged behavior: {@code placeOrder} calls
     * {@code notificationService.sendOrderConfirmation} directly, in-process,
     * inside this transaction (SMELL[ch.17]). {@code outbox} instead writes an
     * {@code order.placed} event row to the transactional outbox — atomically,
     * in the SAME transaction — and skips the synchronous call; a separate
     * {@code @Scheduled} relay (common.outbox.OutboxRelay) publishes it to
     * Kafka later. Both code paths are kept side by side on purpose: this is
     * what makes the cutover (S7) reversible by flipping one property, and
     * what lets S2's equivalence suite prove zero regression before S3's
     * behavior change ships.
     */
    private final String notificationMode;

    public OrderService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            InventoryService inventoryService,
            PaymentService paymentService,
            ShippingService shippingService,
            NotificationService notificationService,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            @Value("${notification.mode:synchronous}") String notificationMode) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
        this.notificationService = notificationService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.notificationMode = notificationMode;
    }

    /**
     * SMELL[ch.22]: one in-process ACID {@code @Transactional} spans FIVE bounded
     * contexts — order, inventory, payment, shipping, notification. It "works"
     * today because Postgres gives us atomic rollback for free across all of them.
     * The moment any one of these becomes its own service with its own database,
     * this rollback-everything behavior disappears and has to be rebuilt
     * explicitly as a saga with compensating actions (ch.23 choreographed,
     * ch.24 orchestrated) — that is the ACID -> ACD story told in ch.22.
     *
     * <p>Flow: validate customer -> check+reserve stock (inventory, SMELL[ch.16]
     * no ACL) -> persist the order -> charge payment (payment; a decline rolls
     * back everything written so far) -> dispatch shipment (shipping) -> notify.
     *
     * <p>The notification step is flag-gated (DRQ-036,
     * {@code notification.mode}): {@code synchronous} (default) still sends
     * the confirmation SYNCHRONOUSLY inside this same transaction
     * (notification, SMELL[ch.17] — unchanged, uncured behavior); {@code
     * outbox} instead writes an {@code order.placed} outbox row atomically in
     * this same transaction (ch.17's cure, S3) and lets a separate
     * {@code @Scheduled} relay publish it to Kafka asynchronously.
     */
    @Transactional
    public OrderDto placeOrder(OrderCreate command) {
        Customer customer = customerRepository.findById(command.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));

        Order order = new Order(customer, command.shippingAddress());

        // SMELL[ch.16]: reaching directly into inventory's entities/repository from
        // the order context, with no anti-corruption layer at the seam.
        for (OrderCreate.Line line : command.items()) {
            InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
            inventoryService.reserve(line.sku(), line.quantity()); // throws InsufficientStockException, no writes yet
            order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
        }

        order = orderRepository.save(order);

        // SMELL[ch.22]/[ch.26]: payment captured synchronously, in-process, inside
        // the same transaction as the inventory decrement above. A decline here
        // rolls back the inventory reservation too (see PaymentDeclinedException).
        paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
        order.confirm();

        // SMELL[ch.26]: shipping dispatched synchronously from the order's god
        // service rather than being triggered by an event the order context emits.
        shippingService.dispatch(order, order.getShippingAddress());

        if ("outbox".equals(notificationMode)) {
            // ch.17 cure (S3): write the event to the outbox, atomically, in
            // THIS transaction. No Kafka client is touched here — the
            // @Scheduled OutboxRelay is the only thing that talks to Kafka,
            // on its own schedule, after this transaction has committed (or
            // not, if something downstream still throws).
            writeOrderPlacedOutboxEvent(customer, order);
        } else {
            // SMELL[ch.17]: notification sent synchronously inside the
            // checkout transaction instead of via an outbox + async consumer.
            // This is the default (notification.mode=synchronous) and
            // remains today's unchanged, uncured behavior.
            notificationService.sendOrderConfirmation(customer, order);
        }

        return toDto(order);
    }

    /**
     * ch.17 (r04/S3): builds the {@code order.placed} payload and persists it
     * as an {@link OutboxEvent} row via the same {@link OutboxRepository} used
     * by {@link dev.patterncatalyst.monolith.common.outbox.OutboxRelay} —
     * called from inside {@link #placeOrder}'s {@code @Transactional}, so this
     * write is part of the checkout transaction, not a separate dual-write
     * after commit.
     */
    private void writeOrderPlacedOutboxEvent(Customer customer, Order order) {
        String confirmationMessage =
                "Order #%d confirmed, total $%.2f".formatted(order.getId(), order.getTotalCents() / 100.0);
        OrderPlacedEvent event = new OrderPlacedEvent(
                order.getId(),
                customer.getId(),
                customer.getEmail(),
                order.getTotalCents(),
                confirmationMessage,
                Instant.now());
        try {
            String payload = objectMapper.writeValueAsString(event);
            outboxRepository.save(new OutboxEvent(
                    "Order", String.valueOf(order.getId()), Topics.ORDER_PLACED, payload));
        } catch (JsonProcessingException e) {
            // A record of primitives/Strings/Instant never fails to
            // serialize in practice; wrapping keeps placeOrder's signature
            // unchanged and still fails the checkout (and rolls back the
            // transaction) rather than silently dropping the notification.
            throw new IllegalStateException("Failed to serialize OrderPlacedEvent for order " + order.getId(), e);
        }
    }

    @Transactional(readOnly = true)
    public OrderDto getById(Long id) {
        return toDto(orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No order with id " + id)));
    }

    @Transactional(readOnly = true)
    public List<OrderDto> listAll() {
        return orderRepository.findAll().stream().map(OrderService::toDto).toList();
    }

    private static OrderDto toDto(Order order) {
        List<OrderDto.Item> items = order.getItems().stream()
                .map(i -> new OrderDto.Item(i.getInventoryItem().getSku(), i.getQuantity(), i.getUnitPriceCents()))
                .toList();
        return new OrderDto(
                order.getId(),
                order.getCustomer().getId(),
                order.getStatus(),
                order.getTotalCents(),
                order.getCreatedAt(),
                items);
    }
}
