package dev.patterncatalyst.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit), REFACTORED for ch.26 S5 (Phase B, DRQ-073)/S6 (DRQ-067/074).
 * All collaborators are mocked; this test never touches a database, a real
 * gRPC channel, or Kafka. Net-new S5 cases cover {@link
 * OrderService#placeOrder}'s outbox-write behavior (atomic with the order
 * row, no dual-write) at the unit level with a mocked {@link
 * OrderOutboxRepository} -- the full Reactive Messaging + real-outbox-row
 * round trip is covered by {@code CheckoutOutboxTest}. Net-new S6 cases cover
 * {@link OrderService#placeOrder} calling {@link OrderViewProjector#project}
 * exactly once with the initial PENDING row's null payment/shipment status,
 * and {@link OrderService#getById}/{@link OrderService#listAll} reading
 * EXCLUSIVELY from {@link OrderViewRepository} (never {@link
 * OrderRepository}) -- the full read-after-write/rebuild/idempotency proof
 * against a real database is {@code OrderViewProjectionTest}'s job. The
 * pre-handoff compensation regression checks carried over from Phase A are
 * extended to also assert the outbox AND the read-model projection are NEVER
 * written on a failed checkout.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private RemoteInventoryClient remoteInventoryClient;

    @Mock
    private OrderOutboxRepository outboxRepository;

    @Mock
    private OrderViewRepository orderViewRepository;

    @Mock
    private OrderViewProjector orderViewProjector;

    private OrderService orderService;
    private Customer customer;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository,
                customerRepository,
                remoteInventoryClient,
                outboxRepository,
                orderViewRepository,
                orderViewProjector,
                new ObjectMapper().findAndRegisterModules());
        customer = TestFixtures.customer(1L, "Ada Lovelace", "ada@example.com");
    }

    @Test
    void placeOrder_happyPath_staysPendingAndReservesStock_writesExactlyOneOrderPlacedOutboxEvent() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findByIdOptional(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));

        OrderDto dto = orderService.placeOrder(command);

        // ch.26 S5 (Phase B): checkout stays PENDING synchronously -- the
        // saga reactions (OrderSagaListener) are what eventually move it,
        // out of process, off the order.placed handoff this test proves.
        assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(dto.customerId()).isEqualTo(1L);
        assertThat(dto.totalCents()).isEqualTo(1999L * 2);
        assertThat(dto.items()).hasSize(1);
        assertThat(dto.items().get(0).sku()).isEqualTo("SKU-WIDGET-001");
        assertThat(dto.shippingAddress()).isEqualTo("1 Test Way");

        verify(remoteInventoryClient).reserve("SKU-WIDGET-001", 2);
        // happy path never issues a compensating Release.
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());

        // DRQ-073: exactly one order.placed outbox row, atomic with the
        // order persist (both happen inside the same @Transactional method;
        // this unit test proves the Java-side call shape, not the DB commit
        // atomicity itself -- that's CheckoutOutboxTest's job).
        verify(orderRepository).persist(any(Order.class));
        verify(outboxRepository)
                .persist(argThat((OrderOutboxEvent e) -> e.getEventType().equals("order.placed")
                        && e.getAggregateType().equals("order")
                        && e.getPayload().contains("\"paymentMethod\":\"CARD-VISA\"")));

        // ch.26 S6 (DRQ-067): the initial PENDING projection is written in
        // the SAME @Transactional, with no payment/shipment outcome known
        // yet (both null -- see OrderViewProjector#project's javadoc).
        verify(orderViewProjector).project(any(Order.class), isNull(), isNull());
    }

    @Test
    void placeOrder_outOfStock_throwsInsufficientStockAndNeverReleasesOrWritesOutbox() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-GIZMO-003", 99)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findByIdOptional(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-GIZMO-003", 99))
                .thenReturn(new RemoteInventoryClient.ReserveResult(false, 5));

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-GIZMO-003");

        verify(orderRepository, never()).persist(any(Order.class));
        verify(outboxRepository, never()).persist(any(OrderOutboxEvent.class));
        verify(orderViewProjector, never()).project(any(Order.class), any(), any());
        // insufficient stock means no snapshot is ever fetched.
        verify(remoteInventoryClient, never()).getStock(anyString());
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
    }

    @Test
    void placeOrder_reserveFailsOnSecondLine_compensatesFirstLine_neverWritesOutbox() {
        // THE pre-handoff compensation regression check (DRQ-042), lifted
        // from the monolith unchanged: line 1 reserves successfully
        // (committed in the inventory service's own database); line 2 is
        // out of stock, so placeOrder throws BEFORE the order (or the
        // outbox event) is ever persisted. The surrounding try/catch in
        // OrderService#placeOrder must still release line 1's reservation.
        var command = new OrderCreate(
                1L,
                List.of(new OrderCreate.Line("SKU-WIDGET-001", 2), new OrderCreate.Line("SKU-GIZMO-003", 99)),
                "CARD-VISA",
                "1 Test Way");

        when(customerRepository.findByIdOptional(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));
        when(remoteInventoryClient.reserve("SKU-GIZMO-003", 99))
                .thenReturn(new RemoteInventoryClient.ReserveResult(false, 5));

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(InsufficientStockException.class);

        verify(remoteInventoryClient).release("SKU-WIDGET-001", 2);
        verify(orderRepository, never()).persist(any(Order.class));
        verify(outboxRepository, never()).persist(any(OrderOutboxEvent.class));
        verify(orderViewProjector, never()).project(any(Order.class), any(), any());
    }

    @Test
    void placeOrder_customerNotFound_throwsAndNeverTouchesInventoryOrOutbox() {
        var command = new OrderCreate(
                404L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 1)), "CARD-VISA", "1 Test Way");
        when(customerRepository.findByIdOptional(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");

        verify(remoteInventoryClient, never()).reserve(anyString(), anyInt());
        verify(orderRepository, never()).persist(any(Order.class));
        verify(outboxRepository, never()).persist(any(OrderOutboxEvent.class));
        verify(orderViewProjector, never()).project(any(Order.class), any(), any());
    }

    @Test
    void getById_notFound_throwsResourceNotFoundException() {
        // ch.26 S6 (DRQ-067): getById reads EXCLUSIVELY from
        // OrderViewRepository -- never OrderRepository (the write-model
        // aggregate). Stubbing orderRepository here would be pointless (and
        // Mockito's strict stubbing would flag it as unnecessary).
        when(orderViewRepository.findByIdOptional(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void listAll_delegatesToViewRepositoryAndMapsToDto() throws Exception {
        // ch.26 S6 (DRQ-067): listAll reads EXCLUSIVELY from
        // OrderViewRepository -- never OrderRepository. Builds a real
        // OrderView row (same package -- package-private constructor) the
        // way OrderViewProjector would have, to prove the JSON items column
        // round-trips into OrderDto.Item byte-for-byte.
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String itemsJson = objectMapper.writeValueAsString(List.of(new OrderDto.Item("SKU-WIDGET-001", 1, 1999L)));
        OrderView view = new OrderView(
                1L, 1L, OrderStatus.PENDING.name(), 1999L, Instant.parse("2026-01-07T12:00:00Z"), "1 Test Way",
                itemsJson, null, null);
        when(orderViewRepository.listAll()).thenReturn(List.of(view));

        List<OrderDto> result = orderService.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).customerId()).isEqualTo(1L);
        assertThat(result.get(0).items()).hasSize(1);
        assertThat(result.get(0).items().get(0).sku()).isEqualTo("SKU-WIDGET-001");
        assertThat(result.get(0).items().get(0).quantity()).isEqualTo(1);
        assertThat(result.get(0).items().get(0).unitPriceCents()).isEqualTo(1999L);
    }
}
