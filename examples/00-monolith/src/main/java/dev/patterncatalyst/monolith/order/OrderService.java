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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * typed gRPC contract. As of r06/ch.23 S9, payment is the third: this class no
 * longer has a direct dependency on payment's service/repository/entity at
 * all (they are deleted) — checkout only ever reaches payment indirectly, via
 * the {@code order.placed} outbox event, and only reacts to ITS outcome
 * events through {@link OrderSagaListener}, never a direct call. Shipping
 * (still directly reached, now from {@code OrderSagaListener} rather than
 * this class) and order itself remain (ch.24, ch.26).
 *
 * <p>ch.23 (r06/S9, DECOMMISSION): the {@code payment.mode=synchronous|
 * choreographed} reversibility flag introduced for the S6 cutover has been
 * removed along with the monolith's in-process payment module ({@code
 * payment.PaymentController}/{@code PaymentService}/{@code Payment}/{@code
 * PaymentRepository}, now deleted) — choreographed is the ONLY path.
 * {@link #placeOrder} never calls payment (or shipping) in-line: it
 * validates the customer, reserves every line synchronously over gRPC,
 * persists the order {@code PENDING}, writes the {@code order.placed}
 * outbox event (carrying {@code paymentMethod}), and returns — the
 * controller maps that to {@code 202 Accepted}. {@link OrderSagaListener}
 * reacts to the payment service's {@code payment.captured}/{@code
 * payment.declined} outcomes to confirm+ship or decline+compensate
 * (DRQ-049). The synchronous {@code 201}/{@code 402} checkout contract this
 * method used to also support is gone; see the class/method history below
 * and {@code common/web/GlobalExceptionHandler} for what was removed.
 */
@Service
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RemoteInventoryClient remoteInventoryClient;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            RemoteInventoryClient remoteInventoryClient,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.remoteInventoryClient = remoteInventoryClient;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * SMELL[ch.22]: one in-process ACID {@code @Transactional} spans FOUR bounded
     * contexts — order, inventory, payment, shipping (plus this context's own
     * outbox table write; the outbox is order-owned infrastructure, not a
     * notification-context collaborator, so it doesn't widen this blast radius
     * the way the old synchronous notification call did). It "worked" in the
     * original monolith because Postgres gave atomic rollback for free across
     * all of them. r06/ch.23 S9 (DECOMMISSION) REALIZES the ACID -> ACD story
     * this smell names, for the payment portion: payment is now its own
     * service with its own database, so this method no longer calls it at
     * all (see below), and the cross-context consistency a local rollback
     * used to give for free is rebuilt EXPLICITLY as the choreographed saga
     * (order.placed -> payment captures/declines -> {@link
     * OrderSagaListener} reacts) with an idempotent compensating {@code
     * Release} on decline (DRQ-049) — see SMELLS.md SMELL[ch.22]. Notification
     * (ch.17/r04/S8) was the first context removed from this transaction's
     * blast radius; payment (ch.23/r06/S9) is the second. Shipping and order
     * itself remain (ch.24, ch.26).
     *
     * <p>Flow: validate customer -> check+reserve stock (inventory, over
     * gRPC -- see below) -> persist the order {@code PENDING} -> write the
     * {@code order.placed} outbox event (the handoff to the choreographed
     * saga) -> return. Payment capture and order confirmation/shipping
     * dispatch happen OUT OF PROCESS and asynchronously, driven by {@link
     * OrderSagaListener} reacting to the payment service's outcome events —
     * see that class's javadoc.
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
     * {@code @Transactional} and therefore can no longer be undone by it.
     *
     * <p>r06/ch.23 S9 (DECOMMISSION, DRQ-047/DRQ-049) — <b>what the
     * surrounding {@code try/catch} compensates now:</b> the {@code
     * payment.mode=synchronous|choreographed} flag is gone (choreographed is
     * the only path) and payment is no longer called from this method AT
     * ALL, so the catch below can no longer be reached by a payment decline —
     * that compensation has moved to {@link
     * OrderSagaListener#onPaymentDeclined}, which reacts to the payment
     * service's {@code payment.declined} event for an order that WAS already
     * handed off (DRQ-049). What the catch STILL must do, and does: this
     * loop can still throw mid-flight — e.g. line 1 reserves successfully
     * and line 2 is out of stock ({@link InsufficientStockException}), or the
     * subsequent {@code orderRepository.save}/outbox write throws — leaving
     * line 1's reservation committed in the inventory service's own database
     * with no saga ever started to compensate it (the order was never
     * handed off; {@code order.placed} was never written). The catch below
     * releases every such PRE-HANDOFF reservation before propagating the
     * original failure. The catch and {@link
     * OrderSagaListener#onPaymentDeclined} are therefore mutually
     * exclusive by construction: the catch only fires for a checkout that
     * never reached the saga (no {@code order.placed} row committed), and
     * the reaction only fires for an order that DID (a {@code PENDING} row
     * exists for it to transition) — neither can double-compensate the same
     * reservation.
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
        // if checkout fails BEFORE the order is handed off to the saga.
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

            // ch.23 (r06/S9, DRQ-047/H1/H4): checkout NEVER calls payment or
            // shipping in-line. The order is persisted PENDING and the
            // order.placed outbox event (carrying paymentMethod, DRQ-048) is
            // the handoff to the payment service, which reacts over Kafka
            // and emits payment.captured|declined. OrderSagaListener reacts
            // to those and drives confirm+ship or decline+compensate — see
            // its class javadoc. order.confirm() and a shipping dispatch
            // call are deliberately NOT present here.
            writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());
            return toDto(order);
        } catch (RuntimeException ex) {
            // r06/ch.23 S9 (DRQ-049): this compensates ONLY a pre-handoff
            // failure now (a later line out of stock, or the save/outbox
            // write itself throwing) — never a payment decline, since
            // payment is never called from this method. See this method's
            // javadoc for why this and OrderSagaListener#onPaymentDeclined
            // can never double-compensate the same reservation. No-op when
            // nothing was reserved yet.
            compensateRemoteReservations(remoteReservations);
            throw ex;
        }
    }

    /**
     * Compensating {@code Release} (DRQ-042, originally a first taste of
     * saga compensation, forward-ref'd to ch.23) for every sku {@link
     * #placeOrder} reserved remotely before a PRE-HANDOFF checkout failure
     * (insufficient stock on a later line, or the order save/outbox write
     * throwing) — see {@link #placeOrder}'s javadoc for why this is now
     * disjoint from the payment-decline compensation, which lives in {@link
     * OrderSagaListener#onPaymentDeclined} (DRQ-049) since r06/ch.23 S9.
     * Best-effort: a {@code Release} failure is logged loudly (never
     * swallowed silently) but does not replace the ORIGINAL checkout failure
     * being propagated to the caller — an honest, documented limitation (no
     * idempotency key or saga ledger yet) rather than speculative full-saga
     * infrastructure this step does not need.
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
