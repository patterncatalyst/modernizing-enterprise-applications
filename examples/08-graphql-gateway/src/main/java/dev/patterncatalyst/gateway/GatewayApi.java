package dev.patterncatalyst.gateway;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import dev.patterncatalyst.inventory.v1.GetStockRequest;
import dev.patterncatalyst.inventory.v1.InventoryGrpcServiceGrpc.InventoryGrpcServiceBlockingStub;
import dev.patterncatalyst.inventory.v1.StockReply;

import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;

/**
 * The aggregation GraphQL API (ch.26 S7, DRQ-069): {@code order(id)} resolves
 * an order over REST from order-service, and the nested {@code payments}/
 * {@code shipments} fields on that order, plus the per-item {@code stock}/
 * {@code reviews} fields, are each resolved by a {@code @Source} field
 * resolver calling the OWNING service -- order/payment/shipping/review over
 * REST, inventory over gRPC. This class holds NO state and owns NO data: it
 * is purely a stitching layer over five already-extracted services' own read
 * surfaces. Adapted from
 * {@code ~/Dev/datamesh-reference-arch-quarkus/examples/graphql-gateway}'s
 * {@code GatewayApi} (DRQ-032), widened from order+stock to the full
 * order+payments+shipments+reviews+stock view.
 *
 * <p>Every downstream target (the four REST client base URLs, the gRPC
 * host/port) is FIXED operator config in {@code application.properties} --
 * never derived from the incoming GraphQL request -- and query depth/
 * complexity are bounded there too (DRQ-069's secure-by-design requirement:
 * an aggregation gateway must not become a request-controlled fan-out/
 * amplification vector).
 */
@GraphQLApi
@ApplicationScoped
public class GatewayApi {

    @RestClient
    OrderRestClient orderRestClient;

    @RestClient
    PaymentRestClient paymentRestClient;

    @RestClient
    ShipmentRestClient shipmentRestClient;

    @RestClient
    ReviewRestClient reviewRestClient;

    @GrpcClient("inventory")
    InventoryGrpcServiceBlockingStub inventoryClient;

    /**
     * Resolves an order by id. Unknown ids raise {@link
     * OrderNotFoundException} rather than returning {@code null} directly --
     * the GraphQL Gateway Contract's GG-b expects a populated {@code
     * errors[]} array alongside {@code data.order: null}, not a silent null
     * with no error (see {@link OrderNotFoundException}'s javadoc).
     */
    @Query("order")
    public OrderView order(@Id @Name("id") String id) {
        try {
            OrderDto dto = orderRestClient.getById(id);
            return OrderView.from(dto);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) {
                throw new OrderNotFoundException("order " + id + " not found");
            }
            // Any other downstream status (5xx, etc.) bubbles up as-is --
            // SmallRye GraphQL maps it to a masked DataFetchingException
            // entry in errors[], the secure default for an exception that
            // isn't explicitly whitelisted via show-runtime-exception-message.
            throw e;
        }
    }

    /** Federated from payment-service: every CAPTURED/DECLINED payment attempt against this order. */
    public List<PaymentView> payments(@Source OrderView order) {
        return paymentRestClient.listByOrderId(Long.valueOf(order.id())).stream()
                .map(PaymentView::from)
                .toList();
    }

    /** Federated from shipping-service: every shipment recorded against this order. */
    public List<ShipmentView> shipments(@Source OrderView order) {
        return shipmentRestClient.listByOrderId(Long.valueOf(order.id())).stream()
                .map(ShipmentView::from)
                .toList();
    }

    /** Federated from review-service: every review posted for this line item's sku. */
    public List<ReviewView> reviews(@Source OrderItemView item) {
        return reviewRestClient.listBySku(item.sku()).stream()
                .map(ReviewView::from)
                .toList();
    }

    /**
     * Federated from inventory-service over gRPC -- the real-time
     * availability figure for this line item's sku, never a cached/
     * denormalized copy. Called only when a client actually selects {@code
     * stock} (standard MicroProfile GraphQL {@code @Source} laziness).
     */
    public StockView stock(@Source OrderItemView item) {
        StockReply reply = inventoryClient.withDeadlineAfter(3, TimeUnit.SECONDS)
                .getStock(GetStockRequest.newBuilder()
                        .setStockKeepingUnit(item.sku())
                        .build());
        return new StockView(reply.getStockKeepingUnit(), reply.getOnHandQty(), reply.getAvailable());
    }
}
