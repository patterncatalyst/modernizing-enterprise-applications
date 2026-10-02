package dev.patterncatalyst.review;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LIFTED from {@code dev.patterncatalyst.monolith.review.ReviewController}
 * (ch.15 Phase A, DRQ-029). {@code @RestController}/{@code @RequestMapping}/
 * {@code @GetMapping}/{@code @PostMapping}/{@code @PathVariable}/
 * {@code @RequestParam}/{@code @RequestBody} and the
 * {@code ResponseEntity<T>} return type are all supported unchanged by
 * {@code quarkus-spring-web} (verified against the quarkus-agent
 * {@code migrate-spring-to-quarkus} annotation map).
 *
 * <p>ONE deliberate, documented adaptation (not a Phase-B idiomatic rewrite):
 * the write endpoint's auth rule is now expressed with the Jakarta
 * {@code @RolesAllowed} annotation instead of being governed externally by a
 * Spring {@code SecurityFilterChain} bean (that DSL — {@code HttpSecurity},
 * {@code SecurityFilterChain} — has no {@code quarkus-spring-security}
 * compatibility equivalent; the compat extension only covers method-level
 * {@code @Secured}/{@code @PreAuthorize}). {@code @RolesAllowed} is itself a
 * plain {@code jakarta.annotation.security} annotation, needs no Spring
 * import, and — combined with the HTTP Basic + embedded-realm config in
 * {@code application.properties} — reproduces the exact 401/201 contract the
 * monolith's {@code SecurityConfig} enforced. This is also the smell's cure
 * (SMELL[ch.15]): Review now owns its OWN security posture instead of sharing
 * the monolith's one global filter chain.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
    @RolesAllowed("CUSTOMER")
    public ResponseEntity<ReviewDto> createReview(@Valid @RequestBody ReviewCreate command) {
        ReviewDto dto = service.createReview(command);
        return ResponseEntity.created(URI.create("/api/reviews/" + dto.id())).body(dto);
    }

    @GetMapping("/{id}")
    public ReviewDto getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping
    public List<ReviewDto> listBySku(@RequestParam String sku) {
        return service.listBySku(sku);
    }
}
