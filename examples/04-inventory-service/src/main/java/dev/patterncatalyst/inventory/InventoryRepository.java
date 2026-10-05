package dev.patterncatalyst.inventory;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * REFACTORED to idiomatic Quarkus (r05/ch.19 S6 Phase B, DRQ-029/DRQ-044)
 * from Phase A's Spring Data {@code JpaRepository<InventoryItem, Long>}
 * interface. The Panache REPOSITORY pattern (not active-record) is chosen
 * deliberately, mirroring review-service's Phase B choice: {@link
 * InventoryItem} keeps its plain-JPA shape unchanged (zero entity edits
 * needed), and this stays a thin, testable, CDI-managed data-access class.
 * {@code findBySku} becomes an explicit simplified-HQL {@code find(...)}
 * call -- Panache has no Spring-Data-style method-name-parsing convention.
 *
 * <p><b>{@link #reserve} / {@link #release} -- the gRPC mutating hot path
 * (r05/ch.19 S6, DRQ-041/DRQ-042/DRQ-044, net-new, no Spring/Phase-A
 * original -- the monolith's {@code reserve} lived in-JVM against a
 * pessimistic-lock derived query, a concern that never existed here as a
 * Spring Data method to lift).</b> Both are single conditional/unconditional
 * {@code UPDATE} statements via Panache's {@code update(query, params)},
 * which compiles to {@code UPDATE InventoryItem SET ... WHERE ...} and
 * returns the number of rows affected:
 *
 * <ul>
 *   <li>{@code reserve(sku, qty)} -- {@code quantityOnHand = quantityOnHand
 *       - :qty WHERE sku = :sku AND quantityOnHand >= :qty}. This is
 *       CONCURRENCY-SAFE without an explicit application-level lock (no
 *       {@code SELECT ... FOR UPDATE}, no optimistic-locking
 *       {@code @Version} column): Postgres (any ACID RDBMS) takes a
 *       row-level write lock for the duration of the {@code UPDATE}
 *       statement itself, so two concurrent callers reserving the last unit
 *       serialize on that row -- whichever commits first leaves
 *       {@code quantityOnHand} below the other's {@code >= :qty} predicate,
 *       so exactly one {@code UPDATE} affects a row (returns 1) and the
 *       other affects none (returns 0, re-evaluating the WHERE clause
 *       against the now-updated row). Rows-affected == 1 means
 *       {@code reservation_ok=true}; rows-affected == 0 means insufficient
 *       stock, {@code reservation_ok=false}, and NO decrement happened
 *       (the whole point of making it one conditional statement rather
 *       than a read-then-write).</li>
 *   <li>{@code release(sku, qty)} -- the compensating re-increment
 *       (DRQ-042): {@code quantityOnHand = quantityOnHand + :qty WHERE sku
 *       = :sku}, unconditional (no upper-bound check -- see
 *       {@link InventoryGrpcServiceImpl#release} javadoc for the honest
 *       at-least-once/idempotency note this leaves open, deferred to
 *       ch.23's saga).</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryRepository implements PanacheRepository<InventoryItem> {

    public Optional<InventoryItem> findBySku(String sku) {
        return find("sku", sku).firstResultOptional();
    }

    /** Atomic check-and-decrement. Returns 1 if reserved, 0 if insufficient stock. */
    public int reserve(String sku, int qty) {
        return update("quantityOnHand = quantityOnHand - ?1 where sku = ?2 and quantityOnHand >= ?1", qty, sku);
    }

    /** Compensating re-increment. Returns 1 if the sku existed and was restored, 0 otherwise. */
    public int release(String sku, int qty) {
        return update("quantityOnHand = quantityOnHand + ?1 where sku = ?2", qty, sku);
    }
}
