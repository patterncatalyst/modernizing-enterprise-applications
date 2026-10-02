package dev.patterncatalyst.monolith.review;

import dev.patterncatalyst.monolith.common.ReviewDto;
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
 * SMELL[ch.15]: writes are authenticated, reads are not, and BOTH rules live in the
 * monolith's one global {@code SecurityConfig} alongside every other context's
 * rules — Review itself has no independent say over its own security posture even
 * though it has no runtime dependency on anything else in the monolith.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
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
