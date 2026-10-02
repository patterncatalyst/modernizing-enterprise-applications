package dev.patterncatalyst.review;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * LIFTED (near-unchanged) from
 * {@code dev.patterncatalyst.monolith.review.ReviewService} (ch.15 Phase A,
 * DRQ-029). {@code @Service} + constructor injection is supported as-is by
 * {@code quarkus-spring-di}.
 *
 * <p>TWO deliberate, documented adaptations (both one-line, neither touches a
 * method body):
 * <ol>
 *   <li>Spring's {@code org.springframework.transaction.annotation.Transactional}
 *   has no compat shim (the annotation-map is explicit — Quarkus always uses
 *   {@code jakarta.transaction.Transactional}, "NOT Spring's"). Swapping the
 *   import is the entire change.</li>
 *   <li>{@code @Service} alone maps to a {@code @Singleton} CDI bean under
 *   {@code quarkus-spring-di} (per the annotation-map: "Compat maps to
 *   {@code @Singleton} by default, not {@code @ApplicationScoped}").
 *   {@code @Singleton} is a CDI pseudo-scope, not a normal scope, so it
 *   cannot be client-proxied — {@code io.quarkus.test.InjectMock} in
 *   {@code ReviewControllerTest} requires a normal scope to substitute a
 *   Mockito mock. Adding the plain jakarta {@code @ApplicationScoped}
 *   annotation alongside {@code @Service} (the Spring annotation is kept for
 *   the Phase A teaching shape; the jakarta one is what actually governs the
 *   bean's scope) fixes this without touching business logic — a testability
 *   fix, not an idiomatic rewrite.</li>
 * </ol>
 *
 * <p>Review still has no synchronous dependency on order/payment/shipping/
 * notification; the only thing that changed getting here is Review's own
 * standalone security config (see {@code application.properties}) replacing
 * the monolith's one shared {@code SecurityFilterChain} — SMELL[ch.15], now
 * cured for Review specifically.
 */
@Service
@ApplicationScoped
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final CustomerRepository customerRepository;
    private final InventoryRepository inventoryRepository;

    public ReviewService(
            ReviewRepository reviewRepository,
            CustomerRepository customerRepository,
            InventoryRepository inventoryRepository) {
        this.reviewRepository = reviewRepository;
        this.customerRepository = customerRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public ReviewDto createReview(ReviewCreate command) {
        Customer customer = customerRepository.findById(command.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));
        InventoryItem item = inventoryRepository.findBySku(command.sku())
                .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + command.sku()));
        Review review = reviewRepository.save(
                new Review(customer, item, command.rating(), command.comment()));
        return toDto(review);
    }

    public ReviewDto getById(Long id) {
        return toDto(reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No review with id " + id)));
    }

    public List<ReviewDto> listBySku(String sku) {
        return reviewRepository.findAllByInventoryItemSku(sku).stream().map(ReviewService::toDto).toList();
    }

    private static ReviewDto toDto(Review r) {
        return new ReviewDto(
                r.getId(),
                r.getCustomer().getId(),
                r.getInventoryItem().getSku(),
                r.getRating(),
                r.getComment(),
                r.getCreatedAt());
    }
}
