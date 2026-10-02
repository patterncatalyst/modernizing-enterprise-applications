package dev.patterncatalyst.monolith.inventory;

import dev.patterncatalyst.monolith.common.StockDto;
import dev.patterncatalyst.monolith.common.exception.InsufficientStockException;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

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

    /**
     * SMELL[ch.16]: returns the raw JPA entity, not a DTO/contract, to a caller
     * (order.OrderService) outside this context — there is no anti-corruption
     * layer at this seam. ch.16 introduces a Camel content enricher / message
     * translator here instead.
     */
    public InventoryItem findBySkuOrThrow(String sku) {
        return repository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + sku));
    }

    /**
     * Reserves (decrements) stock for one checkout line. Called directly from
     * {@code order.OrderService} against this context's repository/entity, with no
     * translation layer in between — see {@code SMELL[ch.16]} on
     * {@code order.OrderService#placeOrder}.
     */
    public void reserve(String sku, int quantity) {
        InventoryItem item = repository.findWithLockBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + sku));
        if (item.getQuantityOnHand() < quantity) {
            throw new InsufficientStockException(
                    "Requested %d of %s but only %d on hand".formatted(quantity, sku, item.getQuantityOnHand()));
        }
        item.decrement(quantity);
    }

    private static StockDto toDto(InventoryItem item) {
        return new StockDto(item.getSku(), item.getName(), item.getPriceCents(), item.getQuantityOnHand());
    }
}
