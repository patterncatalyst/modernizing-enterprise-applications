package dev.patterncatalyst.order;

import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * Lifted from the monolith's {@code order.OrderService} (ch.26 S4,
 * DRQ-068/073, Phase A) — the read+command surface via Quarkiverse
 * Spring-compat. {@code @Scope("application")} is an added-during-lift
 * adjustment (maps to CDI {@code @ApplicationScoped} per the migration
 * skill's annotation map) so this bean is normal-scoped and therefore
 * mockable with {@code @InjectMock} in {@code OrderControllerTest} — plain
 * {@code @Service} alone compiles to a {@code @Singleton} pseudo-scope bean,
 * which Quarkus cannot mock (no client proxy to swap).
 *
 * <p><b>Divergence from the monolith's {@code @Transactional}:</b> this class
 * uses {@code jakarta.transaction.Transactional}, NOT Spring's {@code
 * org.springframework.transaction.annotation.Transactional} — per the
 * migration skill's annotation map, quarkus-spring-data-jpa does not process
 * Spring's own annotation; jakarta's is required even under compat.
 *
 * <p><b>What's the SAME as the monolith's lifted shape:</b> {@link
 * #placeOrder} validates the customer, reserves every line synchronously over
 * gRPC via {@link RemoteInventoryClient} (now a quarkus-grpc client, DRQ-068/
 * 073 scaffold), persists the order {@code PENDING}, and — on a pre-handoff
 * failure (a later line out of stock, or the save itself throwing) —
 * compensates every reservation already committed this checkout, exactly the
 * monolith's try/catch shape.
 *
 * <p><b>What's deliberately NOT here (deferred to S5/S6, per this step's
 * scope):</b> the monolith's {@code placeOrder} ALSO writes an {@code
 * order.placed} outbox event as its handoff to the choreographed saga — that
 * write (and the {@code OrderOutboxEvent}/{@code OrderOutboxRelay} machinery
 * backing it, and the event contract itself) is explicitly S3/S5 scope, not
 * this step's. This service's Flyway migrations already reserve the {@code
 * outbox} table (empty, unwritten) so S5 can add the Java-side wiring without
 * a schema change. Likewise, no saga reactions exist yet to ever move an
 * order OUT of {@code PENDING} (S5), and no read-model projection exists yet
 * to populate the reserved {@code order_view} table (S6) — an order placed
 * against this Phase A service will sit {@code PENDING} forever until those
 * steps land, which is the expected, documented state of an isolated Phase A
 * scaffold, not a bug.
 */
@Service
@Scope("application")
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RemoteInventoryClient remoteInventoryClient;

    public OrderService(
            OrderRepository orderRepository,
            CustomerRepository customerRepository,
            RemoteInventoryClient remoteInventoryClient) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.remoteInventoryClient = remoteInventoryClient;
    }

    /**
     * Flow: validate customer -&gt; check+reserve stock (inventory, over gRPC)
     * -&gt; persist the order {@code PENDING} -&gt; return. See this class's
     * javadoc for what the monolith's version ALSO does (the {@code
     * order.placed} outbox handoff) that is deliberately deferred to S5.
     *
     * <p>r05/ch.19-lineage (DRQ-042/043, preserved unchanged): each line's
     * sku/name/unit-price-at-order-time is captured as a denormalized
     * snapshot from the inventory service's {@code GetStock} gRPC reply (not
     * a local join), and any EARLIER line's reservation is compensated via
     * {@code Release} if a LATER line fails or the save throws, before
     * propagating the original failure. No-op when nothing was reserved yet.
     */
    @Transactional
    public OrderDto placeOrder(OrderCreate command) {
        Customer customer = customerRepository.findById(command.customerId())
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

            order = orderRepository.save(order);
            return toDto(order);
        } catch (RuntimeException ex) {
            compensateRemoteReservations(remoteReservations);
            throw ex;
        }
    }

    /**
     * Compensating {@code Release} for every sku {@link #placeOrder}
     * reserved remotely before a pre-handoff checkout failure. Best-effort: a
     * {@code Release} failure is logged loudly but does not replace the
     * ORIGINAL checkout failure being propagated to the caller — lifted
     * unchanged from the monolith's documented limitation (no idempotency
     * key or saga ledger in this service yet; the full saga ledger is S5).
     */
    private void compensateRemoteReservations(List<ReservedLine> remoteReservations) {
        for (ReservedLine reserved : remoteReservations) {
            try {
                remoteInventoryClient.release(reserved.sku(), reserved.quantity());
            } catch (RuntimeException releaseFailure) {
                LOG.error(
                        "compensation FAILED for sku={} qty={} — stock NOT restored "
                                + "(full saga/idempotency deferred to ch.26 S5)",
                        reserved.sku(), reserved.quantity(), releaseFailure);
            }
        }
    }

    /** One remotely-reserved checkout line pending compensation if checkout fails. */
    private record ReservedLine(String sku, int quantity) {
    }

    @Transactional
    public OrderDto getById(Long id) {
        return toDto(orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No order with id " + id)));
    }

    @Transactional
    public List<OrderDto> listAll() {
        return orderRepository.findAll().stream().map(OrderService::toDto).toList();
    }

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
}
