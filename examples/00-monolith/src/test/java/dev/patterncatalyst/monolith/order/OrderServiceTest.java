package dev.patterncatalyst.monolith.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import dev.patterncatalyst.monolith.common.exception.PaymentDeclinedException;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.common.outbox.OutboxEvent;
import dev.patterncatalyst.monolith.common.outbox.OutboxRepository;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import dev.patterncatalyst.monolith.inventory.InventoryService;
import dev.patterncatalyst.monolith.inventory.RemoteInventoryClient;
import dev.patterncatalyst.monolith.payment.Payment;
import dev.patterncatalyst.monolith.payment.PaymentService;
import dev.patterncatalyst.monolith.payment.PaymentStatus;
import dev.patterncatalyst.monolith.shipping.Shipment;
import dev.patterncatalyst.monolith.shipping.ShipmentStatus;
import dev.patterncatalyst.monolith.shipping.ShippingService;
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
 * coverage: happy path, out-of-stock, and payment-declined, per the r02 S5 DoD.
 * All collaborators are mocked; this test never touches a database.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private RemoteInventoryClient remoteInventoryClient;

    @Mock
    private PaymentService paymentService;

    @Mock
    private ShippingService shippingService;

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderService orderService;
    private Customer customer;
    private InventoryItem widget;

    @BeforeEach
    void setUp() {
        orderService = newOrderService();
        customer = new Customer("Ada Lovelace", "ada@example.com");
        widget = new InventoryItem("SKU-WIDGET-001", "Standard Widget", 1999, 100);
    }

    private OrderService newOrderService() {
        // r05/ch.19 S7: inventory.mode="local" here keeps every existing test
        // in this class exercising the unchanged in-JVM reserve path;
        // remote-mode + compensation behavior is covered separately below.
        return new OrderService(
                orderRepository, customerRepository, inventoryService, remoteInventoryClient, paymentService,
                shippingService, outboxRepository, objectMapper, "local");
    }

    private OrderService newRemoteOrderService() {
        return new OrderService(
                orderRepository, customerRepository, inventoryService, remoteInventoryClient, paymentService,
                shippingService, outboxRepository, objectMapper, "remote");
    }

    @Test
    void placeOrder_happyPath_confirmsOrderAndOrchestratesAllFiveContexts() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(inventoryService.findBySkuOrThrow("SKU-WIDGET-001")).thenReturn(widget);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentService.charge(any(Order.class), anyLong(), anyString()))
                .thenAnswer(invocation -> new Payment(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        PaymentStatus.CAPTURED));
        when(shippingService.dispatch(any(Order.class), anyString()))
                .thenAnswer(invocation -> new Shipment(invocation.getArgument(0), invocation.getArgument(1),
                        ShipmentStatus.DISPATCHED));

        OrderDto dto = orderService.placeOrder(command);

        assertThat(dto.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(dto.totalCents()).isEqualTo(1999L * 2);
        assertThat(dto.items()).hasSize(1);
        assertThat(dto.items().get(0).sku()).isEqualTo("SKU-WIDGET-001");

        verify(inventoryService).reserve("SKU-WIDGET-001", 2);
        verify(paymentService).charge(any(Order.class), eq(1999L * 2), eq("CARD-VISA"));
        verify(shippingService).dispatch(any(Order.class), eq("1 Test Way"));
        // ch.17 cure (r04/S8): checkout ALWAYS writes an order.placed outbox
        // row now — the synchronous in-transaction notification call (SMELL[ch.17])
        // no longer exists in this module.
        verify(outboxRepository).save(any(OutboxEvent.class));
    }

    @Test
    void placeOrder_alwaysWritesOrderPlacedOutboxEvent() throws Exception {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(inventoryService.findBySkuOrThrow("SKU-WIDGET-001")).thenReturn(widget);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            // simulate the DB assigning an id, since OrderPlacedEvent needs it
            Order spy = org.mockito.Mockito.spy(saved);
            when(spy.getId()).thenReturn(42L);
            return spy;
        });
        when(paymentService.charge(any(Order.class), anyLong(), anyString()))
                .thenAnswer(invocation -> new Payment(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        PaymentStatus.CAPTURED));
        when(shippingService.dispatch(any(Order.class), anyString()))
                .thenAnswer(invocation -> new Shipment(invocation.getArgument(0), invocation.getArgument(1),
                        ShipmentStatus.DISPATCHED));

        orderService.placeOrder(command);

        // ch.17 cure (r04/S8): exactly one outbox row is written, atomically
        // within this same (mocked) @Transactional method call — this is now
        // the only notification path; there is no synchronous call left to
        // skip.
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo("42");
        assertThat(event.getEventType()).isEqualTo(Topics.ORDER_PLACED);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).contains("\"orderId\":42").contains("ada@example.com");
    }

    @Test
    void placeOrder_outOfStock_throwsBeforeAnyWritesOrDownstreamCalls() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-GIZMO-003", 99)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(inventoryService.findBySkuOrThrow("SKU-GIZMO-003")).thenReturn(widget);
        doThrow(new InsufficientStockException("Requested 99 of SKU-GIZMO-003 but only 5 on hand"))
                .when(inventoryService).reserve("SKU-GIZMO-003", 99);

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-GIZMO-003");

        verify(orderRepository, never()).save(any());
        verify(paymentService, never()).charge(any(), anyLong(), anyString());
        verify(shippingService, never()).dispatch(any(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void placeOrder_paymentDeclined_throwsAfterInventoryReservedButBeforeShippingOrNotification() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 1)), "CARD-DECLINE", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(inventoryService.findBySkuOrThrow("SKU-WIDGET-001")).thenReturn(widget);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new PaymentDeclinedException("Payment method 'CARD-DECLINE' was declined"))
                .when(paymentService).charge(any(Order.class), anyLong(), eq("CARD-DECLINE"));

        assertThatThrownBy(() -> orderService.placeOrder(command))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("declined");

        // SMELL[ch.22]: inventory WAS reserved (in-memory) before the decline; in
        // the real flow only the surrounding @Transactional rolls that back. This
        // unit test proves the orchestration order, not the rollback itself — the
        // rollback is exercised by the Testcontainers integration tier.
        verify(inventoryService).reserve("SKU-WIDGET-001", 1);
        verify(shippingService, never()).dispatch(any(), anyString());
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

        verify(inventoryService, never()).findBySkuOrThrow(anyString());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void placeOrder_remoteMode_outOfStock_throws409EquivalentAndNeverReleases() {
        // r05/ch.19 S7 (DRQ-041): inventory.mode=remote, server-side Reserve
        // reports insufficient stock (reservation_ok=false, on_hand_qty
        // unchanged) -- same external contract as the local path: a 409 via
        // InsufficientStockException, and because NOTHING was reserved, no
        // compensating Release should ever be issued.
        OrderService remoteOrderService = newRemoteOrderService();
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-GIZMO-003", 99)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-GIZMO-003", 99))
                .thenReturn(new RemoteInventoryClient.ReserveResult(false, 5));

        assertThatThrownBy(() -> remoteOrderService.placeOrder(command))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-GIZMO-003");

        verify(orderRepository, never()).save(any());
        verify(paymentService, never()).charge(any(), anyLong(), anyString());
        // r05/ch.19 S8 (DRQ-043): insufficient stock means no snapshot is ever fetched.
        verify(remoteInventoryClient, never()).getStock(anyString());
        verify(remoteInventoryClient, never()).release(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void placeOrder_remoteMode_paymentDeclined_compensatesWithRelease() {
        // r05/ch.19 S7 (DRQ-042, THE crux): inventory.mode=remote, Reserve
        // succeeds (so the decrement already committed in the inventory
        // service's own DB, outside this method's @Transactional), then
        // payment declines. The catch block MUST issue a compensating
        // Release for the exact sku/qty this checkout reserved, restoring
        // the observable baseline (Scenario 3 of the equivalence suite).
        OrderService remoteOrderService = newRemoteOrderService();
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 3)), "CARD-DECLINE", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 3))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 97));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new PaymentDeclinedException("Payment method 'CARD-DECLINE' was declined"))
                .when(paymentService).charge(any(Order.class), anyLong(), eq("CARD-DECLINE"));

        assertThatThrownBy(() -> remoteOrderService.placeOrder(command))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("declined");

        verify(remoteInventoryClient).reserve("SKU-WIDGET-001", 3);
        verify(remoteInventoryClient).release("SKU-WIDGET-001", 3);
        verify(shippingService, never()).dispatch(any(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void placeOrder_remoteMode_happyPath_neverCompensates() {
        // Contrast case: a successful remote-mode checkout must NOT issue a
        // compensating Release -- compensation is strictly a failure-path
        // behavior.
        OrderService remoteOrderService = newRemoteOrderService();
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentService.charge(any(Order.class), anyLong(), anyString()))
                .thenAnswer(invocation -> new Payment(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        PaymentStatus.CAPTURED));
        when(shippingService.dispatch(any(Order.class), anyString()))
                .thenAnswer(invocation -> new Shipment(invocation.getArgument(0), invocation.getArgument(1),
                        ShipmentStatus.DISPATCHED));

        OrderDto dto = remoteOrderService.placeOrder(command);

        assertThat(dto.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(remoteInventoryClient, never()).release(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void getById_notFound_throwsResourceNotFoundException() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }
}
