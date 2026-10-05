package dev.patterncatalyst.monolith.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.OrderStatus;
import dev.patterncatalyst.monolith.common.Topics;
import dev.patterncatalyst.monolith.common.events.PaymentCaptured;
import dev.patterncatalyst.monolith.common.events.PaymentDeclined;
import dev.patterncatalyst.monolith.common.events.ShipmentDispatched;
import dev.patterncatalyst.monolith.common.events.ShipmentFailed;
import dev.patterncatalyst.monolith.inventory.RemoteInventoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ch.23 (r06/S6, DRQ-047/DRQ-049, H4) — the monolith's FIRST Kafka
 * <b>consumer</b>. Until this step the monolith only ever <em>produced</em>
 * (the {@code order.placed} transactional outbox relay); the order context
 * now also <em>consumes</em> the payment service's two possible outcomes
 * and drives the rest of the order lifecycle off them, replacing the ch.19
 * in-line {@code catch} compensation for the payment-decline path
 * specifically (that catch, in {@code OrderService#placeOrder}, now only
 * compensates a PRE-HANDOFF reserve/save/outbox failure — see its javadoc
 * for why the two compensation paths can never overlap).
 *
 * <p>r06/ch.23 S9 (DECOMMISSION): the {@code payment.mode=synchronous|
 * choreographed} reversibility flag this listener's reactions used to be
 * conditionally exercised under is gone — choreographed is now the ONLY
 * checkout path, so every order handed off via {@code order.placed}
 * genuinely waits on this listener to reach a terminal state; there is no
 * more synchronous fallback where an order is already terminal before this
 * listener could react.
 *
 * <p><b>ch.24 (r07/S6, DRQ-056/058/061, H3/H4):</b> {@link #onPaymentCaptured}
 * transitions the order to {@code AWAITING_SHIPMENT} and does NOT dispatch
 * in-process; fulfilment is owned by the shipping service's Camel Saga EIP
 * coordinator ({@code examples/06-shipping-service}, r07/S5), which
 * independently consumes this SAME {@code payment.captured} topic (its own
 * consumer group — this listener and the shipping service are two
 * unrelated consumer groups reacting to one event, not a hand-off) and
 * eventually emits exactly one of {@link #onShipmentDispatched}/{@link
 * #onShipmentFailed} below.
 *
 * <p>r07/ch.24 S9 (DECOMMISSION): the {@code shipping.mode=inprocess|
 * orchestrated} reversibility flag this listener's {@link #onPaymentCaptured}
 * reaction used to branch on is gone, along with the monolith's in-process
 * {@code shipping.ShippingController}/{@code ShippingService}/{@code
 * Shipment}/{@code ShipmentRepository} module it used to dispatch into
 * directly — orchestrated is now the ONLY shipping path, so {@link
 * #onPaymentCaptured} unconditionally transitions {@code PENDING ->
 * AWAITING_SHIPMENT} and never dispatches in-process; {@link
 * #onShipmentDispatched}/{@link #onShipmentFailed} are correspondingly
 * always genuinely live (SMELL[ch.22] — now CURED for shipping too, see
 * SMELLS.md).
 *
 * <p><b>No double-compensation between the payment and shipping failure
 * paths (H3):</b> {@link #onPaymentDeclined} only fires for an order still
 * {@code PENDING}, and {@link #onShipmentFailed} only fires for an order
 * {@code AWAITING_SHIPMENT} — an order can reach {@code AWAITING_SHIPMENT}
 * ONLY via a successful {@code payment.captured} reaction, i.e. only after
 * payment already succeeded. The two failure reactions are therefore
 * mutually exclusive by construction (exactly like {@code
 * OrderService#placeOrder}'s pre-handoff catch vs. {@link
 * #onPaymentDeclined} are, per that method's javadoc): a payment decline
 * short-circuits the order at {@code PENDING} before it could ever reach
 * {@code AWAITING_SHIPMENT}, and a shipping failure can only be reached
 * from {@code AWAITING_SHIPMENT}, which by definition already had a
 * successful (non-declined) payment. Neither reaction can ever issue a
 * second, redundant compensating {@code Release} for the same reservation.
 *
 * <p><b>Documented limitation (DRQ-056):</b> {@link #onShipmentFailed}
 * compensates INVENTORY only (the gRPC {@code Release}) — it does NOT
 * refund the payment the payment service already captured. Reaching back
 * into payment to issue a refund would require extending the saga's scope
 * (a longer, payment-aware compensation), which this bounded ch.24 saga
 * deliberately does not attempt; see that method's javadoc.
 *
 * <p><b>Idempotency (DRQ-051/064):</b> all four reactions guard on the
 * order's CURRENT status — if it is not in the expected pre-transition
 * state, the event is a no-op (redelivery-safe; Kafka's at-least-once
 * delivery and each producer's own outbox relay can both redeliver). This
 * also guarantees the compensating {@code Release} calls below fire AT
 * MOST ONCE per order: each is only reachable on one specific status
 * transition.
 */
@Component
public class OrderSagaListener {

    private static final Logger LOG = LoggerFactory.getLogger(OrderSagaListener.class);

    private final OrderRepository orderRepository;
    private final RemoteInventoryClient remoteInventoryClient;
    private final ObjectMapper objectMapper;

    public OrderSagaListener(
            OrderRepository orderRepository,
            RemoteInventoryClient remoteInventoryClient,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.remoteInventoryClient = remoteInventoryClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Happy-path saga reaction: the payment service captured funds for this
     * order.
     *
     * <p>r07/ch.24 S9 (DECOMMISSION): unconditionally transitions the order
     * {@code PENDING -> AWAITING_SHIPMENT} and persists — never confirms or
     * dispatches shipping in-process (the monolith's in-process {@code
     * ShippingService} this used to call directly in {@code
     * shipping.mode=inprocess} is deleted). Fulfilment and the eventual
     * {@code CONFIRMED} transition are owned entirely by {@link
     * #onShipmentDispatched}/{@link #onShipmentFailed} below, reacting to
     * the shipping service's saga outcome.
     */
    @KafkaListener(topics = Topics.PAYMENT_CAPTURED)
    @Transactional
    public void onPaymentCaptured(String payload) {
        PaymentCaptured event = deserialize(payload, PaymentCaptured.class);
        if (event == null) {
            return;
        }
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null) {
            LOG.error("payment.captured for unknown orderId={} — cannot react (payload={})",
                    event.orderId(), payload);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            LOG.info("idempotency guard: order {} is already {} — ignoring redelivered payment.captured",
                    order.getId(), order.getStatus());
            return;
        }
        order.awaitShipment();
        orderRepository.save(order);
        LOG.info("order {} AWAITING_SHIPMENT via payment.captured", order.getId());
    }

    /**
     * ch.24 (r07/S6, DRQ-058/061/064) — orchestrated-saga success reaction:
     * the shipping service's Camel Saga EIP coordinator dispatched the
     * shipment and emitted exactly one {@code shipment.dispatched}.
     * Transitions the order {@code AWAITING_SHIPMENT -> CONFIRMED}. No
     * order may reach {@code CONFIRMED} without this reaction firing —
     * there is no other path to {@code CONFIRMED} since {@link
     * #onPaymentCaptured} never confirms directly (r07/ch.24 S9).
     *
     * <p>Idempotent (DRQ-064): a redelivered event for an order that has
     * already left {@code AWAITING_SHIPMENT} (already {@code CONFIRMED}, or
     * — impossible by construction, see class javadoc — {@code
     * SHIPPING_FAILED}) is a no-op.
     */
    @KafkaListener(topics = Topics.SHIPMENT_DISPATCHED)
    @Transactional
    public void onShipmentDispatched(String payload) {
        ShipmentDispatched event = deserialize(payload, ShipmentDispatched.class);
        if (event == null) {
            return;
        }
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null) {
            LOG.error("shipment.dispatched for unknown orderId={} — cannot react (payload={})",
                    event.orderId(), payload);
            return;
        }
        if (order.getStatus() != OrderStatus.AWAITING_SHIPMENT) {
            LOG.info("idempotency guard: order {} is {} (not AWAITING_SHIPMENT) — ignoring "
                            + "redelivered/stray shipment.dispatched",
                    order.getId(), order.getStatus());
            return;
        }
        order.confirm();
        orderRepository.save(order);
        LOG.info("order {} CONFIRMED via shipment.dispatched (shipmentId={})", order.getId(), event.shipmentId());
    }

    /**
     * ch.24 (r07/S6, DRQ-056/058/060/061/064) — orchestrated-saga
     * compensation reaction: the shipping service's Camel Saga EIP
     * coordinator aborted (e.g. the deterministic {@code SHIP-FAIL}
     * injection, DRQ-062) and its compensation route emitted exactly one
     * {@code shipment.failed} after cancelling its own {@code Shipment}
     * row. Marks the order the terminal {@code SHIPPING_FAILED} and issues
     * the compensating gRPC {@code Release} for EVERY sku the order
     * reserved at checkout, recovered from the persisted {@link Order}'s
     * {@link OrderItem}s — reusing the exact same {@link
     * RemoteInventoryClient} machinery {@link #onPaymentDeclined} uses
     * (DRQ-060: the order context, not the shipping service, performs this
     * undo, because it owns the reserved-line snapshot — the coordinator
     * only owns the DECISION to compensate).
     *
     * <p><b>Documented limitation (DRQ-056):</b> this reaction compensates
     * INVENTORY only. The payment this order's {@code payment.captured}
     * already captured is NOT refunded — the order context has no
     * collaborator/contract to request a refund from the payment service,
     * and building one would extend this bounded saga's scope beyond what
     * ch.24 sets out to teach (a longer saga spanning back into payment is
     * a deliberately out-of-scope follow-on, not implemented here). This is
     * an honest, documented gap, not a silent one.
     *
     * <p>Idempotent (DRQ-064): a redelivered event for an order that has
     * already left {@code AWAITING_SHIPMENT} is a no-op, which also
     * guarantees the compensating {@code Release} fires AT MOST ONCE per
     * order — only reachable on the one {@code AWAITING_SHIPMENT ->
     * SHIPPING_FAILED} transition. Best-effort, same shape as {@link #onPaymentDeclined}:
     * a {@code Release} failure is logged loudly, never silently swallowed,
     * and is not retried on redelivery once the order has already left
     * {@code AWAITING_SHIPMENT}.
     */
    @KafkaListener(topics = Topics.SHIPMENT_FAILED)
    @Transactional
    public void onShipmentFailed(String payload) {
        ShipmentFailed event = deserialize(payload, ShipmentFailed.class);
        if (event == null) {
            return;
        }
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null) {
            LOG.error("shipment.failed for unknown orderId={} — cannot react (payload={})",
                    event.orderId(), payload);
            return;
        }
        if (order.getStatus() != OrderStatus.AWAITING_SHIPMENT) {
            LOG.info("idempotency guard: order {} is {} (not AWAITING_SHIPMENT) — ignoring "
                            + "redelivered/stray shipment.failed (compensating Release already issued at most once)",
                    order.getId(), order.getStatus());
            return;
        }
        order.failShipping();
        orderRepository.save(order);
        for (OrderItem item : order.getItems()) {
            try {
                remoteInventoryClient.release(item.getSku(), item.getQuantity());
            } catch (RuntimeException releaseFailure) {
                LOG.error(
                        "compensation FAILED for order={} sku={} qty={} — stock NOT restored "
                                + "(DRQ-064: no saga ledger/retry yet, same documented limitation as DRQ-042/049); "
                                + "payment is NOT refunded either way — DRQ-056",
                        order.getId(), item.getSku(), item.getQuantity(), releaseFailure);
            }
        }
        LOG.info("order {} SHIPPING_FAILED via shipment.failed (reason={}); compensating Release issued for "
                        + "{} line(s); payment NOT refunded (DRQ-056)",
                order.getId(), event.reason(), order.getItems().size());
    }

    /**
     * Failure-path saga reaction (DRQ-049 — replaces the ch.19 in-line
     * {@code catch} compensation for this path): the payment service
     * declined this order's charge. Marks the order {@code
     * PAYMENT_DECLINED} and issues the compensating gRPC {@code Release}
     * for every sku the order reserved at checkout, recovered from the
     * persisted {@link Order}'s {@link OrderItem}s. Best-effort, same as
     * {@code OrderService#compensateRemoteReservations}: a {@code Release}
     * failure is logged loudly, never silently swallowed, and (consistent
     * with the idempotency guard) is not retried on redelivery once the
     * order has already left {@code PENDING}.
     */
    @KafkaListener(topics = Topics.PAYMENT_DECLINED)
    @Transactional
    public void onPaymentDeclined(String payload) {
        PaymentDeclined event = deserialize(payload, PaymentDeclined.class);
        if (event == null) {
            return;
        }
        Order order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null) {
            LOG.error("payment.declined for unknown orderId={} — cannot react (payload={})",
                    event.orderId(), payload);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            LOG.info("idempotency guard: order {} is already {} — ignoring redelivered payment.declined "
                            + "(compensating Release already issued at most once)",
                    order.getId(), order.getStatus());
            return;
        }
        order.declinePayment();
        orderRepository.save(order);
        for (OrderItem item : order.getItems()) {
            try {
                remoteInventoryClient.release(item.getSku(), item.getQuantity());
            } catch (RuntimeException releaseFailure) {
                LOG.error(
                        "compensation FAILED for order={} sku={} qty={} — stock NOT restored "
                                + "(DRQ-049: no saga ledger/retry yet, same documented limitation as DRQ-042)",
                        order.getId(), item.getSku(), item.getQuantity(), releaseFailure);
            }
        }
        LOG.info("order {} PAYMENT_DECLINED via payment.declined; compensating Release issued for {} line(s)",
                order.getId(), order.getItems().size());
    }

    /**
     * Deserializes with the injected {@link ObjectMapper} rather than
     * relying on Spring Kafka's {@code JsonDeserializer} value
     * deserializer — the consumer factory is configured with plain {@code
     * StringDeserializer}s (matching the outbox relay's producer, DRQ-038),
     * so the JSON-to-record mapping happens here, inside the listener, the
     * same shape of responsibility the outbox relay's producer side has for
     * serialization.
     */
    private <T> T deserialize(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            LOG.error("failed to deserialize {} payload — dropping message: {}", type.getSimpleName(), payload, e);
            return null;
        }
    }
}
