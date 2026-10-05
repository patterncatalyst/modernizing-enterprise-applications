package dev.patterncatalyst.order;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REFACTORED to idiomatic Quarkus (ch.26 S5, Phase B, DRQ-073) — renamed from
 * Phase A's {@code OrderControllerTest} to match {@link OrderResource}'s
 * JAX-RS rename, same role (a resource-layer slice test that mocks {@link
 * OrderService} entirely). {@link OrderService} is a plain CDI {@code
 * @ApplicationScoped} bean now (no more {@code @Scope("application")}
 * Spring-compat workaround) — Quarkus always gives such a bean a client
 * proxy, so {@code @InjectMock} works unchanged.
 *
 * <p>Proves the {@code /api/orders} read+command contract — including the
 * {@link OrderDto} shape (byte-for-byte match with the monolith's/Phase A's)
 * and the 404/400/409 error-mapping surface (see {@link
 * GlobalExceptionMapper}) — without touching a database or a real gRPC
 * channel (this is a resource-layer slice test; {@link OrderServiceTest}
 * covers the service-layer reserve/compensate/outbox logic against mocked
 * collaborators).
 *
 * <p>The {@code Location} header assertion is relaxed from an exact-match to
 * {@code endsWith(...)}, same divergence review-service's Phase B {@code
 * ReviewResourceTest} documents: Jakarta REST's {@code Response.accepted(...)
 * .location(URI)} resolves a relative location URI against the request's
 * base URI (unlike Spring's {@code ResponseEntity.location(URI)}, which
 * echoed the literal string), so the header value is now an absolute URL
 * ending in the same path — a test-assertion-strictness adjustment, not a
 * contract change (the Order Context Contract only asserts the header is
 * present).
 */
@QuarkusTest
class OrderResourceTest {

    @InjectMock
    OrderService orderService;

    @Test
    void placeOrder_validCommand_returns202AcceptedWithLocationAndOrderDtoShape() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");
        var dto = new OrderDto(
                8L, 1L, OrderStatus.PENDING, 3998L, Instant.parse("2026-01-07T12:00:00Z"), "1 Test Way",
                List.of(new OrderDto.Item("SKU-WIDGET-001", 2, 1999L)));
        when(orderService.placeOrder(any(OrderCreate.class))).thenReturn(dto);

        given()
                .contentType(ContentType.JSON)
                .body(command)
                .when().post("/api/orders")
                .then()
                .statusCode(202)
                .header("Location", endsWith("/api/orders/8"))
                .body("status", is("PENDING"))
                .body("totalCents", is(3998))
                .body("shippingAddress", is("1 Test Way"))
                .body("items[0].sku", is("SKU-WIDGET-001"));
    }

    @Test
    void placeOrder_emptyItemsList_returns400ValidationFailed() {
        var invalidCommand = new OrderCreate(1L, List.of(), "CARD-VISA", "1 Test Way");

        given()
                .contentType(ContentType.JSON)
                .body(invalidCommand)
                .when().post("/api/orders")
                .then()
                .statusCode(400)
                .body("error", is("VALIDATION_FAILED"));
    }

    @Test
    void placeOrder_insufficientStock_returns409OutOfStock() {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-GIZMO-003", 99)), "CARD-VISA", "1 Test Way");
        when(orderService.placeOrder(any(OrderCreate.class)))
                .thenThrow(new InsufficientStockException("Requested 99 of SKU-GIZMO-003 but only 5 on hand"));

        given()
                .contentType(ContentType.JSON)
                .body(command)
                .when().post("/api/orders")
                .then()
                .statusCode(409)
                .body("error", is("OUT_OF_STOCK"));
    }

    @Test
    void getById_found_returns200WithOrderDtoShape() {
        var dto = new OrderDto(
                1L, 1L, OrderStatus.CONFIRMED, 3998L, Instant.parse("2026-01-07T12:00:00Z"),
                "1 Analytical Engine Way, London", List.of(new OrderDto.Item("SKU-WIDGET-001", 2, 1999L)));
        when(orderService.getById(1L)).thenReturn(dto);

        given()
                .when().get("/api/orders/1")
                .then()
                .statusCode(200)
                .body("id", is(1))
                .body("customerId", is(1))
                .body("status", is("CONFIRMED"))
                .body("shippingAddress", is("1 Analytical Engine Way, London"));
    }

    @Test
    void getById_unknownId_returns404() {
        when(orderService.getById(eq(99L))).thenThrow(new ResourceNotFoundException("No order with id 99"));

        given()
                .when().get("/api/orders/99")
                .then()
                .statusCode(404)
                .body("error", is("NOT_FOUND"));
    }

    @Test
    void listAll_delegatesToService_returns200WithArray() {
        var dto = new OrderDto(
                1L, 1L, OrderStatus.CONFIRMED, 3998L, Instant.parse("2026-01-07T12:00:00Z"), "1 Test Way",
                List.of(new OrderDto.Item("SKU-WIDGET-001", 2, 1999L)));
        when(orderService.listAll()).thenReturn(List.of(dto));

        given()
                .when().get("/api/orders")
                .then()
                .statusCode(200)
                .body("$.size()", is(1))
                .body("[0].id", is(1));
    }
}
