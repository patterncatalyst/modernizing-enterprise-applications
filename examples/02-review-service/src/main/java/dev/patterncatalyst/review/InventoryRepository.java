package dev.patterncatalyst.review;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * REFACTORED to idiomatic Quarkus (ch.15 Phase B, DRQ-029) from the Phase A
 * Spring Data {@code JpaRepository<InventoryItem, Long>} interface's derived
 * {@code findBySku} query. Panache has no method-name-derivation convention,
 * so the query is spelled out explicitly with simplified HQL.
 */
@ApplicationScoped
public class InventoryRepository implements PanacheRepository<InventoryItem> {

    public Optional<InventoryItem> findBySku(String sku) {
        return find("sku", sku).firstResultOptional();
    }
}
