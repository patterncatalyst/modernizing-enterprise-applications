package dev.patterncatalyst.inventory;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LIFTED UNCHANGED from
 * {@code dev.patterncatalyst.monolith.inventory.InventoryController}
 * (r05/ch.19 S5, Phase A, DRQ-044). Same {@code @RequestMapping}, same two
 * endpoints, same {@link StockDto} return shape -- the behavior-equivalence
 * suite's "Inventory Context Contract" folder must see an identical response
 * whether it hits the monolith's {@code /api/inventory} or this service's.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<StockDto> listAll() {
        return service.listAll();
    }

    @GetMapping("/{sku}")
    public StockDto getBySku(@PathVariable String sku) {
        return service.getBySku(sku);
    }
}
