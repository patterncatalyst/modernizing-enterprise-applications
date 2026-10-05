package dev.patterncatalyst.monolith.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.CustomerRepository;
import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.OrderStatus;
import dev.patterncatalyst.monolith.common.Topics;
import dev.patterncatalyst.monolith.common.exception.InsufficientStockException;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.common.outbox.OutboxEvent;
import dev.patterncatalyst.monolith.common.outbox.OutboxRepository;
import dev.patterncatalyst.monolith.inventory.RemoteInventoryClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit): {@code OrderService} is the god service (SMELL[ch.26]) and the
 * one in-process ACID transaction (SMELL[ch.22]) — it is the single most
 * logic-bearing service in the monolith, so it gets the most thorough unit
 * coverage: happy path (now PENDING + outbox handoff), out-of-stock, customer-
 * not-found, and (r05/ch.19 S7/S11, still the crux post r06/ch.23 S9) the gRPC
 * reserve/release PRE-HANDOFF compensation seam. All collaborators are mocked;
 * this test never touches a database or a real gRPC channel.
 *
 * <p>r05/ch.19 S11 (DECOMMISSION): the monolith's local in-JVM
 * {@code InventoryService}/{@code InventoryItem} mocks have been removed
 * along with the {@code inventory.mode=local|remote} flag — {@link
 * RemoteInventoryClient} (gRPC) is now the ONLY collaborator {@code
 * OrderService} uses to reserve/release/read stock, so every test below
 * exercises that one path.
 *
 * <p>r06/ch.23 S9 (DECOMMISSION): the {@code payment.mode=synchronous|
 * choreographed} flag, the {@code paymentService}/{@code shippingService}
 * mocks, and every test exercising the now-deleted synchronous in-line
 * charge (including the synchronous payment-decline compensation test) are
 * gone — {@code OrderService} no longer takes those collaborators at all.
 * {@code placeOrder} always persists {@code PENDING} and hands off via the
 * outbox; the payment-decline compensation this class used to also cover is
 * now {@link OrderSagaListener#onPaymentDeclined}'s job (see {@code
 * OrderSagaListenerTest}). The test below named {@code
 * placeOrder_reserveFailsOnSecondLine_compensatesFirstLine} is THE
 * regression check that decommissioning the synchronous path did not also
 * remove the PRE-HANDOFF reserve-failure compensation — it must keep
 * passing.
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
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderService orderService;
    private Customer customer;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository, customerRepository, remoteInventoryClient, outboxRepository, objectMapper);
        customer = new Customer("Ada Lovelace", "ada@example.com");
    }

    @Test
    void placeOrder_happyPath_staysPendingReservesStockAndWritesOrderPlacedOutboxEvent() throws Exception {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            // simulate the DB assigning an id, since OrderPlacedEvent needs it
            Order spy = org.mockito.Mockito.spy(saved);
            when(spy.getId()).thenReturn(42L);
            return spy;
        });

        OrderDto dto = orderService.placeOrder(command);

        // ch.23 (r06/S9, DRQ-047): checkout NEVER confirms or charges
        // in-line anymore — it always stays PENDING, handed off to the
        // choreographed saga via the order.placed outbox event.
        assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(dto.totalCents()).isEqualTo(1999L * 2);
        assertThat(dto.items()).hasSize(1);
        assertThat(dto.items().get(0).sku()).isEqualTo("SKU-WIDGET-001");

        verify(remoteInventoryClient).reserve("SKU-WIDGET-001", 2);
        // happy path never issues a compensating Release.
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());

        // ch.17 cure (r04/S8), extended by ch.23 (r06/S9): checkout ALWAYS
        // writes an order.placed outbox row now — it is the ONLY handoff,
        // to both notification (unchanged since r04) and payment (since
        // r06/S9, no more in-line charge to skip this write for).
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo("42");
        assertThat(event.getEventType()).isEqualTo(Topics.ORDER_PLACED);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).contains("\"orderId\":42").contains("ada@example.com");
        // the handoff payload must carry paymentMethod for the payment
        // service's DECLINE-in-method demo rule (DRQ-048).
        assertThat(event.getPayload()).contains("\"paymentMethod\":\"CARD-VISA\"");
    }

    @Test
    void placeOrder_outOfStock_throws409EquivalentAndNeverReleases() {
        // r05/ch.19 S7/S11 (DRQ-041): server-side Reserve reports insufficient
        // stock (reservation_ok=false, on_hand_qty unchanged) -- a 409 via
        // InsufficientStockException, and because NOTHING was reserved, no
        // compensating Release should ever be issued.
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-GIZMO-003", 99)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-GIZMO-003", 99))
                .thenReturn(new RemoteInventoryClient.ReserveResult(false, 5));

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-GIZMO-003");

        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        // r05/ch.19 S8 (DRQ-043): insufficient stock means no snapshot is ever fetched.
        verify(remoteInventoryClient, never()).getStock(anyString());
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
    }

    @Test
    void placeOrder_reserveFailsOnSecondLine_compensatesFirstLine() {
        // THE crux regression check for r06/ch.23 S9 (DRQ-049): decommissioning
        // the synchronous payment path and narrowing the catch's purpose must
        // NOT also remove the PRE-HANDOFF reserve-failure compensation. Line 1
        // reserves successfully (committed in the inventory service's own
        // database); line 2 is out of stock, so placeOrder throws BEFORE the
        // order is ever saved or handed off via order.placed -- no saga is
        // ever started for this checkout, so OrderSagaListener will never see
        // it. The surrounding try/catch in OrderService#placeOrder MUST still
        // release line 1's reservation itself, or this would be a stock leak
        // introduced by the decommission.
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

        // the earlier, already-committed reservation IS released...
        verify(remoteInventoryClient).release("SKU-WIDGET-001", 2);
        // ...and no saga was ever started for this checkout (no save, no
        // outbox write) -- so OrderSagaListener#onPaymentDeclined can never
        // ALSO try to release the same sku (there is nothing for it to react
        // to: no order.placed event was ever written).
        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
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
}
