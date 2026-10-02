package dev.patterncatalyst.monolith.inventory;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.patterncatalyst.monolith.common.StockDto;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Tier 2 (slice): {@code @WebMvcTest} for the inventory read endpoints. */
@WebMvcTest(InventoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryService inventoryService;

    @Test
    void listAll_returns200WithSeededItems() throws Exception {
        when(inventoryService.listAll()).thenReturn(List.of(
                new StockDto("SKU-WIDGET-001", "Standard Widget", 1999, 100),
                new StockDto("SKU-GADGET-002", "Deluxe Gadget", 4999, 50)));

        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sku", is("SKU-WIDGET-001")));
    }

    @Test
    void getBySku_unknownSku_returns404() throws Exception {
        when(inventoryService.getBySku(eq("NOPE")))
                .thenThrow(new ResourceNotFoundException("No inventory item with sku NOPE"));

        mockMvc.perform(get("/api/inventory/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("NOT_FOUND")));
    }
}
