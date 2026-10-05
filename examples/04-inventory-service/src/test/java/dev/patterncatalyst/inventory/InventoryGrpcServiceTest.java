package dev.patterncatalyst.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.patterncatalyst.inventory.v1.CheckStockRequest;
import dev.patterncatalyst.inventory.v1.GetStockRequest;
import dev.patterncatalyst.inventory.v1.InventoryGrpcService;
import dev.patterncatalyst.inventory.v1.ReleaseRequest;
import dev.patterncatalyst.inventory.v1.ReserveReply;
import dev.patterncatalyst.inventory.v1.ReserveRequest;
import dev.patterncatalyst.inventory.v1.StockReply;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * r05/ch.19 S6 (DRQ-039/DRQ-041/DRQ-042/DRQ-044), net-new -- there is no
 * Spring/Phase-A original to test, since these RPCs never existed in the
 * monolith. Exercises {@link InventoryGrpcServiceImpl} end-to-end over a
 * real, in-JVM gRPC client ({@code @GrpcClient}, see "Testing your
 * services" in the Quarkus gRPC service implementation guide), against
 * this service's own Dev Services Postgres, seeded via {@link
 * InventoryCdcWriter#upsert} (the same production write path, mirroring
 * {@link InventoryResourceTest}).
 *
 * <p>The test client is wired via {@code %test.quarkus.grpc.clients.inventory.*}
 * in {@code application.properties} to {@code localhost:9005} (the
 * dedicated {@code quarkus.grpc.server.test-port}, distinct from the
 * dev/prod gRPC port 9004 -- mirrors the HTTP port/test-port split already
 * in place for :8084/:8085).
 */
@QuarkusTest
class InventoryGrpcServiceTest {

    @GrpcClient("inventory")
    InventoryGrpcService client;

    @Inject
    InventoryCdcWriter writer;

    @Inject
    InventoryRepository repository;

    @BeforeEach
    void seed() {
        writer.upsert(9101L, "SKU-GRPC-WIDGET", "gRPC Widget", 2500L, 10, Instant.now());
    }

