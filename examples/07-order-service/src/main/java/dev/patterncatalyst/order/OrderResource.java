package dev.patterncatalyst.order;

import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (ch.26 S5, Phase B, DRQ-073) -- renamed
 * from Phase A's {@code OrderController} to {@code OrderResource} to match
 * JAX-RS naming convention, exactly mirroring payment-service's/
 * shipping-service's/review-service's {@code *Controller} -&gt; {@code
 * *Resource} rename. The Spring MVC annotations ({@code @RestController}/
 * {@code @RequestMapping}/{@code @GetMapping}/{@code @PostMapping}/{@code
 * @PathVariable}/{@code @RequestBody}) and the {@code ResponseEntity<T>}
 * wrapper are gone, replaced by Quarkus REST (RESTEasy Reactive) Jakarta REST
 * annotations ({@code @Path}/{@code @GET}/{@code @POST}) and a plain {@code
 * Response}/DTO return type -- no compatibility shim underneath, running
 * directly on {@code quarkus-rest-jackson}.
 *
 * <p>The {@code /api/orders} read+command contract is BYTE-FOR-BYTE
 * UNCHANGED (the Order Context Contract folder depends on this exact shape):
 * same paths, same {@link OrderDto} JSON shape, same {@code 202 Accepted} +
 * {@code Location} header on {@link #placeOrder}, same 404/400/409
 * error-mapping surface (see {@link GlobalExceptionMapper}).
 *
 * <p>Unannotated {@code @Valid OrderCreate command} is JAX-RS's implicit
 * entity/body parameter -- no {@code @RequestBody}-equivalent annotation
 * needed. {@code @Valid} still triggers Hibernate Validator's JAX-RS
 * integration exactly as it did under the Spring-compat layer.
 */
@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    private final OrderService service;

    public OrderResource(OrderService service) {
        this.service = service;
    }

    @POST
    public Response placeOrder(@Valid OrderCreate command) {
        OrderDto dto = service.placeOrder(command);
        URI location = URI.create("/api/orders/" + dto.id());
        return Response.accepted(dto).location(location).build();
    }

    @GET
    @Path("/{id}")
    public OrderDto getById(@PathParam("id") Long id) {
        return service.getById(id);
    }

    @GET
    public List<OrderDto> listAll() {
        return service.listAll();
    }
}
