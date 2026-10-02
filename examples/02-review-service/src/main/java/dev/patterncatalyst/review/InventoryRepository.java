package dev.patterncatalyst.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * LIFTED (shape) from {@code dev.patterncatalyst.monolith.inventory.InventoryRepository},
 * scoped to only the derived query Review actually needs
 * ({@code findWithLockBySku}/pessimistic locking is an Order/Inventory
 * concern, not Review's). Derived-query methods (the Spring Data
 * {@code findBy*} convention) work unchanged under
 * {@code quarkus-spring-data-jpa}.
 */
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findBySku(String sku);
}
