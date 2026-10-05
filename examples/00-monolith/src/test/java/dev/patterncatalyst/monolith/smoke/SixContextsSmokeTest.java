package dev.patterncatalyst.monolith.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import dev.patterncatalyst.inventory.v1.GetStockRequest;
import dev.patterncatalyst.inventory.v1.InventoryGrpcServiceGrpc;
import dev.patterncatalyst.inventory.v1.ReleaseRequest;
import dev.patterncatalyst.inventory.v1.ReserveReply;
import dev.patterncatalyst.inventory.v1.ReserveRequest;
import dev.patterncatalyst.inventory.v1.StockReply;
import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.OrderStatus;
import dev.patterncatalyst.monolith.common.Topics;
import dev.patterncatalyst.monolith.common.outbox.OutboxEvent;
import dev.patterncatalyst.monolith.common.outbox.OutboxRepository;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Self-contained smoke test (DoD for S4): boots the full Spring context against a
 * throwaway, Testcontainers-provisioned Postgres (no external podman-stack
 * required) and hits at least one REST endpoint per bounded context.
 *
 * <p>Originally proved all six contexts responded from one running monolith.
 * As of r02/S10, Review has been decommissioned from the monolith (cutover to
 * {@code examples/02-review-service} behind the strangler proxy's
 * {@code strangler.review.enabled} flag is now permanent default-on). As of
 * r04/S8, Notification has been decommissioned the same way: its read surface
 * and synchronous send path are gone from the monolith, cutover to
 * {@code examples/03-notification-service} is permanent default-on behind
 * {@code strangler.notification.enabled}, and checkout now unconditionally
 * writes the transactional outbox instead. As of r05/ch.19 S11, Inventory is
 * decommissioned too: its read surface ({@code InventoryController}) and its
 * local in-JVM reserve path ({@code InventoryService}/{@code InventoryItem}/
 * {@code InventoryRepository}) are gone from the monolith (SMELL #5, CURED —
 * see SMELLS.md); checkout now ALWAYS reserves/releases/reads stock over
 * gRPC against the extracted inventory service via {@code
 * RemoteInventoryClient} — there is no more in-JVM fallback.
 *
 * <p>As of r06/ch.23 S9, Payment is decommissioned too: its in-process
 * {@code PaymentController}/{@code PaymentService}/{@code Payment}/{@code
 * PaymentRepository} module is gone from the monolith (SMELL[ch.22], now
 * REALIZED for payment — see SMELLS.md), and {@code OrderService#placeOrder}
 * no longer calls payment at all. Checkout ALWAYS returns {@code 202
 * Accepted} with the order {@code PENDING} now — the terminal outcome
 * ({@code CONFIRMED}/{@code PAYMENT_DECLINED}) is reached eventually via the
 * choreographed saga (payment service captures/declines -> {@code
 * order.OrderSagaListener} reacts), never synchronously on the POST.
 *
 * <p>This test now covers the ONE context the monolith still fully owns and
 * calls synchronously from checkout (shipping's REST read surface; its
 * DISPATCH is now triggered by the saga, not the request thread) plus order
 * (which orchestrates the gRPC reserve + outbox handoff), plus FOUR
 * decommission checks (Review/Notification/Inventory/Payment). Review's,
 * Notification's, Inventory's, and Payment's equivalent coverage lives in
 * the behavior-equivalence suite (tooling/newman/mea.postman_collection.json,
 * "Review Context Contract", "Notification Context Contract", "Inventory
 * Context Contract", and "Payment Context Contract" folders) run against the
 * extracted services through the strangler proxy — including the
 * bounded-wait Scenario 1 (CONFIRMED)/Scenario 3 (PAYMENT_DECLINED + stock
 * net-zero) checks this self-contained test cannot perform (see below).
 *
 * <p><b>Why a stub gRPC server lives here:</b> this test is deliberately
 * self-contained (Testcontainers Postgres only, no podman stack, no real
 * {@code examples/04-inventory-service} process) — but since the monolith no
 * longer has an in-JVM inventory fallback, {@code
 * checkoutReservesStockAndHandsOffToTheChoreographedSagaViaTheOutbox} needs
 * SOMETHING answering {@code RemoteInventoryClient}'s gRPC calls. {@link
 * StubInventoryGrpcService} is a minimal in-process stand-in (seeded with the
 * same sku/name/price/qty values as {@code V2__seed_data.sql}) started on an
 * ephemeral port before the Spring context boots, purely so this one test
 * method can exercise {@code OrderService#placeOrder}'s orchestration shape.
 * It is NOT a substitute for the real cross-service behavior-equivalence
 * suite, which runs through the proxy against the REAL inventory and payment
 * services — in particular, this test cannot drive the choreographed saga to
 * a terminal {@code CONFIRMED}/{@code PAYMENT_DECLINED} state itself, since
 * there is no real payment service and the Kafka broker is deliberately
 * unreachable here (see {@link #datasourceProperties}); that full round-trip
 * is the behavior-equivalence suite's job (Scenario 1/3, "Payment Context
 * Contract").
 *
 * <p>This is intentionally a thin smoke test, not the full suite — JUnit
 * unit/integration coverage per service and the Newman behavior-equivalence suite
 * are built in the next steps (S5, S6).
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SixContextsSmokeTest { // name kept for history; one context + four decommission checks, post r06/ch.23 S9

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("monolith")
            .withUsername("monolith")
            .withPassword("monolith");

    /**
     * Started via a static field initializer (class-load time), guaranteed
     * to be listening before JUnit/Spring ever reads its port in {@link
     * #datasourceProperties}, regardless of extension-callback ordering.
     */
    private static final Server INVENTORY_GRPC_SERVER = startStubInventoryGrpcServer();

    private static Server startStubInventoryGrpcServer() {
        try {
            return ServerBuilder.forPort(0)
                    .addService(new StubInventoryGrpcService())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to start the stub inventory gRPC server for SixContextsSmokeTest", e);
        }
    }

    @AfterAll
    static void stopStubInventoryGrpcServer() {
        INVENTORY_GRPC_SERVER.shutdownNow();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("inventory.grpc.host", () -> "localhost");
        registry.add("inventory.grpc.port", INVENTORY_GRPC_SERVER::getPort);
        // ch.23 (r06/S6): this test is deliberately self-contained — no
        // external podman-stack Kafka broker required (see class javadoc).
        // Now that order.OrderSagaListener (r06/S6) is a real @KafkaListener,
        // leaving application.yml's default localhost:9092 in place would
        // let this Spring context's consumer silently attach to a REAL
        // podman-stack broker if one happens to be running on the host and
        // react to unrelated live traffic (and let OutboxRelay's producer do
        // the same). Pointing at an address nothing listens on keeps this
        // test's Kafka-touching beans present (so the context still wires
        // and boots exactly as choreographed mode would) but inert — sends
        // fail fast and are logged (OutboxRelay already tolerates publish
        // failures), and the listener containers simply retry against a
        // closed port in the background, never touching real test data.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void orderContextResponds() {
        ResponseEntity<OrderDto[]> response = rest.getForEntity("/api/orders", OrderDto[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty(); // seed order #1
    }

    @Test
    void inventoryIsNoLongerServedByTheMonolith() {
        // r05/ch.19 S11 decommission: Inventory's controller/service/
        // repository/entity were removed from the monolith once checkout's
        // reserve/release/read path became exclusively gRPC
        // (RemoteInventoryClient) against examples/04-inventory-service.
        // The monolith itself now 404s here — proof the extraction was
        // clean and nothing else depended on this package. (The underlying
        // `inventory_items` table is still present in the shared schema,
        // write-only history now; see SMELLS.md #5.)
        ResponseEntity<Object> response = rest.getForEntity("/api/inventory", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void paymentIsNoLongerServedByTheMonolith() {
        // r06/ch.23 S9 decommission: Payment's controller/service/
        // repository/entity were removed from the monolith once checkout
        // stopped capturing payment in-process (SMELL[ch.22], realized for
        // payment — see SMELLS.md) and the choreographed saga against
        // examples/05-payment-service became the only path. The monolith
        // itself now 404s here — proof the extraction was clean and nothing
        // else depended on this package. (The underlying `payments` table is
        // still present in the shared schema, write-only history now; see
        // SMELLS.md #1/#3.)
        ResponseEntity<Object> response = rest.getForEntity("/api/payments?orderId=1", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shippingContextResponds() {
        ResponseEntity<Object[]> response = rest.getForEntity("/api/shipments?orderId=1", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1); // seed order #1's shipment
    }

    @Test
    void notificationIsNoLongerServedByTheMonolith() {
        // r04/S8 decommission: Notification's controller/service/repository
        // and its read model (common.NotificationDto) were removed from the
        // monolith once the strangler proxy's cutover to
        // examples/03-notification-service became the permanent default
        // (strangler.notification.enabled=true). The monolith itself now
        // 404s here — proof the extraction was clean and nothing else
        // depended on this package. (The underlying `notifications` table is
        // still present in the shared schema, write-only history now; see
        // SMELLS.md #4 and ch.18/19.)
        ResponseEntity<Object> response = rest.getForEntity("/api/notifications?customerId=1", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reviewIsNoLongerServedByTheMonolith() {
        // r02/S10 decommission: Review's controller/service/repository/entity were
        // removed from the monolith once the strangler proxy's cutover to
        // examples/02-review-service became the permanent default
        // (strangler.review.enabled=true). The monolith itself now 404s here —
        // proof the extraction was clean and nothing else depended on this package.
        ResponseEntity<Object> response = rest.getForEntity("/api/reviews?sku=SKU-WIDGET-001", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void checkoutReservesStockAndHandsOffToTheChoreographedSagaViaTheOutbox() {
        // r06/ch.23 S9 (DECOMMISSION): checkout no longer captures payment
        // (or dispatches shipping) in-process — OrderService#placeOrder
        // ALWAYS validates the customer, reserves every line synchronously
        // over gRPC (against the stub server above,
        // StubInventoryGrpcService — never an in-JVM fallback, r05/ch.19
        // S11), persists the order PENDING, and writes the order.placed
        // outbox row that hands the order off to the choreographed saga.
        // This self-contained test cannot drive that saga to a terminal
        // CONFIRMED/PAYMENT_DECLINED state itself (no real payment service,
        // and Kafka is deliberately unreachable — see
        // #datasourceProperties): that full round-trip is the behavior-
        // equivalence suite's job (Scenario 1/3). What THIS test proves: the
        // remaining synchronous checkout work still wires up correctly, and
        // the order.placed outbox handoff is written atomically with the
        // order. (Test name kept loosely descriptive of its r05 predecessor;
        // see class javadoc for the full history.)
        var command = new OrderCreate(
                2L,
                List.of(new OrderCreate.Line("SKU-GADGET-002", 1)),
                "CARD-VISA",
                "42 Analytical Avenue");
        ResponseEntity<OrderDto> response = rest.postForEntity("/api/orders", command, OrderDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.getBody().totalCents()).isEqualTo(4999L);

        List<OutboxEvent> events = outboxRepository.findAll().stream()
                .filter(e -> "Order".equals(e.getAggregateType())
                        && e.getAggregateId().equals(String.valueOf(response.getBody().id())))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(Topics.ORDER_PLACED);
        // not asserting exact JSON spacing (the full Spring context's
        // ObjectMapper bean may format differently than a bare
        // `new ObjectMapper()`) — just that the payment-service handoff
        // value made it into the payload (DRQ-048).
        assertThat(events.get(0).getPayload()).contains("paymentMethod").contains("CARD-VISA");
    }

    /**
     * Minimal in-process gRPC stand-in for the extracted inventory service's
     * {@code InventoryGrpcService} (see class javadoc for why this exists).
     * Seeded with the same sku/name/price/qty values as
     * {@code V2__seed_data.sql} so assertions above match the monolith's
     * original local-reserve behavior byte-for-byte.
     */
    private static final class StubInventoryGrpcService
            extends InventoryGrpcServiceGrpc.InventoryGrpcServiceImplBase {

        private final Map<String, StockRecord> stock = new ConcurrentHashMap<>(Map.of(
                "SKU-WIDGET-001", new StockRecord("Standard Widget", 1999L, 100),
                "SKU-GADGET-002", new StockRecord("Deluxe Gadget", 4999L, 50),
                "SKU-GIZMO-003", new StockRecord("Pocket Gizmo", 999L, 5)));

        @Override
        public void reserve(ReserveRequest request, StreamObserver<ReserveReply> responseObserver) {
            StockRecord record = stock.get(request.getStockKeepingUnit());
            boolean ok = record != null && record.quantityOnHand >= request.getRequestedQty();
            if (ok) {
                record.quantityOnHand -= request.getRequestedQty();
            }
            responseObserver.onNext(ReserveReply.newBuilder()
                    .setStockKeepingUnit(request.getStockKeepingUnit())
                    .setReservationOk(ok)
                    .setOnHandQty(record == null ? 0 : record.quantityOnHand)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void release(ReleaseRequest request, StreamObserver<ReserveReply> responseObserver) {
            StockRecord record = stock.get(request.getStockKeepingUnit());
            if (record != null) {
                record.quantityOnHand += request.getRequestedQty();
            }
            responseObserver.onNext(ReserveReply.newBuilder()
                    .setStockKeepingUnit(request.getStockKeepingUnit())
                    .setReservationOk(true)
                    .setOnHandQty(record == null ? 0 : record.quantityOnHand)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void getStock(GetStockRequest request, StreamObserver<StockReply> responseObserver) {
            StockRecord record = stock.get(request.getStockKeepingUnit());
            responseObserver.onNext(StockReply.newBuilder()
                    .setStockKeepingUnit(request.getStockKeepingUnit())
                    .setDisplayName(record == null ? "" : record.name)
                    .setUnitPriceCents(record == null ? 0 : record.priceCents)
                    .setOnHandQty(record == null ? 0 : record.quantityOnHand)
                    .setAvailable(record != null && record.quantityOnHand > 0)
                    .build());
            responseObserver.onCompleted();
        }

        private static final class StockRecord {
            private final String name;
            private final long priceCents;
            private int quantityOnHand;

            private StockRecord(String name, long priceCents, int quantityOnHand) {
                this.name = name;
                this.priceCents = priceCents;
                this.quantityOnHand = quantityOnHand;
            }
        }
    }
}
