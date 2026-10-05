package dev.patterncatalyst.order;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * ch.26 S5 (DRQ-074) -- the four saga reactions LIFTED from the monolith's
 * {@code order.OrderSagaListener} (ch.23/24, DRQ-047/049/056/058/060/061/
 * 064), re-expressed on SmallRye Reactive Messaging / idiomatic Quarkus
 * instead of Spring Kafka, in this service's OWN consumer group (defaulted
 * from {@code quarkus.application.name} = {@code order-service} -- see
 * {@code application.properties}), distinct from both the monolith's
 * (frozen until ch.26 S9 decommission) and shipping-service's {@code
 * shipping-service} group. Deserialization is automatic (SmallRye's Jackson
 * message converter binds the JSON payload directly to the record parameter
 * type) rather than the monolith's manual {@code ObjectMapper#readValue} --
 * there is no Spring Kafka {@code StringDeserializer} layer to bridge here.
 *
 * <p><b>Every guarantee the monolith's listener documented is preserved
 * EXACTLY, same shape, same reasoning:</b>
 *
 * <ul>
 *   <li><b>Status-guard idempotency (DRQ-051/064):</b> each reaction guards
 *   on the order's CURRENT status before transitioning. A redelivered event
 *   for an order that has already left the expected pre-transition state is
 *   a no-op — safe under Kafka's at-least-once delivery and each producer's
 *   own at-least-once outbox relay.
 *   <li><b>At-most-once compensating {@code Release} (DRQ-042/049/056/060):</b>
 *   a direct consequence of the guard above — the {@code Release} calls in
 *   {@link #onPaymentDeclined} and {@link #onShipmentFailed} are reachable
 *   ONLY on the one specific transition each guards, so a redelivery that
 *   finds the order already past that transition skips the {@code Release}
 *   loop entirely, never reissuing it.
 *   <li><b>Mutual exclusion, disjoint by construction (H3):</b> {@link
 *   #onPaymentDeclined} fires only from {@code PENDING}; {@link
 *   #onShipmentFailed} fires only from {@code AWAITING_SHIPMENT}. An order
 *   can reach {@code AWAITING_SHIPMENT} ONLY via a successful {@link
 *   #onPaymentCaptured} reaction, i.e. only after payment already succeeded
 *   — so a payment decline short-circuits the order at {@code PENDING}
 *   before it could ever reach {@code AWAITING_SHIPMENT}, and a shipping
 *   failure can only be reached from an order whose payment was NOT
 *   declined. Neither reaction can ever issue a second, redundant
 *   compensating {@code Release} for the same reservation — and neither can
 *   collide with {@link OrderService#placeOrder}'s own pre-handoff catch,
 *   which only fires for a checkout that never reached {@code order.placed}
 *   being committed (see that method's javadoc).
 *   <li><b>{@code CONFIRMED} reachable ONLY via {@link
 *   #onShipmentDispatched} (DRQ-061):</b> {@link #onPaymentCaptured}
 *   transitions to {@code AWAITING_SHIPMENT} and nothing else — it never
 *   calls {@link Order#confirm()}. There is no other path to {@code
 *   CONFIRMED} in this class.
 *   <li><b>Unknown order / partial {@code Release} failure never throw out of
 *   the consumer:</b> an event for an {@code orderId} this service has never
 *   seen is logged and dropped (not retried forever as a redelivery, since
 *   there is nothing here that will ever create that row). A {@code Release}
 *   failure for one sku is logged loudly but does not prevent attempting the
 *   remaining skus' releases, and never propagates to fail the consumer
 *   (which would otherwise cause Reactive Messaging to treat a handled
 *   business outcome as a transient processing error and redeliver
 *   forever).
 *   <li><b>Documented limitation (DRQ-056), carried over unchanged:</b>
 *   {@link #onShipmentFailed} compensates INVENTORY only — it does NOT
 *   refund the payment the payment service already captured. Building a
 *   refund path would extend this bounded saga's scope beyond what this
 *   extraction sets out to teach.
 * </ul>
 *
 * <p><b>ch.26 S6 (DRQ-067/074) — CQRS read-model projection on every
 * transition:</b> each reaction below calls {@link
 * OrderViewProjector#project} AFTER mutating the {@link Order} aggregate,
 * inside the SAME {@code @Transactional} this reaction already opened — the
 * read model and the write model commit together, atomically, same as
 * {@link OrderService#placeOrder}. Because {@link #project} is called only
 * on the path PAST the status guard above, a redelivered/stray event that
 * the guard turns into a no-op never re-projects either — the guard
 * protects both models identically. {@link OrderViewProjector#project}
 * itself is independently idempotent by {@code orderId} regardless (an
 * upsert, not an insert) — see its javadoc.
 */
@ApplicationScoped
public class OrderSagaListener {

    private static final Logger LOG = Logger.getLogger(OrderSagaListener.class);

    private final OrderRepository orderRepository;
    private final RemoteInventoryClient remoteInventoryClient;
    private final OrderViewProjector orderViewProjector;

    public OrderSagaListener(
            OrderRepository orderRepository,
            RemoteInventoryClient remoteInventoryClient,
            OrderViewProjector orderViewProjector) {
        this.orderRepository = orderRepository;
        this.remoteInventoryClient = remoteInventoryClient;
        this.orderViewProjector = orderViewProjector;
    }

    /**
     * Happy-path saga reaction: the payment service captured funds for this
     * order. Unconditionally transitions {@code PENDING -> AWAITING_SHIPMENT}
     * — never confirms or dispatches shipping in-process. Fulfilment and the
     * eventual {@code CONFIRMED} transition are owned entirely by {@link
     * #onShipmentDispatched}/{@link #onShipmentFailed} below, reacting to the
     * shipping service's own saga outcome.
     */
    @Incoming("payment-captured")
    @Transactional
    public void onPaymentCaptured(PaymentCaptured event) {
        Order order = orderRepository.findByIdOptional(event.orderId()).orElse(null);
        if (order == null) {
            LOG.errorf("payment.captured for unknown orderId=%d — cannot react", event.orderId());
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            LOG.infof("idempotency guard: order %d is already %s — ignoring redelivered payment.captured",
                    order.getId(), order.getStatus());
            return;
        }
        order.awaitShipment();
        orderViewProjector.project(order, "CAPTURED", null);
        LOG.infof("order %d AWAITING_SHIPMENT via payment.captured", order.getId());
    }

    /**
     * Orchestrated-saga success reaction: the shipping service's coordinator
     * dispatched the shipment and emitted exactly one {@code
     * shipment.dispatched}. Transitions {@code AWAITING_SHIPMENT ->
     * CONFIRMED}. No order may reach {@code CONFIRMED} without this reaction
     * firing — there is no other path (see class javadoc).
     */
    @Incoming("shipment-dispatched")
    @Transactional
    public void onShipmentDispatched(ShipmentDispatched event) {
        Order order = orderRepository.findByIdOptional(event.orderId()).orElse(null);
        if (order == null) {
            LOG.errorf("shipment.dispatched for unknown orderId=%d — cannot react", event.orderId());
            return;
        }
        if (order.getStatus() != OrderStatus.AWAITING_SHIPMENT) {
            LOG.infof(
                    "idempotency guard: order %d is %s (not AWAITING_SHIPMENT) — ignoring "
                            + "redelivered/stray shipment.dispatched",
                    order.getId(), order.getStatus());
            return;
        }
        order.confirm();
        orderViewProjector.project(order, null, "DISPATCHED");
        LOG.infof("order %d CONFIRMED via shipment.dispatched (shipmentId=%d)", order.getId(), event.shipmentId());
    }

    /**
     * Orchestrated-saga compensation reaction: the shipping service's
     * coordinator aborted and emitted exactly one {@code shipment.failed}
     * after cancelling its own shipment row. Marks the order the terminal
     * {@code SHIPPING_FAILED} and issues the compensating gRPC {@code
     * Release} for EVERY sku the order reserved at checkout, recovered from
     * the persisted {@link Order}'s {@link OrderItem}s — the order context,
     * not the shipping service, performs this undo because it owns the
     * reserved-line snapshot (DRQ-060).
     */
    @Incoming("shipment-failed")
    @Transactional
    public void onShipmentFailed(ShipmentFailed event) {
        Order order = orderRepository.findByIdOptional(event.orderId()).orElse(null);
        if (order == null) {
            LOG.errorf("shipment.failed for unknown orderId=%d — cannot react", event.orderId());
            return;
        }
        if (order.getStatus() != OrderStatus.AWAITING_SHIPMENT) {
            LOG.infof(
                    "idempotency guard: order %d is %s (not AWAITING_SHIPMENT) — ignoring "
                            + "redelivered/stray shipment.failed (compensating Release already issued at most once)",
                    order.getId(), order.getStatus());
            return;
        }
        order.failShipping();
        for (OrderItem item : order.getItems()) {
            try {
                remoteInventoryClient.release(item.getSku(), item.getQuantity());
            } catch (RuntimeException releaseFailure) {
                LOG.errorf(
                        releaseFailure,
                        "compensation FAILED for order=%d sku=%s qty=%d — stock NOT restored "
                                + "(DRQ-064: no saga ledger/retry); payment is NOT refunded either way (DRQ-056)",
                        order.getId(), item.getSku(), item.getQuantity());
            }
        }
        orderViewProjector.project(order, null, "FAILED");
        LOG.infof(
                "order %d SHIPPING_FAILED via shipment.failed (reason=%s); compensating Release issued for "
                        + "%d line(s); payment NOT refunded (DRQ-056)",
                order.getId(), event.reason(), order.getItems().size());
    }

    /**
     * Failure-path saga reaction (DRQ-049): the payment service declined
     * this order's charge. Marks the order {@code PAYMENT_DECLINED} and
     * issues the compensating gRPC {@code Release} for every sku the order
     * reserved at checkout, recovered from the persisted {@link Order}'s
     * {@link OrderItem}s.
     */
    @Incoming("payment-declined")
    @Transactional
    public void onPaymentDeclined(PaymentDeclined event) {
        Order order = orderRepository.findByIdOptional(event.orderId()).orElse(null);
        if (order == null) {
            LOG.errorf("payment.declined for unknown orderId=%d — cannot react", event.orderId());
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            LOG.infof(
                    "idempotency guard: order %d is already %s — ignoring redelivered payment.declined "
                            + "(compensating Release already issued at most once)",
                    order.getId(), order.getStatus());
            return;
        }
        order.declinePayment();
        for (OrderItem item : order.getItems()) {
            try {
                remoteInventoryClient.release(item.getSku(), item.getQuantity());
            } catch (RuntimeException releaseFailure) {
                LOG.errorf(
                        releaseFailure,
                        "compensation FAILED for order=%d sku=%s qty=%d — stock NOT restored (DRQ-049: "
                                + "no saga ledger/retry)",
                        order.getId(), item.getSku(), item.getQuantity());
            }
        }
        orderViewProjector.project(order, "DECLINED", null);
        LOG.infof("order %d PAYMENT_DECLINED via payment.declined; compensating Release issued for %d line(s)",
                order.getId(), order.getItems().size());
    }
}
