package dev.patterncatalyst.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.WebApplicationException;

/**
 * Exercises the aggregation {@code order(id)} query end to end through
 * {@code /graphql}, mirroring the GraphQL Gateway Contract's staged GG-a/
 * GG-b newman assertions (ch.26 S2/S7, DRQ-069/DRQ-071,
 * {@code tooling/newman/mea.postman_collection.json}) field-for-field, plus
 * the downstream-unavailable resilience case the plan calls out. The four
 * REST downstreams are mocked with {@link InjectMock} (normal-scoped REST
 * client beans); the inventory-service gRPC downstream is served by the
 * in-process {@link MockInventoryGrpcService} -- the gateway's real {@code
 * @GrpcClient} dials it over the wire (see that class's javadoc for why).
 */
@QuarkusTest
class GatewayApiTest {

    private static final String ORDER_ID = "42";
    private static final Long ORDER_ID_LONG = 42L;
    private static final String SKU = MockInventoryGrpcService.SKU;

    @InjectMock
    @RestClient
    OrderRestClient orderRestClient;

    @InjectMock
    @RestClient
    PaymentRestClient paymentRestClient;

    @InjectMock
    @RestClient
    ShipmentRestClient shipmentRestClient;

    @InjectMock
    @RestClient
    ReviewRestClient reviewRestClient;

    private static final String AGGREGATE_QUERY = "query OrderAggregate($id: ID!) { order(id: $id) { "
            + "id customerId status totalCents createdAt shippingAddress "
            + "items { sku quantity unitPriceCents stock { quantityOnHand } reviews { id rating comment } } "
            + "payments { id status amountCents method createdAt } "
            + "shipments { id status address createdAt } } }";

    private OrderDto happyPathOrder() {
        return new OrderDto(
                42L,
                7L,
                OrderStatus.CONFIRMED,
                5000L,
                "2026-10-01T00:00:00Z",
                "123 Main St, Springfield",
                List.of(new OrderDto.Item(SKU, 2, 2500)));
    }

    /**
     * The GG-a shape, field-for-field: no errors, non-null {@code
     * data.order}, order fields incl. {@code shippingAddress}, {@code
     * items[].stock.quantityOnHand} + {@code items[].reviews[]}, at least
     * one CAPTURED payment, at least one shipment.
     */
    @Test
    void aggregatesOrderAcrossAllFiveServices() {
        when(orderRestClient.getById(ORDER_ID)).thenReturn(happyPathOrder());
        when(paymentRestClient.listByOrderId(ORDER_ID_LONG)).thenReturn(List.of(
                new PaymentDto(1L, ORDER_ID_LONG, 5000L, "CARD", PaymentStatus.CAPTURED, "2026-10-01T00:05:00Z")));
        when(shipmentRestClient.listByOrderId(ORDER_ID_LONG)).thenReturn(List.of(
                new ShipmentDto(1L, ORDER_ID_LONG, "123 Main St, Springfield", ShipmentStatus.DISPATCHED,
                        "2026-10-02T00:00:00Z")));
        when(reviewRestClient.listBySku(SKU)).thenReturn(List.of(
                new ReviewDto(9L, 7L, SKU, 5, "Great widget!", "2026-09-15T00:00:00Z")));

        String body = "{ \"query\": " + quote(AGGREGATE_QUERY) + ", \"variables\": { \"id\": \"" + ORDER_ID + "\" } }";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("errors", nullValue())
                .body("data.order", notNullValue())
                .body("data.order.id", is(ORDER_ID))
                .body("data.order.customerId", is(7))
                .body("data.order.status", is("CONFIRMED"))
                .body("data.order.totalCents", is(5000))
                .body("data.order.createdAt", not(emptyOrNullString()))
                .body("data.order.shippingAddress", is("123 Main St, Springfield"))
                .body("data.order.items", hasSize(1))
                .body("data.order.items[0].sku", is(SKU))
                .body("data.order.items[0].quantity", is(2))
                .body("data.order.items[0].unitPriceCents", is(2500))
                .body("data.order.items[0].stock.quantityOnHand", is(MockInventoryGrpcService.QUANTITY_ON_HAND))
                .body("data.order.items[0].reviews", hasSize(1))
                .body("data.order.items[0].reviews[0].rating", is(5))
                .body("data.order.items[0].reviews[0].comment", is("Great widget!"))
                .body("data.order.payments", hasSize(greaterThanOrEqualTo(1)))
                .body("data.order.payments[0].status", is("CAPTURED"))
                .body("data.order.shipments", hasSize(greaterThanOrEqualTo(1)));
    }

    /**
     * The GG-b negative check: an unknown id must raise a resolver-level
     * ERROR -- {@code data.order: null} WITH a populated {@code errors[]} --
     * not a silently-null field. Mocks the exact exception the real
     * {@code quarkus-rest-client} throws for a real order-service 404
     * (modern reactive REST clients throw {@code WebApplicationException}
     * for any status &gt;= 400 regardless of the declared return type, so a
     * raw 404 {@code Response} never reaches {@link GatewayApi}).
     */
    @Test
    void unknownOrderIdProducesErrorEnvelope() {
        when(orderRestClient.getById("999999999")).thenThrow(new WebApplicationException(404));

        String query = "query OrderAggregate($id: ID!) { order(id: $id) { id status } }";
        String body = "{ \"query\": " + quote(query) + ", \"variables\": { \"id\": \"999999999\" } }";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.order", nullValue())
                .body("errors", hasSize(greaterThanOrEqualTo(1)))
                .body("errors[0].message", not(emptyOrNullString()))
                .body("errors[0].extensions.code", is("ORDER_NOT_FOUND"));
    }

    /**
     * Downstream-unavailable resilience: when ONE federated downstream
     * fails (here, payment-service), the gateway must not crash the whole
     * request with a 500 -- GraphQL's per-field error isolation means the
     * failing field nulls out and reports an error while sibling fields
     * (shipments, and the order's own fields) still resolve. This is
     * standard graphql-java/SmallRye null-propagation behavior given that
     * {@code OrderView.payments} is a nullable list field (no {@code
     * @NonNull}) -- not bespoke exception handling in {@link GatewayApi}.
     */
    @Test
    void downstreamUnavailableProducesPartialDataNotACrash() {
        when(orderRestClient.getById(ORDER_ID)).thenReturn(happyPathOrder());
        when(paymentRestClient.listByOrderId(ORDER_ID_LONG))
                .thenThrow(new RuntimeException("payment-service connection refused"));
        when(shipmentRestClient.listByOrderId(ORDER_ID_LONG)).thenReturn(List.of(
                new ShipmentDto(1L, ORDER_ID_LONG, "123 Main St, Springfield", ShipmentStatus.DISPATCHED,
                        "2026-10-02T00:00:00Z")));

        String query = "{ order(id: \"" + ORDER_ID + "\") { id payments { id } shipments { id } } }";
        String body = "{ \"query\": " + quote(query) + " }";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.order", notNullValue())
                .body("data.order.id", is(ORDER_ID))
                .body("data.order.payments", nullValue())
                .body("data.order.shipments", hasSize(1))
                .body("errors", hasSize(greaterThanOrEqualTo(1)))
                .body("errors[0].path", contains("order", "payments"));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
