package dev.patterncatalyst.monolith.shipping;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Tier 2 (slice): {@code @WebMvcTest} for the shipping read endpoints. */
@WebMvcTest(ShippingController.class)
@AutoConfigureMockMvc(addFilters = false)
class ShippingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShippingService shippingService;

    @Test
    void getById_found_returns200() throws Exception {
        var dto = new ShipmentDto(1L, 1L, "1 Analytical Engine Way, London", ShipmentStatus.DISPATCHED,
                Instant.parse("2026-01-07T12:00:10Z"));
        when(shippingService.getById(eq(1L))).thenReturn(dto);

        mockMvc.perform(get("/api/shipments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DISPATCHED")));
    }

    @Test
    void listByOrderId_missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/api/shipments"))
                .andExpect(status().isBadRequest());
    }
}
