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
 * rule of their own. (Review, the one context whose write endpoint WAS
 * authenticated, was decommissioned from the monolith in r02/S10 — it is now
 * served exclusively by {@code examples/02-review-service} behind the
 * strangler proxy's {@code strangler.review.enabled} flag, with its own
 * standalone security config; see SMELLS.md smell #6, now cured.)
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
    void placeOrder_validCommand_returns202PendingWithLocation() throws Exception {
        // ch.23 (r06/S9, DRQ-047, DECOMMISSION): checkout always returns 202
        // Accepted with a Location header now — OrderService#placeOrder
        // always hands the order off PENDING via the choreographed saga; the
        // synchronous 201 Created/CONFIRMED contract this endpoint used to
        // also support (payment.mode=synchronous) was removed along with
        // that flag. The resource is still created synchronously and is
        // immediately pollable; only the terminal outcome arrives later.
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");
        var dto = new OrderDto(
                8L, 1L, OrderStatus.PENDING, 3998L, Instant.parse("2026-01-07T12:00:00Z"), "1 Test Way",
                List.of(new OrderDto.Item("SKU-WIDGET-001", 2, 1999L)));
        when(orderService.placeOrder(any(OrderCreate.class))).thenReturn(dto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/orders/8"))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.totalCents", is(3998)))
                .andExpect(jsonPath("$.shippingAddress", is("1 Test Way")));
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
