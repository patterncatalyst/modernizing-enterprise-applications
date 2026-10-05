package dev.patterncatalyst.inventory;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (r05/ch.19 S6 Phase B, DRQ-029/DRQ-044) --
 * renamed from Phase A's {@code InventoryController} to {@code
 * InventoryResource} to match JAX-RS naming convention, mirroring
 * review-service's {@code ReviewController} -&gt; {@code ReviewResource}
 * rename. The Spring MVC annotations ({@code @RestController}/{@code
 * @RequestMapping}/{@code @GetMapping}/{@code @PathVariable}) are gone,
 * replaced by Quarkus REST (RESTEasy Reactive) Jakarta REST annotations
 * (`@Path`/`@GET`) and a plain {@code List<StockDto>}/{@code StockDto}
 * return -- no compatibility shim underneath, running directly on
 * {@code quarkus-rest-jackson}. Same two endpoints, same {@link StockDto}
 * return shape -- the behavior-equivalence suite's "Inventory Context
 * Contract" folder must see an identical response to Phase A (and to the
 * monolith's {@code /api/inventory}).
 */
@Path("/api/inventory")
@Produces(MediaType.APPLICATION_JSON)
public class InventoryResource {

    private final InventoryService service;

    public InventoryResource(InventoryService service) {
        this.service = service;
    }

    @GET
    public List<StockDto> listAll() {
        return service.listAll();
    }

    @GET
    @Path("/{sku}")
    public StockDto getBySku(@PathParam("sku") String sku) {
        return service.getBySku(sku);
    }
}
