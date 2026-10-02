package dev.patterncatalyst.monolith.order;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.OrderStatus;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tier 2 (slice): {@code @WebMvcTest} for the order checkout endpoint — the
 * monolith's most consequential REST surface. Security filters are disabled
 * here ({@code addFilters = false}) because order endpoints carry no auth
 * rule of their own (see {@code ReviewControllerTest} for the one endpoint
 * that does).
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    @Test
    void placeOrder_validCommand_returns201WithLocation() throws Exception {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");
        var dto = new OrderDto(
                7L, 1L, OrderStatus.CONFIRMED, 3998L, Instant.parse("2026-01-07T12:00:00Z"),
                List.of(new OrderDto.Item("SKU-WIDGET-001", 2, 1999L)));
        when(orderService.placeOrder(any(OrderCreate.class))).thenReturn(dto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/7"))
                .andExpect(jsonPath("$.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.totalCents", is(3998)));
    }

    @Test
    void placeOrder_emptyItemsList_returns400ValidationFailed() throws Exception {
        var invalidCommand = new OrderCreate(1L, List.of(), "CARD-VISA", "1 Test Way");

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCommand)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        when(orderService.getById(eq(99L))).thenThrow(new ResourceNotFoundException("No order with id 99"));

        mockMvc.perform(get("/api/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("NOT_FOUND")));
    }
}
