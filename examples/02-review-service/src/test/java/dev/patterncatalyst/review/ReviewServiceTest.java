package dev.patterncatalyst.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * LIFTED UNCHANGED from
 * {@code dev.patterncatalyst.monolith.review.ReviewServiceTest} (ch.15 Phase
 * A). This is a plain Mockito unit test with no Spring/Quarkus context at
 * all, so it needed zero framework-level migration — only the package name
 * changed. {@link Customer}/{@link InventoryItem} are built via their
 * package-private no-arg JPA constructors + reflection-free test doubles
 * here, so a tiny test-only constructor is added to each (see their
 * {@code // test support} comment) rather than reflecting into private JPA
 * fields, which the original monolith entities didn't need because their
 * shared-kernel versions had public multi-arg constructors.
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
        Customer customer = TestFixtures.customer(2L);
        InventoryItem item = TestFixtures.inventoryItem(100L, "SKU-WIDGET-001");
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
        Customer customer = TestFixtures.customer(2L);
        var command = new ReviewCreate(2L, "NOPE", 3, "fine");
        when(customerRepository.findById(2L)).thenReturn(Optional.of(customer));
        when(inventoryRepository.findBySku("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE");
    }
}
