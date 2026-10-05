package dev.patterncatalyst.inventory;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * LIFTED from {@code dev.patterncatalyst.monolith.inventory.InventoryService}
 * (r05/ch.19 S5, Phase A, DRQ-044). {@code listAll}/{@code getBySku}/
 * {@code findBySkuOrThrow}/{@code toDto} are unchanged. The monolith's
 * {@code reserve(sku, quantity)} method is NOT lifted here -- it becomes the
 * gRPC {@code Reserve} RPC's server-side implementation in S6
 * (idiomatic-from-start against this service's own locking, DRQ-041/DRQ-044),
 * not a Phase A carry-over of the in-JVM mutation.
 */
@Service
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    public List<StockDto> listAll() {
        return repository.findAll().stream().map(InventoryService::toDto).toList();
    }

    public StockDto getBySku(String sku) {
        return toDto(findBySkuOrThrow(sku));
    }

    public InventoryItem findBySkuOrThrow(String sku) {
        return repository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + sku));
    }

    private static StockDto toDto(InventoryItem item) {
        return new StockDto(item.getSku(), item.getName(), item.getPriceCents(), item.getQuantityOnHand());
    }
}
