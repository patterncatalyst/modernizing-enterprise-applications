package dev.patterncatalyst.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * ch.26 S6 (DRQ-067/074) — THE CQRS projection. Re-derives the {@link
 * OrderView} read-model row for ONE order from that order's CURRENT {@link
 * Order} aggregate state, called from inside the SAME {@code @Transactional}
 * boundary as every write that changes the aggregate:
 *
 * <ul>
 *   <li>{@link OrderService#placeOrder} — the initial {@code PENDING} row.
 *   <li>{@link OrderSagaListener#onPaymentCaptured} — {@code
 *       AWAITING_SHIPMENT} + {@code paymentStatus=CAPTURED}.
 *   <li>{@link OrderSagaListener#onPaymentDeclined} — {@code
 *       PAYMENT_DECLINED} + {@code paymentStatus=DECLINED}.
 *   <li>{@link OrderSagaListener#onShipmentDispatched} — {@code CONFIRMED}
 *       + {@code shipmentStatus=DISPATCHED}.
 *   <li>{@link OrderSagaListener#onShipmentFailed} — {@code
 *       SHIPPING_FAILED} + {@code shipmentStatus=FAILED}.
 * </ul>
 *
 * <p><b>Same transaction, no dual-write (DRQ-067):</b> {@link #project}
 * deliberately carries NO {@code @Transactional} of its own — it must run
 * inside a transaction already opened by its caller (a bare
 * persist/dirty-checking flush outside a transaction throws). Both the
 * aggregate row(s) and the read-model row therefore commit or roll back
 * together as one unit: there is no second datastore and no window where
 * one is visible without the other.
 *
 * <p><b>Idempotent by {@code orderId} (DRQ-074):</b> {@link #project}/{@link
 * #rebuildOne} are an UPSERT keyed on {@link Order#getId()} — find the
 * existing {@link OrderView} row (if any) and mutate it in place, or insert
 * a brand-new row if none exists yet. Calling {@link #project} twice for
 * the SAME order (e.g. a redelivered saga event re-running a reaction)
 * produces exactly one row carrying the LATEST values, never a duplicate —
 * proven directly against a real database, independent of {@link
 * OrderSagaListener}'s own status-transition guards, by {@code
 * OrderViewProjectionTest#project_calledTwiceForSameOrder_upsertsSingleRow}.
 *
 * <p><b>Read-after-write honesty (DRQ-067):</b> because the projection runs
 * in the SAME transaction as the write, a {@code GET} issued after this
 * service's own API call returns sees the new state immediately — strongly
 * consistent WITHIN this service. The cross-service OUTCOME a transition
 * represents (e.g. the shipping hop that eventually yields {@code
 * CONFIRMED}) is still only as fresh as the Kafka event that triggered this
 * projection — the system-wide saga remains eventually consistent; only
 * this order-service's own write-then-read path is strong. This class does
 * NOT claim whole-system strong consistency.
 *
 * <p><b>Rebuildable from the aggregate (DRQ-074):</b> {@link #rebuildAll}
 * re-derives EVERY {@link OrderView} row from {@link OrderRepository}'s
 * current state — the documented recovery path if the read model is ever
 * found to have drifted (corrupted row, a bug in a past projection, a
 * manual DB fix gone wrong). It is exposed as {@code POST
 * /api/orders/_rebuild-view} ({@link OrderResource#rebuildView}).
 */
@ApplicationScoped
public class OrderViewProjector {

    private final OrderViewRepository orderViewRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderViewProjector(
            OrderViewRepository orderViewRepository, OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderViewRepository = orderViewRepository;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Upserts {@code order_view} from {@code order}'s current state.
     * {@code paymentStatus}/{@code shipmentStatus} are "set if non-null,
     * else PRESERVE the existing projected value" — a reaction that only
     * knows about ONE of the two sagas (e.g. {@link
     * OrderSagaListener#onShipmentDispatched} only knows the shipment
     * outcome) must not clobber the other saga's already-projected status.
     * Pass {@code null} for a status this call has no new information
     * about (e.g. {@link OrderService#placeOrder}'s initial projection,
     * which knows neither).
     */
    public void project(Order order, String paymentStatus, String shipmentStatus) {
        upsert(order, paymentStatus, shipmentStatus, /* preserveExistingIfNull= */ true);
    }

    /**
     * Rebuild hook (DRQ-074) for ONE order: re-derives its view row
     * straight from the aggregate's CURRENT state, with {@code
     * paymentStatus}/{@code shipmentStatus} DERIVED from {@link
     * OrderStatus} itself rather than left blank — the lifecycle's
     * reachability guarantees ({@link OrderSagaListener}'s class javadoc:
     * {@code AWAITING_SHIPMENT}/{@code CONFIRMED}/{@code SHIPPING_FAILED}
     * are each reachable via exactly one prior saga outcome) make the
     * mapping deterministic:
     *
     * <ul>
     *   <li>{@code PENDING} → no payment/shipment outcome yet (both null).
     *   <li>{@code PAYMENT_DECLINED} → payment {@code DECLINED}.
     *   <li>{@code AWAITING_SHIPMENT} → payment {@code CAPTURED}, no
     *       shipment outcome yet.
     *   <li>{@code CONFIRMED} → payment {@code CAPTURED}, shipment {@code
     *       DISPATCHED}.
     *   <li>{@code SHIPPING_FAILED} → payment {@code CAPTURED}, shipment
     *       {@code FAILED}.
     * </ul>
     */
    public void rebuildOne(Order order) {
        upsert(
                order,
                derivedPaymentStatus(order.getStatus()),
                derivedShipmentStatus(order.getStatus()),
                /* preserveExistingIfNull= */ false);
    }

    /**
     * Rebuild hook (DRQ-074) for EVERY order: re-derives the entire
     * read model from {@link OrderRepository}'s current state, one order at
     * a time via {@link #rebuildOne}. Unlike {@link #project} (called from
     * an already-open caller transaction), this IS the transactional
     * boundary — it is the entry point {@link OrderResource#rebuildView}
     * calls.
     */
    @Transactional
    public int rebuildAll() {
        List<Order> allOrders = orderRepository.listAll();
        for (Order order : allOrders) {
            rebuildOne(order);
        }
        return allOrders.size();
    }

    private void upsert(Order order, String paymentStatus, String shipmentStatus, boolean preserveExistingIfNull) {
        String itemsJson = serializeItems(order);
        OrderView existing = orderViewRepository.findById(order.getId());
        if (existing == null) {
            orderViewRepository.persist(new OrderView(
                    order.getId(),
                    order.getCustomerId(),
                    order.getStatus().name(),
                    order.getTotalCents(),
                    order.getCreatedAt(),
                    order.getShippingAddress(),
                    itemsJson,
                    paymentStatus,
                    shipmentStatus));
            return;
        }
        existing.update(
                order.getCustomerId(),
                order.getStatus().name(),
                order.getTotalCents(),
                order.getShippingAddress(),
                itemsJson,
                resolvedStatus(paymentStatus, existing.getPaymentStatus(), preserveExistingIfNull),
                resolvedStatus(shipmentStatus, existing.getShipmentStatus(), preserveExistingIfNull));
        // No explicit orderViewRepository.persist(existing) call needed --
        // `existing` is already a managed entity (loaded via findById
        // inside this same transaction); Hibernate's own dirty-checking
        // flushes the mutation at commit, the same convention
        // OrderSagaListener's Order mutations already rely on.
    }

    private static String resolvedStatus(String newValue, String existingValue, boolean preserveExistingIfNull) {
        if (newValue != null) {
            return newValue;
        }
        return preserveExistingIfNull ? existingValue : null;
    }

    private static String derivedPaymentStatus(OrderStatus status) {
        return switch (status) {
            case PENDING -> null;
            case PAYMENT_DECLINED -> "DECLINED";
            case AWAITING_SHIPMENT, CONFIRMED, SHIPPING_FAILED -> "CAPTURED";
        };
    }

    private static String derivedShipmentStatus(OrderStatus status) {
        return switch (status) {
            case PENDING, PAYMENT_DECLINED, AWAITING_SHIPMENT -> null;
            case CONFIRMED -> "DISPATCHED";
            case SHIPPING_FAILED -> "FAILED";
        };
    }

    private String serializeItems(Order order) {
        List<OrderDto.Item> items = order.getItems().stream()
                .map(i -> new OrderDto.Item(i.getSku(), i.getQuantity(), i.getUnitPriceCents()))
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            // A list of primitives/Strings never fails to serialize in
            // practice; wrapping fails (and rolls back) the enclosing
            // write transaction rather than silently committing a write
            // with no matching read-model row.
            throw new IllegalStateException("Failed to serialize order_view items for order " + order.getId(), e);
        }
    }
}
