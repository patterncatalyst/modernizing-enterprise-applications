package dev.patterncatalyst.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * REFACTORED to idiomatic Quarkus (ch.26 S5, Phase B, DRQ-073) from Phase A's
 * {@code @Service @Scope("application")} Spring-compat bean -- plain CDI
 * {@code @ApplicationScoped} plus Quarkus's simplified constructor injection,
 * exactly mirroring payment-service's/shipping-service's Phase B services.
 * {@code @Scope("application")}'s only job (making the bean mockable with
 * {@code @InjectMock}) is now automatic: a plain {@code @ApplicationScoped}
 * CDI bean always gets a client proxy.
 *
 * <p>{@link #placeOrder} is THE CQRS write model's command handler (DRQ-073):
 * validate the customer, reserve every line over gRPC via {@link
 * RemoteInventoryClient} (unchanged from S4), persist the {@link Order}
 * {@code PENDING}, and -- net-new for this step, closing the gap S4's
 * javadoc documented as deferred -- write {@code order.placed} to this
 * service's OWN transactional outbox ({@link OrderOutboxEvent}) in the SAME
 * {@code @Transactional} as the order row. Either both rows commit, or
 * neither does: no dual-write (DRQ-073). {@link OrderOutboxRelay} then
 * publishes the row to Kafka asynchronously, replacing the monolith's {@code
 * common.outbox.OutboxRelay} as the external producer of this topic.
 *
 * <p><b>The pre-handoff compensation catch (DRQ-042), moved in UNCHANGED:</b>
 * the surrounding {@code try/catch} compensates a PRE-HANDOFF failure -- a
 * later checkout line out of stock, or the order/outbox write itself
 * throwing -- by releasing every sku ALREADY reserved this checkout, exactly
 * the monolith's {@code OrderService#placeOrder} shape (see that class's
 * javadoc, and {@code OrderSagaListener}'s for why this catch and the four
 * saga reactions' compensations can never double-fire on the same
 * reservation: this catch only runs for a checkout that never reached {@code
 * order.placed} being committed; the reactions only run for an order that
 * DID).
 *
 * <p><b>ch.26 S6 (DRQ-067/074) -- the CQRS read model:</b> {@link #getById}/
 * {@link #listAll} are repointed to read EXCLUSIVELY from the denormalized
 * {@link OrderView} read model via {@link OrderViewRepository} -- NEVER from
 * {@link OrderRepository} (the write-model aggregate). {@link #placeOrder}
 * calls {@link OrderViewProjector#project} in the SAME {@code
 * @Transactional} as the {@link Order} row write, to create the initial
 * {@code PENDING} projection; {@link OrderSagaListener}'s four reactions
 * project every subsequent lifecycle transition the same way. See {@link
 * OrderViewProjector}'s javadoc for the full write-path/read-path story.
 * {@link #placeOrder}'s OWN return value is the command's immediate echo of
 * the aggregate it just created/committed -- not a system "read" -- so it
 * is built directly from {@code order} (not re-queried from the view);
 * {@code GET /api/orders}/{@code GET /api/orders/{id}} are this service's
 * only reads.
 */
@ApplicationScoped
public class OrderService {

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    /** Aggregate type recorded on every {@link OrderOutboxEvent} this service writes. */
    static final String AGGREGATE_TYPE = "order";

    static final String ORDER_PLACED_EVENT_TYPE = "order.placed";

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RemoteInventoryClient remoteInventoryClient;
    private final OrderOutboxRepository outboxRepository;
    private final OrderViewRepository orderViewRepository;
    private final OrderViewProjector orderViewProjector;
    private final ObjectMapper objectMapper;

    public OrderService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            RemoteInventoryClient remoteInventoryClient,
            OrderOutboxRepository outboxRepository,
            OrderViewRepository orderViewRepository,
            OrderViewProjector orderViewProjector,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.remoteInventoryClient = remoteInventoryClient;
        this.outboxRepository = outboxRepository;
        this.orderViewRepository = orderViewRepository;
        this.orderViewProjector = orderViewProjector;
        this.objectMapper = objectMapper;
    }

    /**
     * Flow: validate customer -&gt; check+reserve stock (inventory, over
     * gRPC) -&gt; persist the order {@code PENDING} -&gt; write the {@code
     * order.placed} outbox event (the handoff to the choreographed saga) -&gt;
     * return. Payment capture and order confirmation/shipping dispatch
     * happen OUT OF PROCESS and asynchronously, driven by {@link
     * OrderSagaListener} reacting to the payment/shipping services' outcome
     * events.
     *
     * <p>r05/ch.19-lineage (DRQ-042/043, preserved unchanged): each line's
     * sku/name/unit-price-at-order-time is captured as a denormalized
     * snapshot from the inventory service's {@code GetStock} gRPC reply (not
     * a local join), and any EARLIER line's reservation is compensated via
     * {@code Release} if a LATER line fails, or the order/outbox write
     * itself throws, before propagating the original failure. No-op when
     * nothing was reserved yet.
     */
    @Transactional
    public OrderDto placeOrder(OrderCreate command) {
        Customer customer = customerRepository
                .findByIdOptional(command.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));

        Order order = new Order(customer.getId(), customer.getEmail(), command.shippingAddress());

        List<ReservedLine> remoteReservations = new ArrayList<>();

        try {
            for (OrderCreate.Line line : command.items()) {
                RemoteInventoryClient.ReserveResult result =
                        remoteInventoryClient.reserve(line.sku(), line.quantity());
                if (!result.ok()) {
                    throw new InsufficientStockException("Requested %d of %s but only %d on hand"
                            .formatted(line.quantity(), line.sku(), result.onHandQty()));
                }
                remoteReservations.add(new ReservedLine(line.sku(), line.quantity()));
                RemoteInventoryClient.StockSnapshot stock = remoteInventoryClient.getStock(line.sku());
                OrderItem orderItem = new OrderItem(stock.sku(), stock.name(), line.quantity(), stock.priceCents());
                order.addItem(orderItem);
            }

            orderRepository.persist(order);
            // ch.26 S6 (DRQ-067/074): the CQRS read model's initial PENDING
            // row, projected in the SAME transaction as the order/outbox
            // writes above -- no dual-write, no window where the order
            // exists but order_view does not.
            orderViewProjector.project(order, null, null);
            writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());
            return toDto(order);
        } catch (RuntimeException ex) {
            compensateRemoteReservations(remoteReservations);
            throw ex;
        }
    }

    /**
     * Builds the {@code order.placed} payload and persists it as an {@link
     * OrderOutboxEvent} row via the SAME {@link OrderOutboxRepository} {@link
     * OrderOutboxRelay} polls -- called from inside {@link #placeOrder}'s
     * {@code @Transactional}, so this write is part of the checkout
     * transaction, not a separate dual-write after commit (DRQ-073).
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
            outboxRepository.persist(new OrderOutboxEvent(
                    AGGREGATE_TYPE, String.valueOf(order.getId()), ORDER_PLACED_EVENT_TYPE, payload));
        } catch (JsonProcessingException e) {
            // A record of primitives/Strings/Instant never fails to
            // serialize in practice; wrapping keeps placeOrder's signature
            // unchanged and still fails (and rolls back) the checkout rather
            // than silently dropping the saga handoff -- caught by this
            // method's own try/catch, which compensates every reservation
            // already made this checkout.
            throw new IllegalStateException("Failed to serialize OrderPlacedEvent for order " + order.getId(), e);
        }
    }

    /**
     * Compensating {@code Release} for every sku {@link #placeOrder}
     * reserved remotely before a pre-handoff checkout failure. Best-effort: a
     * {@code Release} failure is logged loudly but does not replace the
     * ORIGINAL checkout failure being propagated to the caller — lifted
     * unchanged from the monolith's documented limitation (no idempotency
     * key or saga ledger in this service).
     */
    private void compensateRemoteReservations(List<ReservedLine> remoteReservations) {
        for (ReservedLine reserved : remoteReservations) {
            try {
                remoteInventoryClient.release(reserved.sku(), reserved.quantity());
            } catch (RuntimeException releaseFailure) {
                LOG.errorf(
                        releaseFailure,
                        "compensation FAILED for sku=%s qty=%d — stock NOT restored "
                                + "(DRQ-042: no saga ledger/retry)",
                        reserved.sku(), reserved.quantity());
            }
        }
    }

    /** One remotely-reserved checkout line pending compensation if checkout fails. */
    private record ReservedLine(String sku, int quantity) {
    }

    /**
     * ch.26 S6 (DRQ-067): reads EXCLUSIVELY from the {@link OrderView} read
     * model via {@link #orderViewRepository} -- NEVER {@link
     * #orderRepository} (see {@link OrderViewRepository}'s javadoc). {@code
     * @Transactional} is kept for symmetry/repository-access safety even
     * though {@link OrderView} has no lazy collection to keep a session open
     * for (unlike {@link Order#items}, the reason the PRE-S6 version of this
     * method needed it).
     */
    @Transactional
    public OrderDto getById(Long id) {
        return toDto(orderViewRepository
                .findByIdOptional(id)
                .orElseThrow(() -> new ResourceNotFoundException("No order with id " + id)));
    }

    /** See {@link #getById}'s javadoc -- reads EXCLUSIVELY from {@link OrderViewRepository}. */
    @Transactional
    public List<OrderDto> listAll() {
        return orderViewRepository.listAll().stream().map(this::toDto).toList();
    }

    /**
     * Builds {@link OrderDto} directly from the just-persisted {@link
     * Order} aggregate -- used ONLY by {@link #placeOrder} for its own
     * command-response echo (not a system "read"; see this class's
     * javadoc). {@link #getById}/{@link #listAll} use {@link
     * #toDto(OrderView)} below instead.
     */
    private static OrderDto toDto(Order order) {
        List<OrderDto.Item> items = order.getItems().stream()
                .map(i -> new OrderDto.Item(i.getSku(), i.getQuantity(), i.getUnitPriceCents()))
                .toList();
        return new OrderDto(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalCents(),
                order.getCreatedAt(),
                order.getShippingAddress(),
                items);
    }

    /**
     * Builds {@link OrderDto} from the {@link OrderView} read-model row --
     * BYTE-FOR-BYTE the same external shape {@link #toDto(Order)} produces
     * (the Order Context Contract depends on this). {@link
     * OrderView#getItems()}'s JSON array deserializes straight into {@link
     * OrderDto.Item} (field names match exactly: sku/quantity/
     * unitPriceCents).
     */
    private OrderDto toDto(OrderView view) {
        List<OrderDto.Item> items;
        try {
            items = objectMapper.readValue(view.getItems(), new TypeReference<List<OrderDto.Item>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize order_view items for order " + view.getOrderId(), e);
        }
        return new OrderDto(
                view.getOrderId(),
                view.getCustomerId(),
                OrderStatus.valueOf(view.getStatus()),
                view.getTotalCents(),
                view.getCreatedAt(),
                view.getShippingAddress(),
                items);
    }
}
