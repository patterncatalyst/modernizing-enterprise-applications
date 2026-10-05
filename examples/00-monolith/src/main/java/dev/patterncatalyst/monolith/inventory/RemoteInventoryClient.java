package dev.patterncatalyst.monolith.inventory;

import dev.patterncatalyst.inventory.v1.GetStockRequest;
import dev.patterncatalyst.inventory.v1.InventoryGrpcServiceGrpc;
import dev.patterncatalyst.inventory.v1.InventoryGrpcServiceGrpc.InventoryGrpcServiceBlockingStub;
import dev.patterncatalyst.inventory.v1.ReleaseRequest;
import dev.patterncatalyst.inventory.v1.ReserveReply;
import dev.patterncatalyst.inventory.v1.ReserveRequest;
import dev.patterncatalyst.inventory.v1.StockReply;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * r05/ch.19 S7 (DRQ-039/DRQ-041/DRQ-042) -- the monolith's gRPC CLIENT
 * decorating collaborator for the order-&gt;inventory checkout seam. As of
 * r05/ch.19 S11 (DECOMMISSION), this is the ONLY path {@link
 * dev.patterncatalyst.monolith.order.OrderService#placeOrder} uses to
 * reserve/release/read stock -- the {@code inventory.mode=local|remote}
 * reversibility flag introduced for the cutover has been removed along with
 * the monolith's local in-JVM inventory module (SMELL #5, now CURED).
 *
 * <p>This is the client side of the ACL (curing SMELL #5 at the source):
 * every method here translates the gRPC wire vocabulary
 * ({@code stock_keeping_unit}/{@code on_hand_qty}/{@code reservation_ok})
 * into plain values/records the rest of the monolith understands -- the
 * generated proto types ({@code ReserveRequest}/{@code ReserveReply}/...)
 * never leak past this class.
 *
 * <p><b>Failure modes (timeout/unavailable).</b> Both RPCs carry an explicit
 * per-call deadline ({@code inventory.grpc.timeout-ms}, default 5000ms). If
 * the inventory service is slow, hung, or unreachable, the blocking stub
 * throws an unchecked {@link StatusRuntimeException} (e.g. {@code
 * DEADLINE_EXCEEDED}/{@code UNAVAILABLE}). {@link #reserve} lets this
 * propagate out of {@code OrderService#placeOrder} uncaught -- it is not a
 * logical "insufficient stock" result, so it must NOT be mapped to {@link
 * dev.patterncatalyst.monolith.common.exception.InsufficientStockException}.
 * Spring's default handling turns an unmapped {@code RuntimeException} into
 * a 500, failing the checkout cleanly rather than silently confirming an
 * order whose reservation status is unknown.
 */
@Component
public class RemoteInventoryClient {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteInventoryClient.class);

    private final String host;
    private final int port;
    private final long timeoutMs;

    private ManagedChannel channel;
    private InventoryGrpcServiceBlockingStub stub;

    public RemoteInventoryClient(
            @Value("${inventory.grpc.host:localhost}") String host,
            @Value("${inventory.grpc.port:9004}") int port,
            @Value("${inventory.grpc.timeout-ms:5000}") long timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct
    void start() {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = InventoryGrpcServiceGrpc.newBlockingStub(channel);
        LOG.info("RemoteInventoryClient targeting inventory service gRPC at {}:{} (timeout {}ms)",
                host, port, timeoutMs);
    }

    @PreDestroy
    void stop() {
        if (channel == null) {
            return;
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Server-side atomic check-and-decrement in the inventory service's OWN
     * database (DRQ-041: Reserve stays synchronous so the caller knows
     * before confirming the order whether stock was secured). {@code
     * ok=false} means insufficient stock and NO decrement happened on the
     * server side -- {@code OrderService} maps that to {@link
     * dev.patterncatalyst.monolith.common.exception.InsufficientStockException}
     * (409), the identical external contract to the local path.
     */
    public ReserveResult reserve(String sku, int quantity) {
        ReserveReply reply = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .reserve(ReserveRequest.newBuilder()
                        .setStockKeepingUnit(sku)
                        .setRequestedQty(quantity)
                        .build());
        return new ReserveResult(reply.getReservationOk(), reply.getOnHandQty());
    }

    /**
     * Compensating re-increment (DRQ-042 -- a deliberate first taste of
     * saga, forward-ref ch.23). {@code OrderService} calls this for every
     * sku it successfully reserved remotely this checkout, on ANY failure
     * after that reserve (insufficient stock on a later line, a payment
     * decline, a shipping failure, or any other exception before the order
     * confirms) -- because once the decrement committed in the inventory
     * service's own database, the monolith's local {@code @Transactional}
     * can no longer roll it back across the service boundary.
     */
    public void release(String sku, int quantity) {
        stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .release(ReleaseRequest.newBuilder()
                        .setStockKeepingUnit(sku)
                        .setRequestedQty(quantity)
                        .build());
    }

    /**
     * r05/ch.19 S8 (DRQ-043): full read of one sku's current stock record,
     * used by {@code OrderService#placeOrder} to populate {@code OrderItem}'s
     * denormalized snapshot (sku/name/unit-price-at-order-time) from the
     * extracted inventory service's OWN data -- this monolith no longer has
     * a local {@code inventory_items} table reader at all (SMELL #5, CURED,
     * S11 decommission) -- the same {@code
     * GetStock} RPC the ch.16 {@code InventoryAclRoute} content enricher
     * uses on the read side (S9).
     */
    public StockSnapshot getStock(String sku) {
        StockReply reply = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .getStock(GetStockRequest.newBuilder()
                        .setStockKeepingUnit(sku)
                        .build());
        return new StockSnapshot(reply.getStockKeepingUnit(), reply.getDisplayName(), reply.getUnitPriceCents());
    }

    /** Clean translation of the gRPC {@code ReserveReply} -- the raw proto type never leaks past this client. */
    public record ReserveResult(boolean ok, int onHandQty) {
    }

    /** Clean translation of the gRPC {@code StockReply} -- the raw proto type never leaks past this client. */
    public record StockSnapshot(String sku, String name, long priceCents) {
    }
}
