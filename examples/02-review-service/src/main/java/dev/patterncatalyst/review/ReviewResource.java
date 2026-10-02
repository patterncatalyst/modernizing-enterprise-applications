package dev.patterncatalyst.review;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (ch.15 Phase B, DRQ-029) — renamed from
 * Phase A's {@code ReviewController} to {@code ReviewResource} to match
 * JAX-RS naming convention. The Spring MVC annotations
 * ({@code @RestController}/{@code @RequestMapping}/{@code @GetMapping}/
 * {@code @PostMapping}/{@code @PathVariable}/{@code @RequestParam}/
 * {@code @RequestBody}) and the {@code ResponseEntity<T>} wrapper are gone,
 * replaced by Quarkus REST (RESTEasy Reactive) Jakarta REST annotations
 * (`@Path`/`@GET`/`@POST`) and plain {@code Response}/DTO return types — no
 * compatibility shim underneath, running directly on {@code quarkus-rest-jackson}.
 *
 * <p>Unannotated method parameters (like {@code ReviewCreate command}) are
 * JAX-RS's implicit entity/body parameter — no {@code @RequestBody}-equivalent
 * annotation needed. {@code @Valid} still triggers Hibernate Validator's
 * JAX-RS integration exactly as it did under the Spring-compat layer (see
 * {@link GlobalExceptionMapper}).
 *
 * <p>{@code @RolesAllowed("CUSTOMER")} is unchanged from Phase A (it was
 * already the idiomatic Jakarta security annotation, not a Spring one) and
 * still reproduces the monolith's 401/201 contract via Review's own
 * standalone security config (see {@code application.properties}).
 */
@Path("/api/reviews")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewResource {

    private final ReviewService service;

    public ReviewResource(ReviewService service) {
        this.service = service;
    }

    @POST
    @RolesAllowed("CUSTOMER")
    public Response createReview(@Valid ReviewCreate command) {
        ReviewDto dto = service.createReview(command);
        return Response.created(URI.create("/api/reviews/" + dto.id())).entity(dto).build();
    }

    @GET
    @Path("/{id}")
    public ReviewDto getById(@PathParam("id") Long id) {
        return service.getById(id);
    }

    @GET
    public List<ReviewDto> listBySku(@QueryParam("sku") String sku) {
        return service.listBySku(sku);
    }
}
