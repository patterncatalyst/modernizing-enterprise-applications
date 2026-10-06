package dev.patterncatalyst.order;

import dev.patterncatalyst.inventory.v1.GetStockRequest;
import dev.patterncatalyst.inventory.v1.InventoryGrpcServiceGrpc.InventoryGrpcServiceBlockingStub;
import dev.patterncatalyst.inventory.v1.ReleaseRequest;
import dev.patterncatalyst.inventory.v1.ReserveReply;
import dev.patterncatalyst.inventory.v1.ReserveRequest;
import dev.patterncatalyst.inventory.v1.StockReply;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * ch.26 S4 (DRQ-068/073, Phase A) gRPC CLIENT SCAFFOLD — the order service's
 * own copy of the monolith's {@code inventory.RemoteInventoryClient}
 * collaborator, now built on the idiomatic {@code quarkus-grpc} extension
 * (field-injected {@code @GrpcClient} blocking stub) instead of the
 * monolith's hand-wired {@code grpc-netty-shaded}/{@code ManagedChannelBuilder}
 * plumbing (Quarkus manages the channel lifecycle for us). The {@code .proto}
 * (copied verbatim from {@code examples/04-inventory-service}) is this
 * client's generated-code source — see {@code src/main/proto}.
 *
 * <p>Every method translates the gRPC wire vocabulary ({@code
 * stock_keeping_unit}/{@code on_hand_qty}/{@code reservation_ok}) into plain
 * records the rest of this service understands — the generated proto types
 * never leak past this class, same discipline as the monolith's client.
 *
 * <p><b>Wired, not merely scaffolded:</b> {@link OrderService#placeOrder}
 * calls {@link #reserve}/{@link #getStock}/{@link #release} exactly like the
 * monolith's {@code OrderService#placeOrder} does — this IS the "lifted
 * shape" the plan calls for. What's genuinely deferred to S5 is everything
 * AROUND this call: the idiomatic command-handler refactor, the atomic
 * reserve-then-outbox-write transaction, and the live saga reactions that
 * eventually move an order out of {@code PENDING}. This class has no
 * integration test exercising a real inventory-service process (see
 * {@code OrderServiceTest}, which mocks this collaborator) — that end-to-end
 * proof is left to the same step that wires the rest of the live checkout
 * path (S5), consistent with the plan's documented Phase A scope.
 */
@ApplicationScoped
public class RemoteInventoryClient {

    @GrpcClient("inventory")
    InventoryGrpcServiceBlockingStub inventoryStub;

    /**
     * Server-side atomic check-and-decrement in the inventory service's OWN
     * database. {@code ok=false} means insufficient stock and no decrement
     * happened server-side — {@link OrderService} maps that to {@link
     * InsufficientStockException} (409), the same external contract the
     * monolith's lifted path used.
     */
    public ReserveResult reserve(String sku, int quantity) {
        ReserveReply reply = inventoryStub.reserve(ReserveRequest.newBuilder()
                .setStockKeepingUnit(sku)
                .setRequestedQty(quantity)
                .build());
        return new ReserveResult(reply.getReservationOk(), reply.getOnHandQty());
    }

    /**
     * Compensating re-increment (DRQ-042 lineage) — {@link OrderService}
     * calls this for every sku it successfully reserved remotely this
     * checkout, on a PRE-HANDOFF failure (see {@link
     * OrderService#placeOrder}'s javadoc).
     */
    public void release(String sku, int quantity) {
        inventoryStub.release(ReleaseRequest.newBuilder()
                .setStockKeepingUnit(sku)
                .setRequestedQty(quantity)
                .build());
    }

    /**
     * Full read of one sku's current stock record, used by {@link
     * OrderService#placeOrder} to populate {@link OrderItem}'s denormalized
     * snapshot (sku/name/unit-price-at-order-time), mirroring the monolith's
     * lifted shape exactly (DRQ-043).
     */
    public StockSnapshot getStock(String sku) {
        StockReply reply = inventoryStub.getStock(GetStockRequest.newBuilder()
                .setStockKeepingUnit(sku)
                .build());
        return new StockSnapshot(reply.getStockKeepingUnit(), reply.getDisplayName(), reply.getUnitPriceCents());
    }

    /** Clean translation of the gRPC {@code ReserveReply} — the raw proto type never leaks past this client. */
    public record ReserveResult(boolean ok, int onHandQty) {
    }

    /** Clean translation of the gRPC {@code StockReply} — the raw proto type never leaks past this client. */
    public record StockSnapshot(String sku, String name, long priceCents) {
    }
}
