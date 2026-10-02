package dev.patterncatalyst.review;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * LIFTED UNCHANGED from {@code dev.patterncatalyst.monolith.review.ReviewRepository}
 * (ch.15 Phase A) — the Spring Data derived query
 * ({@code findAllByInventoryItemSku}) works as-is under
 * {@code quarkus-spring-data-jpa}.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findAllByInventoryItemSku(String sku);
}
