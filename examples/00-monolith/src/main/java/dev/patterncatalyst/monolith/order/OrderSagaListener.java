package dev.patterncatalyst.monolith.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.OrderStatus;
import dev.patterncatalyst.monolith.common.Topics;
import dev.patterncatalyst.monolith.common.events.PaymentCaptured;
import dev.patterncatalyst.monolith.common.events.PaymentDeclined;
import dev.patterncatalyst.monolith.inventory.RemoteInventoryClient;
import dev.patterncatalyst.monolith.shipping.ShippingService;
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
 * <p><b>Idempotency (DRQ-051):</b> both reactions guard on the order's
 * CURRENT status — if it is not {@code PENDING}, the event is a no-op
 * (redelivery-safe; Kafka's at-least-once delivery and the payment
 * service's own outbox relay can both redeliver). This also guarantees the
 * compensating {@code Release} below fires AT MOST ONCE per order: it is
 * only reachable on the one {@code PENDING -> PAYMENT_DECLINED} transition.
 */
@Component
public class OrderSagaListener {

    private static final Logger LOG = LoggerFactory.getLogger(OrderSagaListener.class);

    private final OrderRepository orderRepository;
    private final RemoteInventoryClient remoteInventoryClient;
    private final ShippingService shippingService;
    private final ObjectMapper objectMapper;

    public OrderSagaListener(
            OrderRepository orderRepository,
            RemoteInventoryClient remoteInventoryClient,
            ShippingService shippingService,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.remoteInventoryClient = remoteInventoryClient;
        this.shippingService = shippingService;
        this.objectMapper = objectMapper;
    }

    /**
     * Happy-path saga reaction: the payment service captured funds for this
     * order. Confirms the order and dispatches shipping — shipping stays in
     * the monolith until ch.24, but is now triggered by the event, not the
     * request thread (SMELL[ch.26] is unchanged by this step; only WHO
     * triggers the call moves).
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
        order.confirm();
        orderRepository.save(order);
        shippingService.dispatch(order, order.getShippingAddress());
        LOG.info("order {} CONFIRMED via payment.captured; shipping dispatched", order.getId());
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
