package dev.patterncatalyst.review;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (ch.15 Phase B, DRQ-029) from the Phase A
 * Spring Data {@code JpaRepository<Review, Long>} interface. The Panache
 * REPOSITORY pattern (not active-record) is chosen deliberately: {@link Review}
 * keeps its plain-JPA shape (see its class javadoc — identical mapping either
 * way), and this stays a thin, testable, CDI-managed data-access class. The
 * derived-query method becomes an explicit simplified-HQL {@code find(...)}
 * call — Panache has no Spring-Data-style method-name-parsing convention.
 */
@ApplicationScoped
public class ReviewRepository implements PanacheRepository<Review> {

    public List<Review> findAllByInventoryItemSku(String sku) {
        return list("inventoryItem.sku", sku);
    }
}
