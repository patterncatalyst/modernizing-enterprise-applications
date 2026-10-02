package dev.patterncatalyst.monolith.review;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.CustomerRepository;
import dev.patterncatalyst.monolith.common.ReviewDto;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.inventory.InventoryRepository;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review has no synchronous dependency on order, payment, shipping, or
 * notification — it reads and writes its own rows only (plus the two shared-schema
 * joins to customer/inventory noted on {@link Review}, SMELL[ch.18]). That
 * independence is what makes it the walking-skeleton extraction (ch.15); the only
 * entanglement is the shared security filter chain (SMELL[ch.15], see
 * {@code security.SecurityConfig}).
 */
@Service
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
