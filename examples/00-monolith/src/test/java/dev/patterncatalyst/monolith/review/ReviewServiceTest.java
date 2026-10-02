package dev.patterncatalyst.monolith.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.CustomerRepository;
import dev.patterncatalyst.monolith.common.ReviewDto;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import dev.patterncatalyst.monolith.inventory.InventoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit): {@code ReviewService} is Review's only real logic (two
 * existence checks before a save) — Review is otherwise the
 * least-logic-bearing, most independent context in the monolith, which is
 * exactly why it is the r02 walking-skeleton extraction (ch.15).
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewRepository, customerRepository, inventoryRepository);
    }

    @Test
    void createReview_happyPath_savesAndReturnsDto() {
        Customer customer = new Customer("Grace Hopper", "grace@example.com");
        InventoryItem item = new InventoryItem("SKU-WIDGET-001", "Standard Widget", 1999, 100);
        var command = new ReviewCreate(2L, "SKU-WIDGET-001", 5, "Works great!");

        when(customerRepository.findById(2L)).thenReturn(Optional.of(customer));
        when(inventoryRepository.findBySku("SKU-WIDGET-001")).thenReturn(Optional.of(item));
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewDto dto = reviewService.createReview(command);

        assertThat(dto.sku()).isEqualTo("SKU-WIDGET-001");
        assertThat(dto.rating()).isEqualTo(5);
        assertThat(dto.comment()).isEqualTo("Works great!");
    }

    @Test
    void createReview_customerNotFound_throwsResourceNotFoundException() {
        var command = new ReviewCreate(404L, "SKU-WIDGET-001", 3, "fine");
        when(customerRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    void createReview_skuNotFound_throwsResourceNotFoundException() {
        Customer customer = new Customer("Grace Hopper", "grace@example.com");
        var command = new ReviewCreate(2L, "NOPE", 3, "fine");
        when(customerRepository.findById(2L)).thenReturn(Optional.of(customer));
        when(inventoryRepository.findBySku("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE");
    }
}
