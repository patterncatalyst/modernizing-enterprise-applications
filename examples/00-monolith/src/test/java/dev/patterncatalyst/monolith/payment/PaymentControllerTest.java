package dev.patterncatalyst.monolith.payment;

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

/** Tier 2 (slice): {@code @WebMvcTest} for the payment read endpoints. */
@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void getById_found_returns200() throws Exception {
        var dto = new PaymentDto(1L, 1L, 3998L, "CARD-VISA", PaymentStatus.CAPTURED, Instant.parse(
                "2026-01-07T12:00:05Z"));
        when(paymentService.getById(eq(1L))).thenReturn(dto);

        mockMvc.perform(get("/api/payments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CAPTURED")));
    }

    @Test
    void listByOrderId_missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isBadRequest());
    }
}
