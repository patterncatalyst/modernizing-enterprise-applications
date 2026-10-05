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
 * for checkout and it reaches directly into payment and shipping's
 * services/repositories/entities instead of those contexts being independently
 * deployable collaborators reached over a stable contract. Order is deliberately
 * the HARDEST and LAST extraction in the roadmap (ch.26) precisely because every
 * other context's extraction has to first remove one of this service's direct
 * dependencies — notification's was the first to go (ch.17/r04/S8): checkout
 * now reaches only its own outbox, not a notification-context collaborator; as
 * of r05/ch.19 S11, inventory is the second: the raw in-JVM
 * entity/repository reach-in is gone (SMELL #5, CURED — see SMELLS.md) and
 * checkout now reaches inventory only through {@link RemoteInventoryClient}'s
 * typed gRPC contract.
 *
 * <p>ch.23 (r06/S6, DRQ-047): {@code payment.mode=synchronous|choreographed}
 * (default {@code synchronous}) gates the checkout contract itself. In
 * {@code synchronous} mode (the default, unchanged baseline) {@link
 * #placeOrder} behaves exactly as described below: charge in-line, confirm,
 * dispatch shipping, 201/402. In {@code choreographed} mode {@code
 * placeOrder} stops calling payment/shipping in-line — the order is
 * persisted {@code PENDING} and the {@code order.placed} outbox handoff
 * drives the payment service; {@link OrderSagaListener} reacts to {@code
 * payment.captured}/{@code payment.declined} to confirm+ship or
 * decline+compensate (DRQ-049), replacing this method's in-line catch for
 * the payment-decline path only (the catch still compensates a reserve
 * failure in BOTH modes).
 */
@Service
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RemoteInventoryClient remoteInventoryClient;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final String paymentMode;

    public OrderService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            RemoteInventoryClient remoteInventoryClient,
            PaymentService paymentService,
            ShippingService shippingService,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            @Value("${payment.mode:synchronous}") String paymentMode) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.remoteInventoryClient = remoteInventoryClient;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.paymentMode = paymentMode;
    }

    /** ch.23 (r06/S6, DRQ-047): {@code true} when {@code payment.mode=choreographed}. */
    private boolean isChoreographed() {
        return "choreographed".equalsIgnoreCase(paymentMode);
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
     * <p>Flow: validate customer -> check+reserve stock (inventory, over gRPC
     * -- see below) -> persist the order -> charge payment (payment; a
     * decline triggers the compensating Release below) -> dispatch shipment
     * (shipping) -> write the {@code order.placed} outbox event.
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
     * <p>r05/ch.19 S11 (DECOMMISSION, DRQ-039/DRQ-041/DRQ-042/DRQ-045): the
     * monolith's own in-JVM inventory read/reserve surface
     * ({@code inventory.InventoryController}/{@code InventoryService}/
     * {@code InventoryItem}/{@code InventoryRepository}) has been removed
     * entirely, along with the {@code inventory.mode=local|remote}
     * reversibility flag introduced for the cutover (r05/ch.19 S7) — {@link
     * RemoteInventoryClient}'s gRPC {@code Reserve} against the extracted
     * inventory service's OWN database (SMELL #5, now CURED — see
     * SMELLS.md) is the ONLY path. That decrement commits OUTSIDE this
     * {@code @Transactional} and therefore can no longer be undone by it; the
     * surrounding try/catch issues a compensating gRPC {@code Release} for
     * every sku this checkout successfully reserved remotely, on ANY failure
     * that happens afterward (insufficient stock on a later line, a payment
     * decline, a shipping failure, or any other exception before the order
     * confirms) — a deliberate first taste of saga compensation, forward-ref
     * ch.23.
     *
     * <p>r05/ch.19 S8 (DRQ-043): {@code OrderItem} no longer holds a
     * cross-context JPA/DB FK onto an inventory entity (SMELL[ch.18], now
     * CURED for this seam). Each line's sku/name/unit-price-at-order-time is
     * captured as a denormalized snapshot from the remote inventory
     * service's {@code GetStock} gRPC reply (see {@link
     * RemoteInventoryClient#getStock}). {@code common.OrderDto}'s shape is
     * unchanged.
     */
    @Transactional
    public OrderDto placeOrder(OrderCreate command) {
        Customer customer = customerRepository.findById(command.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));

        Order order = new Order(customer, command.shippingAddress());

        // r05/ch.19 S7/S11 (DRQ-042): skus this checkout has successfully
        // reserved via the remote gRPC Reserve so far, pending compensation
        // if checkout fails afterward.
        List<ReservedLine> remoteReservations = new ArrayList<>();

        try {
            for (OrderCreate.Line line : command.items()) {
                // r05/ch.19 S7/S11: the decorating collaborator -- the actual
                // reserve/decrement happens in the extracted inventory
                // service's OWN database over gRPC (the proto IS the ACL,
                // curing SMELL #5 -- see SMELLS.md), never against a local
                // inventory_items row in this monolith.
                RemoteInventoryClient.ReserveResult result =
                        remoteInventoryClient.reserve(line.sku(), line.quantity());
                if (!result.ok()) {
                    throw new InsufficientStockException("Requested %d of %s but only %d on hand"
                            .formatted(line.quantity(), line.sku(), result.onHandQty()));
                }
                remoteReservations.add(new ReservedLine(line.sku(), line.quantity()));
                // r05/ch.19 S8 (DRQ-043): the OrderItem snapshot is captured
                // from the extracted inventory service's OWN GetStock reply --
                // never from a local inventory_items table -- curing SMELL #5
                // (raw-entity leak) at the source for the snapshot path too.
                RemoteInventoryClient.StockSnapshot stock = remoteInventoryClient.getStock(line.sku());
                OrderItem orderItem = new OrderItem(stock.sku(), stock.name(), line.quantity(), stock.priceCents());
                order.addItem(orderItem);
            }

            order = orderRepository.save(order);

            if (isChoreographed()) {
                // ch.23 (r06/S6, DRQ-047/H1/H4): payment.mode=choreographed —
                // checkout STOPS calling paymentService.charge in-line. The
                // order is persisted PENDING and the order.placed outbox
                // event (now carrying paymentMethod, DRQ-048) is the handoff
                // to the payment service, which reacts over Kafka and emits
                // payment.captured|declined. OrderSagaListener reacts to
                // those and drives confirm+ship or decline+compensate — see
                // its class javadoc. order.confirm() and
                // shippingService.dispatch are deliberately NOT called here.
                writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());
                return toDto(order);
            }

            // SMELL[ch.22]/[ch.26]: payment captured synchronously, in-process, inside
            // the same transaction as the inventory decrement above. A decline here
            // can no longer be undone by this transaction, because the decrement
            // already committed in the inventory service's own database -- the
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
            writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());

            return toDto(order);
        } catch (RuntimeException ex) {
            // r05/ch.19 S7/S11 (DRQ-042): undo every remote reservation this
            // checkout already committed before propagating the ORIGINAL
            // failure (insufficient stock on a later line / payment decline
            // / shipping failure / any other exception before confirmation).
            // No-op when nothing was reserved yet.
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
                        "compensation FAILED for sku={} qty={} — stock NOT restored "
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
    private void writeOrderPlacedOutboxEvent(Customer customer, Order order, String paymentMethod) {
        String confirmationMessage =
                "Order #%d confirmed, total $%.2f".formatted(order.getId(), order.getTotalCents() / 100.0);
        OrderPlacedEvent event = new OrderPlacedEvent(
                order.getId(),
                customer.getId(),
                customer.getEmail(),
                order.getTotalCents(),
                paymentMethod,
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
        // r05/ch.19 S8 (DRQ-043): projects from OrderItem's own denormalized
        // snapshot fields now, not a cross-context InventoryItem join -- the
        // external shape (OrderDto.Item: sku/quantity/unitPriceCents) is
        // byte-for-byte unchanged.
        List<OrderDto.Item> items = order.getItems().stream()
                .map(i -> new OrderDto.Item(i.getSku(), i.getQuantity(), i.getUnitPriceCents()))
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
