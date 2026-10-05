package dev.patterncatalyst.shipping;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * r07/ch.24 S5 (DRQ-059 "enrich") -- the saga's ENRICHMENT CONTRACT
 * decision, made explicit and documented here rather than silently assumed.
 *
 * <h2>The investigation (what the real contract looks like today)</h2>
 * The saga's trigger ({@link PaymentCaptured}) does not carry a shipping
 * address. Reading the monolith directly
 * ({@code examples/00-monolith/.../common/OrderDto.java} and
 * {@code order/OrderController.java}): {@code OrderDto} exposes
 * {@code id, customerId, status, totalCents, createdAt, items} -- NO
 * {@code shippingAddress} field, even though the order entity itself
 * ({@code order.Order#shippingAddress}) and the original checkout payload
 * ({@code common.OrderCreate#shippingAddress}) both have it. So
 * {@code GET /api/orders/{id}} cannot supply the address TODAY.
 *
 * <h2>The decision</h2>
 * Per this step's explicit instructions, that monolith-side gap is S6's
 * lane (monolith wiring), NOT this step's (scope is
 * {@code examples/06-shipping-service/} only) -- so this client is coded
 * against the INTENDED contract (an order read surface that DOES expose
 * {@code shippingAddress}, {@link OrderSummary}/{@link OrderServiceClient}),
 * exercised in tests via an injected test double ({@code @InjectMock
 * OrderReadClient}, never a live REST call), and the gap is flagged loudly
 * here, in {@code MIGRATION.md}, and in this step's report for S6 to close.
 *
 * <h2>The not-yet-wired behavior (defined, not a silent assumption)</h2>
 * {@link #fetchShippingAddress(Long)} NEVER throws and NEVER returns
 * {@code null}: if the REST call fails (connection refused -- the monolith
 * isn't reachable at the configured base URL, a timeout, a non-2xx
 * response) OR it succeeds but {@code shippingAddress} is absent (today's
 * real, unmodified {@code OrderDto}), this returns the documented sentinel
 * {@link #ADDRESS_UNAVAILABLE} and logs a warning -- the saga proceeds to
 * dispatch with that placeholder rather than hard-failing. This exactly
 * mirrors payment-service's {@code UNSPECIFIED_METHOD} fallback for its own
 * documented forward-compatibility gap (a real, unmodified {@code
 * order.placed} payload not yet carrying a payment method) -- same
 * demonstrated pattern: a defined, non-throwing substitute for a
 * not-yet-wired upstream field, verified live in that precedent against
 * ~160 real historical events with zero errors.
 *
 * <p><b>Deliberately NOT equal to the {@code SHIP-FAIL} sentinel</b> (DRQ-062)
 * -- a monolith-unreachable/not-yet-wired condition must never masquerade
 * as the deterministic carrier-booking-failure demo rule the equivalence
 * suite depends on.
 */
@ApplicationScoped
public class OrderReadClient {

    private static final Logger LOG = Logger.getLogger(OrderReadClient.class);

    /**
     * Documented fallback for "could not obtain a real shipping address" --
     * see the class javadoc. Never equal to {@code SHIP-FAIL}
     * ({@link ShipmentSagaSteps#SHIP_FAIL_SENTINEL}).
     */
    public static final String ADDRESS_UNAVAILABLE = "ADDRESS-UNAVAILABLE-PENDING-S6";

    private final OrderServiceClient client;

    public OrderReadClient(@RestClient OrderServiceClient client) {
        this.client = client;
    }

    public String fetchShippingAddress(Long orderId) {
        try {
            OrderSummary order = client.getOrder(orderId);
            if (order == null || order.shippingAddress() == null || order.shippingAddress().isBlank()) {
                LOG.warnf(
                        "order %d's read surface did not provide a shippingAddress (S6 gap -- OrderDto does not "
                                + "expose it yet); using fallback '%s'",
                        orderId, ADDRESS_UNAVAILABLE);
                return ADDRESS_UNAVAILABLE;
            }
            return order.shippingAddress();
        } catch (RuntimeException e) {
            LOG.warnf(e,
                    "failed to enrich order %d from the order service (unreachable/error); using fallback '%s'",
                    orderId, ADDRESS_UNAVAILABLE);
            return ADDRESS_UNAVAILABLE;
        }
    }
}
