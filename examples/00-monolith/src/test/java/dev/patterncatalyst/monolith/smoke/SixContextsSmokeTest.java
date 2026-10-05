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
 * <p>This test now covers the TWO contexts the monolith still fully owns
 * (payment/shipping) plus order (which orchestrates across the gRPC seam),
 * plus three decommission checks. Review's, Notification's, and Inventory's
 * equivalent coverage lives in the behavior-equivalence suite
 * (tooling/newman/mea.postman_collection.json, "Review Context Contract",
 * "Notification Context Contract", and "Inventory Context Contract" folders)
 * run against the extracted services through the strangler proxy.
 *
 * <p><b>Why a stub gRPC server lives here:</b> this test is deliberately
 * self-contained (Testcontainers Postgres only, no podman stack, no real
 * {@code examples/04-inventory-service} process) — but since the monolith no
 * longer has an in-JVM inventory fallback, {@code
 * checkoutFlowSpansAllFiveNonReviewContextsInOneTransaction} needs SOMETHING
 * answering {@code RemoteInventoryClient}'s gRPC calls. {@link
 * StubInventoryGrpcService} is a minimal in-process stand-in (seeded with the
 * same sku/name/price/qty values as {@code V2__seed_data.sql}) started on an
 * ephemeral port before the Spring context boots, purely so this one test
 * method can exercise {@code OrderService#placeOrder}'s orchestration shape.
 * It is NOT a substitute for the real cross-service behavior-equivalence
 * suite, which runs through the proxy against the REAL inventory service.
 *
 * <p>This is intentionally a thin smoke test, not the full suite — JUnit
 * unit/integration coverage per service and the Newman behavior-equivalence suite
 * are built in the next steps (S5, S6).
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SixContextsSmokeTest { // name kept for history; three contexts + three decommission checks, post r05/ch.19 S11

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
    }

    @Autowired
    private TestRestTemplate rest;

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
    void paymentContextResponds() {
        ResponseEntity<Object[]> response = rest.getForEntity("/api/payments?orderId=1", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1); // seed order #1's payment
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
    void checkoutFlowSpansAllFiveNonReviewContextsInOneTransaction() {
        // r05/ch.19 S11: "five contexts" now means order/payment/shipping/
        // outbox plus inventory reached ONLY over gRPC against the stub
        // server above (StubInventoryGrpcService) — never an in-JVM
        // fallback. Test name kept for history (see class javadoc).
        var command = new OrderCreate(
                2L,
                List.of(new OrderCreate.Line("SKU-GADGET-002", 1)),
                "CARD-VISA",
                "42 Analytical Avenue");
        ResponseEntity<OrderDto> response = rest.postForEntity("/api/orders", command, OrderDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.getBody().totalCents()).isEqualTo(4999L);
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
