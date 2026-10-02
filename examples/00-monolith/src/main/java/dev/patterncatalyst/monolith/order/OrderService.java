package dev.patterncatalyst.monolith.order;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.CustomerRepository;
import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import dev.patterncatalyst.monolith.inventory.InventoryService;
import dev.patterncatalyst.monolith.notification.NotificationService;
import dev.patterncatalyst.monolith.payment.PaymentService;
import dev.patterncatalyst.monolith.shipping.ShippingService;
import java.util.List;
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

    public OrderService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            InventoryService inventoryService,
            PaymentService paymentService,
            ShippingService shippingService,
            NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
        this.notificationService = notificationService;
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
     * back everything written so far) -> dispatch shipment (shipping) -> send the
     * confirmation notification SYNCHRONOUSLY inside this same transaction
     * (notification, SMELL[ch.17]).
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

        // SMELL[ch.17]: notification sent synchronously inside the checkout
        // transaction instead of via an outbox + async consumer.
        notificationService.sendOrderConfirmation(customer, order);

        return toDto(order);
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
