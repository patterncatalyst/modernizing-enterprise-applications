package dev.patterncatalyst.monolith.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.CustomerRepository;
import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.Topics;
import dev.patterncatalyst.monolith.common.exception.InsufficientStockException;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.common.outbox.OrderPlacedEvent;
import dev.patterncatalyst.monolith.common.outbox.OutboxEvent;
import dev.patterncatalyst.monolith.common.outbox.OutboxRepository;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import dev.patterncatalyst.monolith.inventory.InventoryService;
import dev.patterncatalyst.monolith.inventory.RemoteInventoryClient;
import dev.patterncatalyst.monolith.payment.PaymentService;
import dev.patterncatalyst.monolith.shipping.ShippingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SMELL[ch.26]: this is the "god service" — it is the single orchestration point
 * for checkout and it reaches directly into inventory, payment, and shipping's
 * services/repositories/entities instead of those contexts being independently
 * deployable collaborators reached over a stable contract. Order is deliberately
 * the HARDEST and LAST extraction in the roadmap (ch.26) precisely because every
 * other context's extraction has to first remove one of this service's direct
 * dependencies — notification's was the first to go (ch.17/r04/S8): checkout
 * now reaches only its own outbox, not a notification-context collaborator.
 */
@Service
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final InventoryService inventoryService;
    private final RemoteInventoryClient remoteInventoryClient;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final String inventoryMode;

    public OrderService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            InventoryService inventoryService,
            RemoteInventoryClient remoteInventoryClient,
            PaymentService paymentService,
            ShippingService shippingService,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            @Value("${inventory.mode:local}") String inventoryMode) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.inventoryService = inventoryService;
        this.remoteInventoryClient = remoteInventoryClient;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.inventoryMode = inventoryMode;
    }

    /**
     * SMELL[ch.22]: one in-process ACID {@code @Transactional} spans FOUR bounded
     * contexts — order, inventory, payment, shipping (plus this context's own
     * outbox table write; the outbox is order-owned infrastructure, not a
     * notification-context collaborator, so it doesn't widen this blast radius
     * the way the old synchronous notification call did). It "works" today
     * because Postgres gives us atomic rollback for free across all of them.
     * The moment any one of these becomes its own service with its own database,
     * this rollback-everything behavior disappears and has to be rebuilt
     * explicitly as a saga with compensating actions (ch.23 choreographed,
     * ch.24 orchestrated) — that is the ACID -> ACD story told in ch.22.
     * Notification (ch.17/r04/S8) was the first context removed from this
     * transaction's blast radius entirely.
     *
     * <p>Flow: validate customer -> check+reserve stock (inventory, SMELL[ch.16]
     * no ACL) -> persist the order -> charge payment (payment; a decline rolls
     * back everything written so far) -> dispatch shipment (shipping) -> write
     * the {@code order.placed} outbox event.
     *
     * <p>ch.17 (r04/S8) CURE: the notification step is no longer flag-gated —
     * the synchronous in-transaction call to a local NotificationService
     * (SMELL[ch.17]) has been removed entirely. {@code placeOrder} now always
     * writes an {@code order.placed} outbox row atomically in this same
     * transaction; a separate {@code @Scheduled} relay
     * ({@link dev.patterncatalyst.monolith.common.outbox.OutboxRelay})
     * publishes it to Kafka asynchronously, where
     * {@code examples/03-notification-service} consumes it and owns
     * notification delivery entirely outside this transaction's latency and
     * failure domain.
     *
     * <p>r05/ch.19 S7 (DRQ-039/DRQ-041/DRQ-042/DRQ-045): {@code
     * inventory.mode=local} (default) keeps the in-JVM reserve below exactly
     * as it always was — Postgres's own {@code @Transactional} rollback is
     * the "compensation" and nothing in this method's shape changes. {@code
     * inventory.mode=remote} routes the reserve through {@link
     * RemoteInventoryClient}'s gRPC {@code Reserve} against the extracted
     * inventory service's OWN database instead, which commits OUTSIDE this
     * {@code @Transactional} and therefore can no longer be undone by it; the
     * surrounding try/catch issues a compensating gRPC {@code Release} for
     * every sku this checkout successfully reserved remotely, on ANY failure
     * that happens afterward (insufficient stock on a later line, a payment
     * decline, a shipping failure, or any other exception before the order
     * confirms) — a deliberate first taste of saga compensation, forward-ref
     * ch.23.
     */
    @Transactional
    public OrderDto placeOrder(OrderCreate command) {
        Customer customer = customerRepository.findById(command.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));

        Order order = new Order(customer, command.shippingAddress());
        boolean remoteInventory = "remote".equalsIgnoreCase(inventoryMode);

        // r05/ch.19 S7 (DRQ-042): skus this checkout has successfully
        // reserved via the remote gRPC Reserve so far. Always empty in
        // inventory.mode=local — compensation is then a guaranteed no-op,
        // because the local @Transactional rollback already undoes the
        // in-JVM decrement for free.
        List<ReservedLine> remoteReservations = new ArrayList<>();

        try {
            // SMELL[ch.16]: reaching directly into inventory's entities/repository from
            // the order context, with no anti-corruption layer at the seam.
            for (OrderCreate.Line line : command.items()) {
                InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
                if (remoteInventory) {
                    // r05/ch.19 S7: the decorating collaborator — the actual
                    // reserve/decrement now happens in the extracted
                    // inventory service's OWN database over gRPC, not
                    // against this monolith's local inventory_items row.
                    // inventoryItem above is still read locally only to
                    // populate OrderItem's FK/name/price snapshot fields;
                    // ch.18/S8 later replaces this with a denormalized
                    // snapshot captured from the gRPC reply (H3, not yet
                    // done here).
                    RemoteInventoryClient.ReserveResult result =
                            remoteInventoryClient.reserve(line.sku(), line.quantity());
                    if (!result.ok()) {
                        throw new InsufficientStockException("Requested %d of %s but only %d on hand"
                                .formatted(line.quantity(), line.sku(), result.onHandQty()));
                    }
                    remoteReservations.add(new ReservedLine(line.sku(), line.quantity()));
                } else {
                    inventoryService.reserve(line.sku(), line.quantity()); // throws InsufficientStockException, no writes yet
                }
                order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
            }

            order = orderRepository.save(order);

            // SMELL[ch.22]/[ch.26]: payment captured synchronously, in-process, inside
            // the same transaction as the inventory decrement above. A decline here
            // rolls back the inventory reservation too in inventory.mode=local (see
            // PaymentDeclinedException); in inventory.mode=remote the decrement
            // already committed in the inventory service's own database, so the
            // catch block below issues the compensating Release instead (DRQ-042).
            paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
            order.confirm();

            // SMELL[ch.26]: shipping dispatched synchronously from the order's god
            // service rather than being triggered by an event the order context emits.
            shippingService.dispatch(order, order.getShippingAddress());

            // ch.17 cure (r04/S8): write the event to the outbox, atomically, in
            // THIS transaction. No Kafka client is touched here — the
            // @Scheduled OutboxRelay is the only thing that talks to Kafka, on
            // its own schedule, after this transaction has committed (or not, if
            // something downstream still throws). This is now the ONLY
            // notification path; the synchronous in-transaction call (SMELL[ch.17])
            // has been removed.
            writeOrderPlacedOutboxEvent(customer, order);

            return toDto(order);
        } catch (RuntimeException ex) {
            // r05/ch.19 S7 (DRQ-042): undo every remote reservation this
            // checkout already committed before propagating the ORIGINAL
            // failure (insufficient stock on a later line / payment decline
            // / shipping failure / any other exception before confirmation).
            // No-op when remoteReservations is empty (inventory.mode=local,
            // or inventory.mode=remote but nothing was reserved yet).
            compensateRemoteReservations(remoteReservations);
            throw ex;
        }
    }

    /**
     * Compensating {@code Release} (DRQ-042, a deliberate first taste of
     * saga — forward-ref ch.23) for every sku {@link #placeOrder} reserved
     * remotely before the checkout failed. Best-effort: a {@code Release}
     * failure is logged loudly (never swallowed silently) but does not
     * replace the ORIGINAL checkout failure being propagated to the caller —
     * an honest, documented limitation (no idempotency key or saga ledger
     * yet) rather than speculative full-saga infrastructure this step does
     * not need.
     */
    private void compensateRemoteReservations(List<ReservedLine> remoteReservations) {
        for (ReservedLine reserved : remoteReservations) {
            try {
                remoteInventoryClient.release(reserved.sku(), reserved.quantity());
            } catch (RuntimeException releaseFailure) {
                LOG.error(
                        "inventory.mode=remote compensation FAILED for sku={} qty={} — stock NOT restored "
                                + "(DRQ-042: full saga/idempotency deferred to ch.23)",
                        reserved.sku(), reserved.quantity(), releaseFailure);
            }
        }
    }

    /** r05/ch.19 S7 (DRQ-042): one remotely-reserved checkout line pending compensation if checkout fails. */
    private record ReservedLine(String sku, int quantity) {
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
