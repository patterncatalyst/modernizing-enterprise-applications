package dev.patterncatalyst.monolith.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.patterncatalyst.monolith.common.StockDto;
import dev.patterncatalyst.monolith.common.exception.InsufficientStockException;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tier 1 (unit): {@code InventoryService#reserve} is the one place in the
 * monolith with real branching logic (sufficient vs. insufficient stock) —
 * this is the collaborator {@code OrderService} relies on for the
 * out-of-stock branch (SMELL[ch.16], no ACL at this seam).
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository repository;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(repository);
    }

    @Test
    void reserve_sufficientStock_decrementsQuantityOnHand() {
        InventoryItem item = new InventoryItem("SKU-WIDGET-001", "Standard Widget", 1999, 10);
        when(repository.findWithLockBySku("SKU-WIDGET-001")).thenReturn(Optional.of(item));

        inventoryService.reserve("SKU-WIDGET-001", 3);

        assertThat(item.getQuantityOnHand()).isEqualTo(7);
    }

    @Test
    void reserve_insufficientStock_throwsAndLeavesQuantityUnchanged() {
        InventoryItem item = new InventoryItem("SKU-GIZMO-003", "Pocket Gizmo", 999, 2);
        when(repository.findWithLockBySku("SKU-GIZMO-003")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> inventoryService.reserve("SKU-GIZMO-003", 5))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-GIZMO-003");

        assertThat(item.getQuantityOnHand()).isEqualTo(2);
    }

    @Test
    void reserve_skuNotFound_throwsResourceNotFoundException() {
        when(repository.findWithLockBySku("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.reserve("NOPE", 1))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void findBySkuOrThrow_skuNotFound_throwsResourceNotFoundException() {
        when(repository.findBySku("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.findBySkuOrThrow("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getBySku_found_mapsToStockDto() {
        InventoryItem item = new InventoryItem("SKU-WIDGET-001", "Standard Widget", 1999, 100);
        when(repository.findBySku("SKU-WIDGET-001")).thenReturn(Optional.of(item));

        StockDto dto = inventoryService.getBySku("SKU-WIDGET-001");

        assertThat(dto).isEqualTo(new StockDto("SKU-WIDGET-001", "Standard Widget", 1999, 100));
    }
}
