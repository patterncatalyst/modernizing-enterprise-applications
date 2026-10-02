package dev.patterncatalyst.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UPDATED for ch.15 Phase B (DRQ-029): still a plain Mockito unit test with
 * no Quarkus/CDI context, so the migration cost was purely mechanical —
 * {@link ReviewRepository}/{@link CustomerRepository}/{@link InventoryRepository}
 * are now Panache repositories, so {@code findById} returns the entity
 * directly (nullable) instead of {@code Optional<T>}, and the happy-path test
 * no longer stubs a {@code save(...)} return value since Panache's
 * {@code persist(entity)} returns {@code void} (Mockito does nothing for an
 * unstubbed void method, which is exactly the desired behavior here). Same
 * three scenarios, same assertions, as Phase A.
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

        when(customerRepository.findById(2L)).thenReturn(customer);
        when(inventoryRepository.findBySku("SKU-WIDGET-001")).thenReturn(Optional.of(item));

        ReviewDto dto = reviewService.createReview(command);

        assertThat(dto.sku()).isEqualTo("SKU-WIDGET-001");
        assertThat(dto.rating()).isEqualTo(5);
        assertThat(dto.comment()).isEqualTo("Works great!");
    }

    @Test
    void createReview_customerNotFound_throwsResourceNotFoundException() {
        var command = new ReviewCreate(404L, "SKU-WIDGET-001", 3, "fine");
        when(customerRepository.findById(404L)).thenReturn(null);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    void createReview_skuNotFound_throwsResourceNotFoundException() {
        Customer customer = TestFixtures.customer(2L);
        var command = new ReviewCreate(2L, "NOPE", 3, "fine");
        when(customerRepository.findById(2L)).thenReturn(customer);
        when(inventoryRepository.findBySku("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE");
    }
}
