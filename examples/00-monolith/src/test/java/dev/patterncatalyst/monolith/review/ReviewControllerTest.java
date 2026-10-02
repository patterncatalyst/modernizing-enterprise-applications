package dev.patterncatalyst.monolith.review;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.patterncatalyst.monolith.common.ReviewDto;
import dev.patterncatalyst.monolith.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tier 2 (slice): {@code @WebMvcTest} for the review endpoints — the ONE
 * endpoint in the monolith that is authenticated (SMELL[ch.15]: this auth
 * rule lives in the monolith's single shared {@code SecurityConfig} even
 * though Review has no other dependency on anything else). Unlike the other
 * five controller slices, security filters are left ENABLED and the real
 * {@code SecurityConfig} is imported, so this test can prove 401 (no auth)
 * vs. 201 (authenticated) on the same endpoint.
 */
@WebMvcTest(ReviewController.class)
@Import(SecurityConfig.class)
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReviewService reviewService;

    @Test
    void listBySku_noAuthRequired_returns200() throws Exception {
        when(reviewService.listBySku("SKU-WIDGET-001")).thenReturn(List.of(
                new ReviewDto(1L, 2L, "SKU-WIDGET-001", 5, "Works great!",
                        Instant.parse("2026-01-08T15:00:00Z"))));

        mockMvc.perform(get("/api/reviews").param("sku", "SKU-WIDGET-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void createReview_withoutAuthentication_returns401() throws Exception {
        var command = new ReviewCreate(1L, "SKU-WIDGET-001", 3, "fine");

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReview_withValidCredentials_returns201() throws Exception {
        var command = new ReviewCreate(1L, "SKU-WIDGET-001", 3, "fine");
        var dto = new ReviewDto(9L, 1L, "SKU-WIDGET-001", 3, "fine", Instant.now());
        when(reviewService.createReview(any(ReviewCreate.class))).thenReturn(dto);

        mockMvc.perform(post("/api/reviews")
                        .with(httpBasic("demo-customer", "demo-pass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/reviews/9"))
                .andExpect(jsonPath("$.sku", is("SKU-WIDGET-001")));
    }

    @Test
    void createReview_withValidCredentialsButInvalidRating_returns400() throws Exception {
        var invalidCommand = new ReviewCreate(1L, "SKU-WIDGET-001", 0, "fine"); // @Min(1) violated

        mockMvc.perform(post("/api/reviews")
                        .with(httpBasic("demo-customer", "demo-pass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCommand)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
    }
}