    /**
     * This service's Dev Services Postgres container (and its {@code
     * inventory.inventory_items} table) is shared across ALL test classes
     * in one {@code mvn test} run -- {@link InventoryResourceTest}'s
     * {@code listAll} assertion counts every row, so this gRPC-only seed
     * data must not leak into (or across) other test classes/methods.
     */
    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            repository.delete("sku", "SKU-GRPC-WIDGET");
            repository.delete("sku", "SKU-GRPC-LASTUNIT");
        });
    }

    @Test
    void checkStock_sufficientStock_reportsAvailable_readOnly() {
        StockReply reply = call(client.checkStock(CheckStockRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(5)
                .build()));

        assertThat(reply.getAvailable()).isTrue();
        assertThat(reply.getOnHandQty()).isEqualTo(10);
        // read-only: stock is unaffected by CheckStock
        assertThat(repository.findBySku("SKU-GRPC-WIDGET").orElseThrow().getQuantityOnHand()).isEqualTo(10);
    }

    @Test
    void checkStock_insufficientStock_reportsUnavailable() {
        StockReply reply = call(client.checkStock(CheckStockRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(999)
                .build()));

        assertThat(reply.getAvailable()).isFalse();
        assertThat(reply.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void checkStock_unknownSku_reportsUnavailableWithZeroOnHand() {
        StockReply reply = call(client.checkStock(CheckStockRequest.newBuilder()
                .setStockKeepingUnit("SKU-DOES-NOT-EXIST")
                .setRequestedQty(1)
                .build()));

        assertThat(reply.getAvailable()).isFalse();
        assertThat(reply.getOnHandQty()).isEqualTo(0);
    }

    @Test
    void getStock_returnsFullStockRecord_mappedFromAclVocabulary() {
        StockReply reply = call(client.getStock(GetStockRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .build()));

        assertThat(reply.getStockKeepingUnit()).isEqualTo("SKU-GRPC-WIDGET");
        assertThat(reply.getDisplayName()).isEqualTo("gRPC Widget");
        assertThat(reply.getUnitPriceCents()).isEqualTo(2500L);
        assertThat(reply.getOnHandQty()).isEqualTo(10);
        assertThat(reply.getAvailable()).isTrue();
    }

    @Test
    void reserve_sufficientStock_decrementsByRequestedQtyExactlyOnceAndReportsOk() {
        ReserveReply reply = call(client.reserve(ReserveRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(4)
                .build()));

        assertThat(reply.getReservationOk()).isTrue();
        assertThat(reply.getOnHandQty()).isEqualTo(6);
        assertThat(repository.findBySku("SKU-GRPC-WIDGET").orElseThrow().getQuantityOnHand()).isEqualTo(6);
    }

    @Test
    void reserve_insufficientStock_reportsNotOkAndLeavesStockUnchanged() {
        ReserveReply reply = call(client.reserve(ReserveRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(999)
                .build()));

        assertThat(reply.getReservationOk()).isFalse();
        assertThat(reply.getOnHandQty()).isEqualTo(10);
        assertThat(repository.findBySku("SKU-GRPC-WIDGET").orElseThrow().getQuantityOnHand()).isEqualTo(10);
    }

    /** Sequential over-reserve: two successive reserves whose combined quantity exceeds on-hand stock. */
    @Test
    void reserve_sequentialOverReserve_secondCallFailsAfterFirstSucceeds() {
        ReserveReply first = call(client.reserve(ReserveRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(7)
                .build()));
        assertThat(first.getReservationOk()).isTrue();
        assertThat(first.getOnHandQty()).isEqualTo(3);

        ReserveReply second = call(client.reserve(ReserveRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(7)
                .build()));
        assertThat(second.getReservationOk()).isFalse();
        assertThat(second.getOnHandQty()).isEqualTo(3);
    }

    @Test
    void release_restoresPreviouslyReservedQuantity() {
        ReserveReply reserveReply = call(client.reserve(ReserveRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(7)
                .build()));
        assertThat(reserveReply.getReservationOk()).isTrue();
        assertThat(reserveReply.getOnHandQty()).isEqualTo(3);

        ReserveReply releaseReply = call(client.release(ReleaseRequest.newBuilder()
                .setStockKeepingUnit("SKU-GRPC-WIDGET")
                .setRequestedQty(7)
                .build()));

        assertThat(releaseReply.getReservationOk()).isTrue();
        assertThat(releaseReply.getOnHandQty()).isEqualTo(10);
        assertThat(repository.findBySku("SKU-GRPC-WIDGET").orElseThrow().getQuantityOnHand()).isEqualTo(10);
    }

    @Test
    void release_unknownSku_reportsNotOk() {
        ReserveReply releaseReply = call(client.release(ReleaseRequest.newBuilder()
                .setStockKeepingUnit("SKU-DOES-NOT-EXIST")
                .setRequestedQty(1)
                .build()));

        assertThat(releaseReply.getReservationOk()).isFalse();
        assertThat(releaseReply.getOnHandQty()).isEqualTo(0);
    }

    /**
     * CONCURRENCY check (DRQ-041): two concurrent {@code Reserve} calls race
     * the last unit of stock. Exactly one must succeed (reservation_ok=true,
     * on-hand decremented to 0); the other must report insufficient stock
     * (reservation_ok=false, on-hand unchanged by that call) -- proving the
     * server-side conditional UPDATE ({@link InventoryRepository#reserve})
     * is atomic/concurrency-safe without an explicit application-level lock.
     */
    @Test
    void reserve_concurrentRequestsForLastUnit_exactlyOneSucceeds() throws Exception {
        writer.upsert(9102L, "SKU-GRPC-LASTUNIT", "Last Unit Widget", 999L, 1, Instant.now());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<ReserveReply> first = CompletableFuture.supplyAsync(
                    () -> call(client.reserve(ReserveRequest.newBuilder()
                            .setStockKeepingUnit("SKU-GRPC-LASTUNIT")
                            .setRequestedQty(1)
                            .build())),
                    pool);
            CompletableFuture<ReserveReply> second = CompletableFuture.supplyAsync(
                    () -> call(client.reserve(ReserveRequest.newBuilder()
                            .setStockKeepingUnit("SKU-GRPC-LASTUNIT")
                            .setRequestedQty(1)
                            .build())),
                    pool);

            ReserveReply r1 = first.get(15, TimeUnit.SECONDS);
            ReserveReply r2 = second.get(15, TimeUnit.SECONDS);

            int successCount = (r1.getReservationOk() ? 1 : 0) + (r2.getReservationOk() ? 1 : 0);
            assertThat(successCount)
                    .as("exactly one of two concurrent Reserve calls for the last unit should succeed")
                    .isEqualTo(1);
            assertThat(repository.findBySku("SKU-GRPC-LASTUNIT").orElseThrow().getQuantityOnHand())
                    .isEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> T call(Uni<T> uni) {
        try {
            return uni.subscribeAsCompletionStage().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
