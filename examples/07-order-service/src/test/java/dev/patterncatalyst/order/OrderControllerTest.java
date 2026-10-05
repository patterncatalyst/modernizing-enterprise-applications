package dev.patterncatalyst.order;

import static io.restassured.RestAssured.given;
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
 * Lifted from the monolith's {@code order.OrderControllerTest} (ch.26 S4,
 * DRQ-068/073, Phase A) — same role (a controller-layer slice test that mocks
 * {@link OrderService} entirely), translated from Spring's {@code
 * @WebMvcTest}/{@code @MockitoBean} to Quarkus's {@code @QuarkusTest}/{@code
 * @InjectMock}. {@link OrderService} must be normal-scoped ({@code
 * @Scope("application")}, see its javadoc) for {@code @InjectMock} to work —
 * Quarkus cannot mock a {@code @Singleton} bean (no client proxy to swap).
 *
 * <p>Proves the {@code /api/orders} read+command contract — including the
 * {@link OrderDto} shape (byte-for-byte match with the monolith's) and the
 * 404/400 error-mapping surface — without touching a database or a real gRPC
 * channel (this is a controller-layer slice test; {@link OrderServiceTest}
 * covers the service-layer reserve/compensate logic against mocked
 * collaborators).
 */
@QuarkusTest
class OrderControllerTest {

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
                .header("Location", "/api/orders/8")
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
