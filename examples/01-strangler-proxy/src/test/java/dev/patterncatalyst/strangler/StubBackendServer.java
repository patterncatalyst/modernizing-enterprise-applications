package dev.patterncatalyst.strangler;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A minimal in-JVM HTTP stub standing in for a strangler-proxy backend
 * (monolith or payment-service) in the Payment route tests (payment-plan.md
 * S7). Binds to an ephemeral loopback port and echoes back a small JSON body
 * naming which backend answered and the exact path it was asked for, so a
 * test can assert the route's content-based target selection without
 * needing the real monolith or payment service up and without colliding
 * with their fixed ports (:8080 / :8085) if those happen to be running —
 * the real end-to-end proof through the full podman stack is payment-plan
 * S8, not here.
 *
 * <p>Deliberately JDK-only ({@code com.sun.net.httpserver}) so this test
 * helper adds no new test dependency beyond RestAssured.
 */
final class StubBackendServer {

    private final HttpServer server;

    StubBackendServer(String backendName) {
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start stub backend '" + backendName + "'", e);
        }
        server.createContext("/", exchange -> respond(exchange, backendName));
        server.setExecutor(null);
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String backendName) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = "{\"backend\":\"" + backendName + "\",\"path\":\"" + path + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
