package dev.patterncatalyst.review;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * REFACTORED to idiomatic Quarkus (ch.15 Phase B, DRQ-029) from the Phase A
 * lift. The Spring {@code @Service} stereotype is dropped — plain CDI
 * {@code @ApplicationScoped} plus Quarkus's simplified constructor injection
 * (a single constructor needs no {@code @Inject}) is the whole DI story now.
 * {@code jakarta.transaction.Transactional} is unchanged from Phase A (it was
 * already the Jakarta annotation, not Spring's — see the Phase A javadoc this
 * replaces).
 *
 * <p>The repository calls change shape because {@link ReviewRepository}/
 * {@link CustomerRepository}/{@link InventoryRepository} are now Panache
 * repositories, not Spring Data: {@code findById} returns the entity directly
 * (nullable) instead of {@code Optional<T>}, and persistence is
 * {@code repository.persist(entity)} instead of
 * {@code repository.save(entity)} (Panache's {@code persist} returns
 * {@code void} — the managed entity instance itself is already enriched by
 * Hibernate inside the transaction).
 *
 * <p>Business logic — the customer/sku existence checks, the DTO mapping — is
 * byte-for-byte the same as Phase A. Review still has no synchronous
 * dependency on order/payment/shipping/notification; see
 * {@code application.properties} for Review's own standalone security
 * config (SMELL[ch.15], cured since Phase A).
 */
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
        Customer customer = Optional.ofNullable(customerRepository.findById(command.customerId()))
                .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));
        InventoryItem item = inventoryRepository.findBySku(command.sku())
                .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + command.sku()));
        Review review = new Review(customer, item, command.rating(), command.comment());
        reviewRepository.persist(review);
        return toDto(review);
    }

    public ReviewDto getById(Long id) {
        Review review = Optional.ofNullable(reviewRepository.findById(id))
                .orElseThrow(() -> new ResourceNotFoundException("No review with id " + id));
        return toDto(review);
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
