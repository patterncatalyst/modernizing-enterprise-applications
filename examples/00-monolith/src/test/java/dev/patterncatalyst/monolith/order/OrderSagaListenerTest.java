package dev.patterncatalyst.monolith.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.OrderStatus;
import dev.patterncatalyst.monolith.common.events.PaymentCaptured;
import dev.patterncatalyst.monolith.common.events.PaymentDeclined;
import dev.patterncatalyst.monolith.inventory.RemoteInventoryClient;
import dev.patterncatalyst.monolith.shipping.Shipment;
import dev.patterncatalyst.monolith.shipping.ShipmentStatus;
import dev.patterncatalyst.monolith.shipping.ShippingService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit): {@code OrderSagaListener} is the monolith's first Kafka
 * consumer (ch.23/r06/S6, H4) — the choreographed-saga reaction to {@code
 * payment.captured}/{@code payment.declined}. All collaborators are mocked;
 * this test never touches Kafka or a database, and calls the {@code
 * @KafkaListener}-annotated methods directly with a hand-built JSON payload
 * (exactly what Spring Kafka would hand the method body after its
 * StringDeserializer, per {@code application.yml}'s consumer config).
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaListenerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RemoteInventoryClient remoteInventoryClient;

    @Mock
    private ShippingService shippingService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderSagaListener listener;
    private Customer customer;

    @BeforeEach
    void setUp() {
        listener = new OrderSagaListener(orderRepository, remoteInventoryClient, shippingService, objectMapper);
        customer = new Customer("Ada Lovelace", "ada@example.com");
    }

    private Order pendingOrderWithId(long id, OrderItem... items) {
        Order order = new Order(customer, "1 Test Way");
        for (OrderItem item : items) {
            order.addItem(item);
        }
        Order spyOrder = spy(order);
        when(spyOrder.getId()).thenReturn(id);
        return spyOrder;
    }

    private String json(Object event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    // ---- payment.captured ----

    @Test
    void onPaymentCaptured_pendingOrder_confirmsAndDispatchesShipping() throws Exception {
        Order order = pendingOrderWithId(42L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(shippingService.dispatch(any(Order.class), anyString()))
                .thenAnswer(inv -> new Shipment(inv.getArgument(0), inv.getArgument(1), ShipmentStatus.DISPATCHED));

        String payload = json(new PaymentCaptured(42L, 100L, 3998L, "CARD-VISA", "CAPTURED", Instant.now()));

        listener.onPaymentCaptured(payload);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository).save(order);
        verify(shippingService).dispatch(order, "1 Test Way");
    }

    @Test
    void onPaymentCaptured_redeliveredForAlreadyConfirmedOrder_isNoOp() throws Exception {
        Order order = pendingOrderWithId(42L);
        order.confirm(); // simulate the first delivery already having landed
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        String payload = json(new PaymentCaptured(42L, 100L, 3998L, "CARD-VISA", "CAPTURED", Instant.now()));

        listener.onPaymentCaptured(payload);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository, never()).save(any());
        verify(shippingService, never()).dispatch(any(), anyString());
    }

    @Test
    void onPaymentCaptured_unknownOrderId_logsAndDoesNotThrow() throws Exception {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        String payload = json(new PaymentCaptured(999L, 100L, 3998L, "CARD-VISA", "CAPTURED", Instant.now()));

        listener.onPaymentCaptured(payload); // must not throw

        verify(orderRepository, never()).save(any());
        verify(shippingService, never()).dispatch(any(), anyString());
    }

    // ---- payment.declined ----

    @Test
    void onPaymentDeclined_pendingOrder_declinesAndReleasesEverySku() throws Exception {
        OrderItem lineA = new OrderItem("SKU-WIDGET-001", "Standard Widget", 2, 1999L);
        OrderItem lineB = new OrderItem("SKU-GADGET-002", "Deluxe Gadget", 1, 4999L);
        Order order = pendingOrderWithId(43L, lineA, lineB);
        when(orderRepository.findById(43L)).thenReturn(Optional.of(order));

        String payload = json(new PaymentDeclined(
                43L, 101L, 8997L, "CARD-DECLINE", "DECLINED", "demo decline rule", Instant.now()));

        listener.onPaymentDeclined(payload);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_DECLINED);
        verify(orderRepository).save(order);
        verify(remoteInventoryClient).release("SKU-WIDGET-001", 2);
        verify(remoteInventoryClient).release("SKU-GADGET-002", 1);
    }

    @Test
    void onPaymentDeclined_redeliveredForAlreadyDeclinedOrder_isNoOpAndReleaseNotReissued() throws Exception {
        OrderItem line = new OrderItem("SKU-WIDGET-001", "Standard Widget", 2, 1999L);
        Order order = pendingOrderWithId(43L, line);
        order.declinePayment(); // simulate the first delivery already having landed

        when(orderRepository.findById(43L)).thenReturn(Optional.of(order));

        String payload = json(new PaymentDeclined(
                43L, 101L, 3998L, "CARD-DECLINE", "DECLINED", "demo decline rule", Instant.now()));

        listener.onPaymentDeclined(payload);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_DECLINED);
        verify(orderRepository, never()).save(any());
        // the FIRST delivery (simulated above by calling declinePayment() directly,
        // bypassing the listener) never went through the listener either, so this
        // assertion proves the REDELIVERY specifically issues zero further Releases.
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
    }

    @Test
    void onPaymentDeclined_releaseFailsForOneSku_otherSkusStillReleasedAndFailureIsLoggedNotThrown() throws Exception {
        OrderItem lineA = new OrderItem("SKU-WIDGET-001", "Standard Widget", 2, 1999L);
        OrderItem lineB = new OrderItem("SKU-GADGET-002", "Deluxe Gadget", 1, 4999L);
        Order order = pendingOrderWithId(44L, lineA, lineB);
        when(orderRepository.findById(44L)).thenReturn(Optional.of(order));
        org.mockito.Mockito.doThrow(new RuntimeException("inventory service unavailable"))
                .when(remoteInventoryClient).release("SKU-WIDGET-001", 2);

        String payload = json(new PaymentDeclined(
                44L, 102L, 8997L, "CARD-DECLINE", "DECLINED", "demo decline rule", Instant.now()));

        listener.onPaymentDeclined(payload); // must not throw despite the release failure

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_DECLINED);
        verify(remoteInventoryClient, times(1)).release("SKU-WIDGET-001", 2);
        verify(remoteInventoryClient).release("SKU-GADGET-002", 1);
    }

    @Test
    void onPaymentDeclined_unknownOrderId_logsAndDoesNotThrow() throws Exception {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        String payload = json(new PaymentDeclined(
                999L, 101L, 3998L, "CARD-DECLINE", "DECLINED", "demo decline rule", Instant.now()));

        listener.onPaymentDeclined(payload); // must not throw

        verify(orderRepository, never()).save(any());
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
    }
}
