package dev.patterncatalyst.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit), mirroring the monolith's {@code OrderServiceTest} (ch.26 S4,
 * DRQ-068/073, Phase A) minus the outbox-handoff assertions (no {@code
 * order.placed} write exists in this step's scope — see {@link
 * OrderService}'s javadoc for what's deferred to S5). All collaborators are
 * mocked; this test never touches a database or a real gRPC channel.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private RemoteInventoryClient remoteInventoryClient;

    private OrderService orderService;
    private Customer customer;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, customerRepository, remoteInventoryClient);
        customer = TestFixtures.customer(1L, "Ada Lovelace", "ada@example.com");
    }

    @Test
    void placeOrder_happyPath_staysPendingAndReservesStock() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderDto dto = orderService.placeOrder(command);

        // ch.26 S4 (Phase A): checkout always stays PENDING -- no saga
        // reactions exist yet to transition it further (that's S5).
        assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(dto.customerId()).isEqualTo(1L);
        assertThat(dto.totalCents()).isEqualTo(1999L * 2);
        assertThat(dto.items()).hasSize(1);
        assertThat(dto.items().get(0).sku()).isEqualTo("SKU-WIDGET-001");
        assertThat(dto.shippingAddress()).isEqualTo("1 Test Way");

        verify(remoteInventoryClient).reserve("SKU-WIDGET-001", 2);
        // happy path never issues a compensating Release.
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
    }

    @Test
    void placeOrder_outOfStock_throwsInsufficientStockAndNeverReleases() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-GIZMO-003", 99)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-GIZMO-003", 99))
                .thenReturn(new RemoteInventoryClient.ReserveResult(false, 5));

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-GIZMO-003");

        verify(orderRepository, never()).save(any());
        // insufficient stock means no snapshot is ever fetched.
        verify(remoteInventoryClient, never()).getStock(anyString());
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
    }

    @Test
    void placeOrder_reserveFailsOnSecondLine_compensatesFirstLine() {
        // THE pre-handoff compensation regression check, lifted from the
        // monolith: line 1 reserves successfully (committed in the inventory
        // service's own database); line 2 is out of stock, so placeOrder
        // throws BEFORE the order is ever saved. The surrounding try/catch in
        // OrderService#placeOrder must still release line 1's reservation.
        var command = new OrderCreate(
                1L,
                List.of(new OrderCreate.Line("SKU-WIDGET-001", 2), new OrderCreate.Line("SKU-GIZMO-003", 99)),
                "CARD-VISA",
                "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));
        when(remoteInventoryClient.reserve("SKU-GIZMO-003", 99))
                .thenReturn(new RemoteInventoryClient.ReserveResult(false, 5));

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(InsufficientStockException.class);

        verify(remoteInventoryClient).release("SKU-WIDGET-001", 2);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void placeOrder_customerNotFound_throwsAndNeverTouchesInventory() {
        var command = new OrderCreate(
                404L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 1)), "CARD-VISA", "1 Test Way");
        when(customerRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");

        verify(remoteInventoryClient, never()).reserve(anyString(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void getById_notFound_throwsResourceNotFoundException() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void listAll_delegatesToRepositoryAndMapsToDto() {
        Order order = new Order(1L, "ada@example.com", "1 Test Way");
        order.addItem(new OrderItem("SKU-WIDGET-001", "Standard Widget", 1, 1999L));
        when(orderRepository.findAll()).thenReturn(List.of(order));

        List<OrderDto> result = orderService.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).customerId()).isEqualTo(1L);
        assertThat(result.get(0).items()).hasSize(1);
    }
}
