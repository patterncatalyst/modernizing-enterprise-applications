package dev.patterncatalyst.notification;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;

/**
 * NET-NEW for ch.17 S5 — a minimal push-notification socket (adapted from
 * datamesh's {@code OrderNotificationSocket}): a client connects here to be
 * told, in real time, when this service has consumed a new
 * {@code order.placed} event off Kafka. Intentionally minimal: this endpoint
 * only acknowledges the connection ({@code @OnOpen}); the actual push
 * happens from {@link OrderPlacedPushConsumer}, a second, independent Kafka
 * consumer of the same topic (per-replica unique group) that broadcasts to
 * every open connection registered in {@code io.quarkus.websockets.next.
 * OpenConnections}.
 */
@WebSocket(path = "/ws/notifications")
public class OrderNotificationSocket {

    @OnOpen
    public String onOpen() {
        return "{\"type\":\"connected\"}";
    }
}
