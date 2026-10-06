package dev.patterncatalyst.gateway;

import dev.patterncatalyst.inventory.v1.CheckStockRequest;
import dev.patterncatalyst.inventory.v1.GetStockRequest;
import dev.patterncatalyst.inventory.v1.InventoryGrpcService;
import dev.patterncatalyst.inventory.v1.ReleaseRequest;
import dev.patterncatalyst.inventory.v1.ReserveReply;
import dev.patterncatalyst.inventory.v1.ReserveRequest;
import dev.patterncatalyst.inventory.v1.StockReply;

import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;

/**
 * In-process gRPC stand-in for inventory-service, used by {@link
 * GatewayApiTest} -- same pattern as datamesh's {@code MockInventoryService}
 * (DRQ-032): {@link GatewayApi}'s {@code @GrpcClient("inventory")} blocking
 * stub is a {@code @Singleton} bean, which {@code @InjectMock}/{@code
 * QuarkusMock} cannot replace (only normal-scoped beans are mockable), so
 * registering this {@code @GrpcService} starts an in-process gRPC server
 * that the gateway's REAL client dials -- the gRPC stitching is exercised
 * over the wire, not mocked away at the Java-call level.
 */
@GrpcService
public class MockInventoryGrpcService implements InventoryGrpcService {

    public static final String SKU = "SKU-1";
    public static final int QUANTITY_ON_HAND = 42;

    @Override
    public Uni<StockReply> checkStock(CheckStockRequest request) {
        return Uni.createFrom().item(reply());
    }

    @Override
    public Uni<ReserveReply> reserve(ReserveRequest request) {
        return Uni.createFrom().item(ReserveReply.newBuilder()
                .setStockKeepingUnit(request.getStockKeepingUnit())
                .setReservationOk(true)
                .setOnHandQty(QUANTITY_ON_HAND)
                .build());
    }

    @Override
    public Uni<ReserveReply> release(ReleaseRequest request) {
        return Uni.createFrom().item(ReserveReply.newBuilder()
                .setStockKeepingUnit(request.getStockKeepingUnit())
                .setReservationOk(true)
                .setOnHandQty(QUANTITY_ON_HAND)
                .build());
    }

    @Override
    public Uni<StockReply> getStock(GetStockRequest request) {
        return Uni.createFrom().item(reply());
    }

    private static StockReply reply() {
        return StockReply.newBuilder()
                .setStockKeepingUnit(SKU)
                .setDisplayName("Widget")
                .setUnitPriceCents(2500)
                .setOnHandQty(QUANTITY_ON_HAND)
                .setAvailable(true)
                .build();
    }
}
