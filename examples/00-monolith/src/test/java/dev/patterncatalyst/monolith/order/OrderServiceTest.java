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
import dev.patterncatalyst.monolith.notification.NotificationService;
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
    private PaymentService paymentService;

    @Mock
    private ShippingService shippingService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderService orderService;
    private Customer customer;
    private InventoryItem widget;

    @BeforeEach
    void setUp() {
        orderService = newOrderService("synchronous");
        customer = new Customer("Ada Lovelace", "ada@example.com");
        widget = new InventoryItem("SKU-WIDGET-001", "Standard Widget", 1999, 100);
    }

    private OrderService newOrderService(String notificationMode) {
        return new OrderService(
                orderRepository, customerRepository, inventoryService, paymentService, shippingService,
                notificationService, outboxRepository, objectMapper, notificationMode);
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
        // notification.mode=synchronous (the default, set up in @BeforeEach):
        // today's unchanged SMELL[ch.17] path — direct in-transaction call,
        // and the outbox is never touched.
        verify(notificationService).sendOrderConfirmation(eq(customer), any(Order.class));
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void placeOrder_outboxMode_writesOrderPlacedOutboxEventInsteadOfSynchronousCall() throws Exception {
        orderService = newOrderService("outbox");
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

        // ch.17 cure (notification.mode=outbox): the synchronous call is
        // skipped entirely and exactly one outbox row is written instead,
        // atomically within this same (mocked) @Transactional method call.
        verify(notificationService, never()).sendOrderConfirmation(any(), any());
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
        verify(notificationService, never()).sendOrderConfirmation(any(), any());
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
        verify(notificationService, never()).sendOrderConfirmation(any(), any());
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
    void getById_notFound_throwsResourceNotFoundException() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }
}
