package dev.patterncatalyst.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * NET-NEW for ch.17 S5, adapted from datamesh's
 * {@code OrderNotificationSocketTest}: confirms a client connecting to
 * {@code /ws/notifications} immediately receives the
 * {@code {"type":"connected"}} acknowledgment from
 * {@link OrderNotificationSocket#onOpen()}.
 *
 * <p>Scoped to the handshake only — it does not also race a Kafka/in-memory
 * consumer to prove the push-on-consume path ({@link OrderPlacedPushConsumer}
 * broadcasting to every open connection), which would require a second,
 * concurrently open client connection and is both heavier to set up and more
 * flaky than this handshake-only check. Uses the
 * {@code BasicWebSocketConnector} CDI bean rather than a separate
 * {@code @WebSocketClient} endpoint class, per the "simpler option" guidance
 * in the WebSockets Next docs.
 */
@QuarkusTest
class OrderNotificationSocketTest {

    @TestHTTPResource
    URI baseUri;

    @Inject
    BasicWebSocketConnector connector;

    @Test
    void onOpen_sendsConnectedHandshake() throws Exception {
        CompletableFuture<String> firstMessage = new CompletableFuture<>();

        WebSocketClientConnection connection = connector
                .baseUri(baseUri)
                .path("/ws/notifications")
                .onTextMessage((conn, message) -> firstMessage.complete(message))
                .connectAndAwait();
        try {
            assertEquals("{\"type\":\"connected\"}", firstMessage.get(10, TimeUnit.SECONDS));
        } finally {
            connection.closeAndAwait();
        }
    }
}
