package dev.patterncatalyst.inventory;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase A lift (r05/ch.19 S5, DRQ-044) of
 * {@code dev.patterncatalyst.monolith.inventory.InventoryRepository} --
 * {@code findBySku} is unchanged. The pessimistic-lock {@code findWithLockBySku}
 * method is NOT carried over: it exists on the monolith to back the
 * synchronous checkout reserve, a concern that lands here as the gRPC
 * {@code Reserve} RPC in S6 (idiomatic-from-start, DRQ-044), not as a lifted
 * Spring Data derived query.
 *
 * <p>The CDC backfill/sync consumer's idempotent upsert/delete ({@link
 * InventoryCdcConsumer}) does NOT live on this interface: Quarkus's Spring
 * Data JPA compat extension does not support {@code @Query(nativeQuery = true)}
 * on custom repository methods (verified empirically -- the build fails
 * {@code SpringDataJPAProcessor} with "Attribute nativeQuery of @Query is
 * currently not supported"). See {@link InventoryCdcWriter}, a plain CDI
 * bean using {@code EntityManager} directly, for that native
 * {@code INSERT ... ON CONFLICT} upsert.
 */
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findBySku(String sku);
}
