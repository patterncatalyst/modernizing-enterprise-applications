package dev.patterncatalyst.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit), ch.26 S5 (DRQ-074)/S6 (DRQ-067/074). All four reactions
 * LIFTED from the monolith's {@code order.OrderSagaListener} are retested
 * here at the unit level with mocked collaborators, for the guarantees that
 * class's javadoc documents: status-guard idempotency, at-most-once
 * compensating {@code Release}, mutual exclusion between the
 * decline/shipment-failed paths, {@code CONFIRMED} reachable only via
 * {@link OrderSagaListener#onShipmentDispatched} -- and, net-new for S6,
 * that each reaction's read-model projection fires ONLY on the one
 * transition it guards (never on a redelivered/stray no-op), proving the
 * idempotency guard protects BOTH the aggregate and the read model
 * identically. The full Reactive Messaging + real-DB round trip (including
 * redelivery through the actual {@code @Incoming} pipeline, and the
 * resulting {@code order_view} row) is covered by {@code
 * OrderSagaListenerIntegrationTest}/{@code OrderViewProjectionTest}.
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaListenerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RemoteInventoryClient remoteInventoryClient;

    @Mock
    private OrderViewProjector orderViewProjector;

    private OrderSagaListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderSagaListener(orderRepository, remoteInventoryClient, orderViewProjector);
    }

    private static Order pendingOrderWithItems(String... skuQtyPairs) {
        Order order = new Order(1L, "ada@example.com", "1 Test Way");
        for (int i = 0; i < skuQtyPairs.length; i += 2) {
            order.addItem(new OrderItem(skuQtyPairs[i], "Item", Integer.parseInt(skuQtyPairs[i + 1]), 1000L));
        }
        return order;
    }

    // -- onPaymentCaptured --------------------------------------------------

    @Test
    void onPaymentCaptured_pendingOrder_transitionsToAwaitingShipment_neverConfirmsDirectly() {
        Order order = pendingOrderWithItems("SKU-A", "1");
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onPaymentCaptured(new PaymentCaptured(1L, 10L, 1000L, "CARD-VISA", "CAPTURED", Instant.now()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_SHIPMENT);
        // ch.26 S6 (DRQ-067): the read model is projected on the transition,
        // with the newly-known payment outcome and no shipment outcome yet.
        verify(orderViewProjector).project(order, "CAPTURED", null);
    }

    @Test
    void onPaymentCaptured_redeliveredAfterAlreadyAwaitingShipment_isNoOp() {
        Order order = pendingOrderWithItems("SKU-A", "1");
        order.awaitShipment();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onPaymentCaptured(new PaymentCaptured(1L, 10L, 1000L, "CARD-VISA", "CAPTURED", Instant.now()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_SHIPMENT);
        // ch.26 S6 (DRQ-067): the status guard protects the projection too
        // -- a redelivered event past the guarded state never re-projects.
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    @Test
    void onPaymentCaptured_unknownOrder_doesNotThrow() {
        when(orderRepository.findByIdOptional(999L)).thenReturn(Optional.empty());

        listener.onPaymentCaptured(new PaymentCaptured(999L, 10L, 1000L, "CARD-VISA", "CAPTURED", Instant.now()));
        // no exception propagated -- consumer stays alive for the next message.
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    // -- onShipmentDispatched -------------------------------------------------

    @Test
    void onShipmentDispatched_awaitingShipmentOrder_transitionsToConfirmed() {
        Order order = pendingOrderWithItems("SKU-A", "1");
        order.awaitShipment();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onShipmentDispatched(new ShipmentDispatched(1L, 20L, "1 Test Way", "DISPATCHED", Instant.now()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderViewProjector).project(order, null, "DISPATCHED");
    }

    @Test
    void onShipmentDispatched_redeliveredAfterAlreadyConfirmed_isNoOp() {
        Order order = pendingOrderWithItems("SKU-A", "1");
        order.awaitShipment();
        order.confirm();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onShipmentDispatched(new ShipmentDispatched(1L, 20L, "1 Test Way", "DISPATCHED", Instant.now()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    @Test
    void onShipmentDispatched_pendingOrder_notYetAwaitingShipment_isNoOp_neverConfirms() {
        // CONFIRMED must be reachable ONLY via a prior AWAITING_SHIPMENT.
        Order order = pendingOrderWithItems("SKU-A", "1");
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onShipmentDispatched(new ShipmentDispatched(1L, 20L, "1 Test Way", "DISPATCHED", Instant.now()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    @Test
    void onShipmentDispatched_unknownOrder_doesNotThrow() {
        when(orderRepository.findByIdOptional(999L)).thenReturn(Optional.empty());

        listener.onShipmentDispatched(new ShipmentDispatched(999L, 20L, "addr", "DISPATCHED", Instant.now()));
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    // -- onShipmentFailed -----------------------------------------------------

    @Test
    void onShipmentFailed_awaitingShipmentOrder_transitionsToShippingFailed_releasesEverySku() {
        Order order = pendingOrderWithItems("SKU-A", "2", "SKU-B", "3");
        order.awaitShipment();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onShipmentFailed(new ShipmentFailed(1L, 30L, "1 Test Way", "FAILED", Instant.now(), "carrier down"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING_FAILED);
        verify(remoteInventoryClient).release("SKU-A", 2);
        verify(remoteInventoryClient).release("SKU-B", 3);
        verify(orderViewProjector).project(order, null, "FAILED");
    }

    @Test
    void onShipmentFailed_redeliveredAfterAlreadyShippingFailed_isNoOp_releaseNotReissued() {
        Order order = pendingOrderWithItems("SKU-A", "2");
        order.awaitShipment();
        order.failShipping();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onShipmentFailed(new ShipmentFailed(1L, 30L, "1 Test Way", "FAILED", Instant.now(), "carrier down"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING_FAILED);
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    @Test
    void onShipmentFailed_partialReleaseFailure_doesNotThrow_stillAttemptsRemainingSkus() {
        Order order = pendingOrderWithItems("SKU-A", "2", "SKU-B", "3");
        order.awaitShipment();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));
        doThrow(new RuntimeException("inventory unavailable"))
                .when(remoteInventoryClient)
                .release("SKU-A", 2);

        listener.onShipmentFailed(new ShipmentFailed(1L, 30L, "1 Test Way", "FAILED", Instant.now(), "carrier down"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPING_FAILED);
        verify(remoteInventoryClient).release("SKU-A", 2);
        verify(remoteInventoryClient).release("SKU-B", 3);
        verify(orderViewProjector).project(order, null, "FAILED");
    }

    @Test
    void onShipmentFailed_unknownOrder_doesNotThrow() {
        when(orderRepository.findByIdOptional(999L)).thenReturn(Optional.empty());

        listener.onShipmentFailed(new ShipmentFailed(999L, 30L, "addr", "FAILED", Instant.now(), "reason"));
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    // -- onPaymentDeclined ------------------------------------------------------

    @Test
    void onPaymentDeclined_pendingOrder_transitionsToPaymentDeclined_releasesEverySku() {
        Order order = pendingOrderWithItems("SKU-A", "2", "SKU-B", "3");
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onPaymentDeclined(
                new PaymentDeclined(1L, 10L, 5000L, "CARD-DECLINE", "DECLINED", "insufficient funds", Instant.now()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_DECLINED);
        verify(remoteInventoryClient).release("SKU-A", 2);
        verify(remoteInventoryClient).release("SKU-B", 3);
        verify(orderViewProjector).project(order, "DECLINED", null);
    }

    @Test
    void onPaymentDeclined_redeliveredAfterAlreadyDeclined_isNoOp_releaseNotReissued() {
        Order order = pendingOrderWithItems("SKU-A", "2");
        order.declinePayment();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(order));

        listener.onPaymentDeclined(
                new PaymentDeclined(1L, 10L, 5000L, "CARD-DECLINE", "DECLINED", "insufficient funds", Instant.now()));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_DECLINED);
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    @Test
    void onPaymentDeclined_unknownOrder_doesNotThrow() {
        when(orderRepository.findByIdOptional(999L)).thenReturn(Optional.empty());

        listener.onPaymentDeclined(
                new PaymentDeclined(999L, 10L, 5000L, "CARD-DECLINE", "DECLINED", "reason", Instant.now()));
        verify(orderViewProjector, never()).project(any(), any(), any());
    }

    // -- mutual exclusion (H3) ----------------------------------------------

    @Test
    void mutualExclusion_awaitingShipmentOrderIgnoresPaymentDeclined_pendingOrderIgnoresShipmentFailed() {
        // An order that already moved past PENDING (payment succeeded) must
        // never be reachable by the decline reaction...
        Order awaitingShipment = pendingOrderWithItems("SKU-A", "1");
        awaitingShipment.awaitShipment();
        when(orderRepository.findByIdOptional(1L)).thenReturn(Optional.of(awaitingShipment));

        listener.onPaymentDeclined(
                new PaymentDeclined(1L, 10L, 1000L, "CARD-DECLINE", "DECLINED", "stray/redelivered", Instant.now()));

        assertThat(awaitingShipment.getStatus()).isEqualTo(OrderStatus.AWAITING_SHIPMENT);
        verify(remoteInventoryClient, never()).release(anyString(), anyInt());

        // ...and an order still PENDING (payment never captured) must never
        // be reachable by the shipment-failed reaction.
        Order stillPending = pendingOrderWithItems("SKU-B", "1");
        when(orderRepository.findByIdOptional(2L)).thenReturn(Optional.of(stillPending));

        listener.onShipmentFailed(
                new ShipmentFailed(2L, 30L, "addr", "FAILED", Instant.now(), "stray/redelivered"));

        assertThat(stillPending.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(remoteInventoryClient, times(0)).release("SKU-B", 1);
        verify(orderViewProjector, never()).project(any(), any(), any());
    }
}
