package dev.patterncatalyst.monolith.inventory;

import dev.patterncatalyst.monolith.common.StockDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
