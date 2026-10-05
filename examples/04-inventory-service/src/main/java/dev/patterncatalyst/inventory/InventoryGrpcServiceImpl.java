package dev.patterncatalyst.inventory;

import dev.patterncatalyst.inventory.v1.CheckStockRequest;
import dev.patterncatalyst.inventory.v1.GetStockRequest;
import dev.patterncatalyst.inventory.v1.InventoryGrpcService;
import dev.patterncatalyst.inventory.v1.ReleaseRequest;
import dev.patterncatalyst.inventory.v1.ReserveReply;
import dev.patterncatalyst.inventory.v1.ReserveRequest;
import dev.patterncatalyst.inventory.v1.StockReply;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * gRPC SERVER (r05/ch.19 S6, DRQ-039/DRQ-041/DRQ-042/DRQ-044) implementing
 * the generated Mutiny {@code InventoryGrpcService} interface (from {@code
 * src/main/proto/.../inventory.proto}, S4). Idiomatic-from-day-one -- there
 * is no Spring original to lift for RPCs that never existed in the
 * monolith (DRQ-044: "you cannot lift code that never existed").
 *
 * <p>This is the ACL contract's server side (curing SMELL #5): the wire
 * vocabulary here ({@code stock_keeping_unit}/{@code on_hand_qty}/{@code
 * unit_price_cents}) is deliberately distinct from the internal {@link
 * StockDto}/{@link InventoryItem} shape ({@code sku}/{@code quantityOnHand}/
 * {@code priceCents}) so ch.16's {@code InventoryAclRoute} message
 * translator (S9) earns its keep converting between the two at the
 * order&lt;-&gt;inventory seam, instead of the two shapes happening to
 * already agree.
 *
 * <p>Every RPC is {@code @Blocking}: this service uses CLASSIC (non-reactive)
 * {@code quarkus-hibernate-orm-panache}, whose JDBC calls are blocking and
 * must not run on the Vert.x event-loop thread the default Mutiny gRPC
 * dispatch uses -- {@code @Blocking} tells quarkus-grpc to invoke the whole
 * method body on a worker thread instead (see the gRPC service
 * implementation guide's "Blocking gRPC methods" section).
 */
@GrpcService
public class InventoryGrpcServiceImpl implements InventoryGrpcService {

    private final InventoryRepository repository;

    public InventoryGrpcServiceImpl(InventoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Read-only availability probe (DRQ-041 contrast with Reserve): does
     * NOT mutate state. {@code available} answers "can {@code requested_qty}
     * be satisfied right now" -- distinct from {@link StockReply#getAvailable()}
     * on {@link #getStock}, which reports "is there any stock at all."
     */
    @Override
    @Blocking
    public Uni<StockReply> checkStock(CheckStockRequest request) {
        String sku = request.getStockKeepingUnit();
        Optional<InventoryItem> item = repository.findBySku(sku);
        boolean canSatisfy = item.isPresent() && item.get().getQuantityOnHand() >= request.getRequestedQty();
        return Uni.createFrom().item(toStockReply(sku, item, canSatisfy));
    }

    /**
     * Server-side ATOMIC check-and-decrement against this service's OWN
     * database (DRQ-041: correctness requires the caller to know
     * synchronously, before confirming the order, whether stock was
     * secured). {@link InventoryRepository#reserve} issues a single
     * conditional {@code UPDATE ... SET quantity_on_hand = quantity_on_hand
     * - :n WHERE sku = :sku AND quantity_on_hand >= :n} and reports the
     * rows-affected count: exactly 1 means the reservation succeeded
     * (checked and decremented atomically); 0 means insufficient stock
     * (out-of-stock semantics) and NO decrement happened. This is
     * CONCURRENCY-SAFE without an explicit application-level lock (no
     * {@code SELECT ... FOR UPDATE}, no {@code @Version} column) -- the
     * database's own row-level write lock on the UPDATE statement
     * serializes two concurrent reservations racing the same row, so
     * exactly one of two concurrent callers reserving the last unit
     * succeeds (see {@code InventoryGrpcServiceTest#reserve_concurrent...}
     * for the proof).
     */
    @Override
    @Blocking
    @Transactional
    public Uni<ReserveReply> reserve(ReserveRequest request) {
        String sku = request.getStockKeepingUnit();
        int qty = request.getRequestedQty();
        boolean reservationOk = repository.reserve(sku, qty) == 1;
        int onHandQty = repository.findBySku(sku).map(InventoryItem::getQuantityOnHand).orElse(0);
        return Uni.createFrom().item(ReserveReply.newBuilder()
                .setStockKeepingUnit(sku)
                .setReservationOk(reservationOk)
                .setOnHandQty(onHandQty)
                .build());
    }

    /**
     * Compensating re-increment (DRQ-042 -- a deliberate first taste of
     * saga, forward-ref ch.23). The monolith calls this when checkout fails
     * AFTER a successful {@link #reserve}, notably a payment decline: once
     * the decrement lives in this service's own DB, the monolith's local
     * {@code @Transactional} can no longer roll it back, so the monolith
     * must explicitly restore the observable baseline via {@code Release}.
     * {@link InventoryRepository#release} issues {@code UPDATE ... SET
     * quantity_on_hand = quantity_on_hand + :n WHERE sku = :sku}.
     *
     * <p><b>Idempotency / at-least-once consideration (an honest limitation,
     * documented rather than silently built around -- NOT speculative saga
     * infrastructure, which is explicitly out of scope here per the plan's
     * scope discipline):</b> unlike {@code Reserve}'s conditional UPDATE,
     * this increment is UNCONDITIONAL and carries no dedupe/idempotency key.
     * If the monolith's gRPC call to {@code Release} is retried after a
     * transient failure whose effect actually DID land server-side (the
     * same at-least-once redelivery hazard {@link InventoryCdcConsumer}'s
     * upsert guards against for CDC), stock would be over-restored by the
     * redelivered amount. A full fix -- an idempotency key per
     * reservation/compensation, or a saga ledger recording
     * reserve/release pairs -- is deliberately deferred to ch.23's
     * choreographed saga (DRQ-042); this javadoc is that deferral's record.
     */
    @Override
    @Blocking
    @Transactional
    public Uni<ReserveReply> release(ReleaseRequest request) {
        String sku = request.getStockKeepingUnit();
        int qty = request.getRequestedQty();
        boolean releaseOk = repository.release(sku, qty) == 1;
        int onHandQty = repository.findBySku(sku).map(InventoryItem::getQuantityOnHand).orElse(0);
        return Uni.createFrom().item(ReserveReply.newBuilder()
                .setStockKeepingUnit(sku)
                .setReservationOk(releaseOk)
                .setOnHandQty(onHandQty)
                .build());
    }

    /**
     * Full read of one sku's current stock record -- the content-enricher
     * fetch target for ch.16's {@code InventoryAclRoute} (S9: content-based
     * router -&gt; content enricher -&gt; message translator into {@link
     * StockDto}).
     */
    @Override
    @Blocking
    public Uni<StockReply> getStock(GetStockRequest request) {
        String sku = request.getStockKeepingUnit();
        Optional<InventoryItem> item = repository.findBySku(sku);
        return Uni.createFrom().item(toStockReply(sku, item, item.isPresent()));
    }

    private static StockReply toStockReply(String requestedSku, Optional<InventoryItem> item, boolean available) {
        return item.map(i -> StockReply.newBuilder()
                        .setStockKeepingUnit(i.getSku())
                        .setDisplayName(i.getName())
                        .setUnitPriceCents(i.getPriceCents())
                        .setOnHandQty(i.getQuantityOnHand())
                        .setAvailable(available)
                        .build())
                .orElseGet(() -> StockReply.newBuilder()
                        .setStockKeepingUnit(requestedSku)
                        .setDisplayName("")
                        .setUnitPriceCents(0)
                        .setOnHandQty(0)
                        .setAvailable(false)
                        .build());
    }
}
