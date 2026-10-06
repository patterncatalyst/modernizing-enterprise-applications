package dev.patterncatalyst.strangler;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The Camel edge router for the six extracted bounded contexts — originally
 * the strangler-fig proxy (ch.14 "The Strangler Fig Pattern", r02-plan S7),
 * now its permanent successor as of order-plan.md S10 (DRQ-070).
 *
 * <p><b>order-plan.md S10 update — the strangler fig completes (DRQ-070,
 * HARD PART H4/H5):</b> Order was the sixth and last bounded context
 * extracted from {@code examples/00-monolith/}; with the monolith now fully
 * decommissioned (frozen in-repo as the "before" referent, not part of the
 * running topology — see its {@code SMELLS.md}), there is no host tree left
 * to strangle and no fallback backend to route to. The six per-context
 * {@code strangler.*.enabled} cutover flags and the
 * {@code strangler.monolith.base-url} default backend documented throughout
 * the history below have all been **retired** — every field and config
 * property that implemented them is gone from this class and from
 * {@code application.properties}. {@link #configure()} now does straight,
 * unconditional content-based routing on URI path prefix to one of the six
 * extracted services; an unmatched path returns {@code 404} directly (there
 * is nothing left to fall through to). This class is kept as the single
 * {@code RouteBuilder} it always was — only its role changed, from a
 * temporary seam to the system's permanent REST edge router.
 *
 * <p><b>The sections below are preserved verbatim as the historical record
 * of each of the six cutovers</b> (the per-context "The flag: ..." and "ACL
 * honesty note" paragraphs) — they describe real, demonstrated reversibility
 * windows that existed right up until each context's own decommission step,
 * and the field-by-field proof that no Camel message translator was ever
 * needed at this layer. Read them as history, not as a description of the
 * live `@ConfigProperty` fields on this class today (those fields no longer
 * exist — see the field declarations and {@link #configure()} below for the
 * current, flagless routing table).
 *
 * <p>This is the seam machinery that originally sat in front of the Spring
 * Boot monolith (examples/00-monolith/) so that individual bounded contexts
 * could be peeled off onto Quarkus one at a time, with clients none the
 * wiser about which backend actually served a given request.
 *
 * <p><b>The strangler seam (historical, r02/S7):</b> every request arrives here first, on
 * {@code :8888}. By default (and for all of r02/S7) <i>every</i> request —
 * including {@code /api/reviews/**} — is forwarded unchanged to the monolith
 * on {@code :8080}: the proxy is a transparent reverse proxy. The behavior-
 * equivalence suite (tooling/newman/mea.postman_collection.json) is run
 * straight through this proxy to prove exactly that transparency: the proxy
 * must introduce zero observable difference versus talking to the monolith
 * directly.
 *
 * <p><b>The flag:</b> {@code strangler.review.enabled} (default {@code true} as
 * of r02/S10) is the single cutover switch for the Review bounded context, the
 * first extraction in the decomposition roadmap (build-plan.md §E).
 * Content-based routing on the {@code /api/reviews} path prefix consults this
 * flag to pick the backend:
 * <ul>
 *   <li>{@code false} (r02/S7–S9) → Review requests went to the monolith.
 *       Both this and the {@code true} state were proven green against the
 *       full behavior-equivalence suite before the monolith's Review module
 *       was decommissioned — see CUTOVER.md.</li>
 *   <li>{@code true} (permanent default, from S10) → Review requests go to
 *       the extracted Quarkus service (examples/02-review-service/). The
 *       monolith's Review module has since been decommissioned (removed), so
 *       flipping this back to {@code false} today would just reach a
 *       monolith that 404s on {@code /api/reviews/**} — reversibility was a
 *       real, demonstrated property right up to that decommission step, which
 *       is deliberately the one irreversible move in the sequence.</li>
 * </ul>
 * Flipping the flag is a config change and a restart — no route code changes,
 * which is what made the cutover (and, before decommission, its reversal)
 * safe.
 *
 * <p><b>The Notification seam (notification-plan.md S6/S7, ch.17, DRQ-036):</b>
 * {@code strangler.notification.enabled} is the second cutover flag, added
 * alongside Review's, with the identical shape: content-based routing on the
 * {@code /api/notifications} path prefix. It is the <i>read-side</i> half of
 * a two-flag reversibility story — the monolith's own
 * {@code notification.mode=synchronous|outbox} config (unrelated file,
 * {@code examples/00-monolith/.../application.yml}) is the write-side half.
 * A real cutover flips both together: the monolith stops sending the
 * synchronous confirmation and instead writes a transactional outbox row
 * that a scheduled relay publishes to Kafka; this proxy stops routing
 * {@code /api/notifications} reads to the monolith and instead routes them
 * to the extracted Quarkus service, which has consumed that same event into
 * its own store. See {@code examples/01-strangler-proxy/CUTOVER.md} for
 * the cutover evidence trail (reversibility baseline, cutover run, and the
 * negative check that proves the async pipeline is genuinely exercised).
 *
 * <p><b>The Inventory seam (inventory-plan.md S9, ch.19, DRQ-045):</b>
 * {@code strangler.inventory.enabled} is the third cutover flag, added
 * alongside Review's and Notification's, with the identical content-based
 * routing shape on the {@code /api/inventory} path prefix (full path, not a
 * route-relative prefix, per the CUTOVER.md paragraph 2 lesson). It is the
 * read-side half of a two-flag reversibility story (DRQ-045): the monolith's
 * own {@code inventory.mode=local|remote} config (a different file,
 * {@code examples/00-monolith/.../application.yml}) is the write/reserve-side
 * half, governing whether {@code OrderService#placeOrder} reserves stock
 * in-JVM or over gRPC against {@code examples/04-inventory-service/}. This
 * proxy flag only ever affects the public REST read surface,
 * {@code GET /api/inventory} and {@code GET /api/inventory/{sku}}.
 *
 * <p><b>ACL honesty note (DRQ-045/H4, ch.16's InventoryAclRoute sketch):</b>
 * unlike the sketch in {@code _docs/16-content-based-routing-acl.md}, this
 * route does not enrich-and-translate. Both backends --
 * {@code examples/00-monolith/.../common/StockDto.java} and
 * {@code examples/04-inventory-service/.../StockDto.java} (lifted unchanged,
 * same field names/types/order) -- already return byte-for-byte identical
 * JSON for this read surface, so a translating message translator here would
 * have nothing to translate; it would be a no-op ACL fabricated for a
 * contract that does not differ. This branch is therefore an honest,
 * transparent reverse proxy, exactly like the Review and Notification
 * branches above it. The real anti-corruption layer for the order-to-inventory
 * seam lives at the gRPC boundary instead -- {@code inventory.proto}'s
 * distinct wire vocabulary ({@code stock_keeping_unit}/
 * {@code unit_price_cents}/{@code on_hand_qty}) versus the internal
 * {@code sku}/{@code priceCents}/{@code quantityOnHand} fields, translated by
 * the monolith's {@code RemoteInventoryClient} (proto to internal, client
 * side) and the inventory service's {@code InventoryGrpcServiceImpl}
 * (internal to proto, server side). See this project's README.md ("The
 * flag: strangler.inventory.enabled") for the full writeup of where ch.16's
 * sketch actually gets realized.
 *
 * <p><b>The Payment seam (payment-plan.md S7/S9, ch.23, DRQ-052):</b>
 * {@code strangler.payment.enabled} is the fourth cutover flag, added
 * alongside Review's, Notification's, and Inventory's, with the identical
 * content-based routing shape on the {@code /api/payments} path prefix
 * (full path, not a route-relative prefix, per the CUTOVER.md paragraph 2
 * lesson, applied here for a fourth time). <b>payment-plan S9 DECOMMISSION
 * (committed default, now permanent, irreversible step):</b> it now defaults
 * to {@code true} — the monolith's in-process payment module (
 * {@code payment.PaymentController}/{@code PaymentService}/{@code Payment}/
 * {@code PaymentRepository}) has been decommissioned and its
 * {@code payment.mode=synchronous|choreographed} flag removed entirely, so
 * {@code /api/payments} on the monolith now 404s — flipping this flag back
 * to {@code false} today would just reach that 404. Before this flip, S8
 * proved the cutover (and its reversibility) with both flags flipped back
 * afterward; see {@code examples/01-strangler-proxy/application.properties}
 * for the full evidence trail (reversibility baseline, cutover run with the
 * bounded-wait Scenario 1/3 proof, and the negative check).
 *
 * <p><b>ACL honesty note (payment-plan S7):</b> the same call already made
 * for Inventory above applies here too. The monolith's
 * {@code payment.PaymentDto} and the payment service's
 * {@code dev.patterncatalyst.payment.PaymentDto} were byte-for-byte identical
 * records — {@code id}, {@code orderId}, {@code amountCents}, {@code method},
 * {@code status}, {@code createdAt}, same names/types/order — before the
 * monolith's copy was deleted in S9. {@code orderId} was already a plain
 * {@code Long} on the wire in the monolith (only the JPA entity carried a
 * cross-context {@code @ManyToOne Order} join, {@code SMELL[ch.18]}); the
 * payment service's {@code Payment} entity decomposes that FK to a plain
 * {@code orderId} value column (r06/S4, DRQ-052 Phase A), but that is an
 * internal persistence-layer change that never reached the read contract, so
 * the wire shape needed zero changes. The {@code payment.captured}/
 * {@code payment.declined} Kafka events the two sides exchange are likewise
 * authored field-for-field identical on both sides (DRQ-038: plain JSON, no
 * shared code between reactors). There is therefore nothing for a Camel
 * message translator to translate at this seam — building one (as the
 * plan's step title, "wire PaymentAclRoute", literally suggests) would
 * fabricate a no-op ACL for a contract that does not differ, the same
 * speculative-infrastructure trap the Inventory precedent above already
 * ruled out. This branch is therefore an honest, transparent reverse proxy,
 * exactly like the Review, Notification, and Inventory branches above it —
 * there is no {@code PaymentAclRoute} class in this package. See this
 * project's README.md ("The flag: strangler.payment.enabled") for the full
 * field-by-field writeup.
 *
 * <p><b>The Shipping seam (shipping-plan.md S7/S9, ch.24, DRQ-065):</b>
 * {@code strangler.shipping.enabled} is the fifth cutover flag, added
 * alongside Review's, Notification's, Inventory's, and Payment's, with the
 * identical content-based routing shape on the {@code /api/shipments} path
 * prefix (full path, not a route-relative prefix, per the CUTOVER.md
 * paragraph 2 lesson, applied here for a fifth time). <b>shipping-plan S9
 * DECOMMISSION (committed default, now permanent, irreversible step):</b> it
 * now defaults to {@code true} — the monolith's in-process shipping module
 * ({@code shipping.ShippingController}/{@code ShippingService}/{@code
 * Shipment}/{@code ShipmentStatus}/{@code ShipmentDto}/{@code
 * ShipmentRepository}) has been decommissioned and its {@code
 * shipping.mode=inprocess|orchestrated} flag removed entirely, so {@code
 * /api/shipments} on the monolith now 404s — flipping this flag back to
 * {@code false} today would just reach that 404. Before this flip, S8
 * proved the cutover (and its reversibility) with both flags flipped back
 * afterward; see {@code examples/01-strangler-proxy/application.properties}
 * for the full evidence trail (reversibility baseline, cutover run with the
 * bounded-wait Scenario 1/4 proof, and the negative check).
 *
 * <p><b>ACL honesty note (shipping-plan S7):</b> the same call already made
 * for Inventory and Payment above applies here too. The monolith's
 * {@code shipping.ShipmentDto} and the shipping service's
 * {@code dev.patterncatalyst.shipping.ShipmentDto} were byte-for-byte
 * identical records — {@code id}, {@code orderId}, {@code address},
 * {@code status}, {@code createdAt}, same names/types/order (the shipping
 * service's copy was lifted unchanged) — before the monolith's copy was
 * deleted in S9. The shipping service's {@code ShipmentStatus} enum adds
 * {@code PENDING}/{@code CANCELLED}/{@code FAILED} (needed by the
 * shipping-plan S5 saga's compensation leg) alongside the monolith's single
 * {@code DISPATCHED} value, but that never changed the wire shape — {@code
 * status} is still a plain JSON string, and {@code DISPATCHED} is the only
 * value either side has ever produced so far. There is therefore nothing
 * for a Camel message translator to translate at this seam — building one
 * would fabricate a no-op ACL for a contract that does not differ, the same
 * speculative-infrastructure trap the Inventory and Payment precedents
 * above already ruled out. This branch is therefore an honest, transparent
 * reverse proxy, exactly like the Review, Notification, Inventory, and
 * Payment branches above it — there is no {@code ShippingAclRoute} class in
 * this package. See this project's README.md ("The flag:
 * strangler.shipping.enabled") for the full field-by-field writeup.
 *
 * <p><b>The Order seam (order-plan.md S8, ch.26, DRQ-065 precedent) — the
 * SIXTH and LAST strangler flag:</b> {@code strangler.order.enabled} is
 * added alongside Review's, Notification's, Inventory's, Payment's, and
 * Shipping's, with the identical content-based routing shape on the
 * {@code /api/orders} path prefix (full path, not a route-relative prefix,
 * per the CUTOVER.md paragraph 2 lesson, applied here for a sixth time).
 * Unlike the five prior flags, this one routes <i>both</i> the POST checkout
 * command and the GET reads in a single branch — the whole order bounded
 * context (the god {@code OrderService}/{@code OrderSagaListener} hub, SMELL
 * #2) moves at once, there being no narrower read/write split worth staging
 * at the proxy layer (the CQRS write/read split happens inside the order
 * service itself, DRQ-067). It defaults to {@code false} — the monolith
 * still serves {@code /api/orders} until order-plan S9's cutover, the LAST
 * flag flip in the whole book: order-plan S10 is the single, deliberately
 * irreversible decommission for the whole system (there is no seventh
 * context left to extract, and no monolith left to fall back to afterward).
 *
 * <p><b>ACL honesty note (DRQ-065 precedent, the same call already made for
 * Inventory, Payment, and Shipping above):</b> the monolith's
 * {@code common.OrderDto} and the order service's
 * {@code dev.patterncatalyst.order.OrderDto} are byte-for-byte identical
 * records — {@code id}, {@code customerId}, {@code status},
 * {@code totalCents}, {@code createdAt}, {@code shippingAddress},
 * {@code items[sku, quantity, unitPriceCents]} — same field names, types,
 * and order; the order service's copy was lifted unchanged (order-plan
 * S4/S5, DRQ-068/073 Phase A). {@code customerId} was already a plain
 * {@code Long} on the wire in the monolith (the FK decomposition, DRQ-068,
 * changes only the order service's INTERNAL JPA mapping — no more
 * {@code @ManyToOne Customer} — never this external read contract). There is
 * therefore nothing for a Camel message translator to translate at this
 * seam — building one would fabricate a no-op ACL for a contract that does
 * not differ, the same speculative-infrastructure trap the Inventory/
 * Payment/Shipping precedents above already ruled out. This branch is
 * therefore an honest, transparent reverse proxy, exactly like the Review,
 * Notification, Inventory, Payment, and Shipping branches above it — there
 * is no {@code OrderAclRoute} class in this package. See this project's
 * README.md ("The flag: strangler.order.enabled") for the full
 * field-by-field writeup.
 *
 * <p>Explicitly {@code @ApplicationScoped} so Quarkus/CDI — not plain
 * reflection — constructs this bean and resolves the {@code @ConfigProperty}
 * fields before {@link #configure()} runs.
 */
@ApplicationScoped
public class StranglerProxyRoute extends RouteBuilder {

    // order-plan.md S10 (DRQ-070): the six strangler.*.enabled boolean flags
    // and the strangler.monolith.base-url fallback are RETIRED. Only the six
    // extracted services' base URLs remain -- fixed, operator-configured
    // targets the route picks between by URI path prefix alone, never by a
    // flag and never by a value derived from the request itself (the same
    // secure-by-default dynamic-URI discipline the retired flags upheld,
    // build-plan.md §F.4).

    /** The extracted Review service (examples/02-review-service, :8081). */
    @ConfigProperty(name = "strangler.review.base-url")
    String reviewServiceBaseUrl;

    /** The extracted Notification service (examples/03-notification-service, :8083). */
    @ConfigProperty(name = "strangler.notification.base-url")
    String notificationServiceBaseUrl;

    /** The extracted Inventory service (examples/04-inventory-service, :8084). */
    @ConfigProperty(name = "strangler.inventory.base-url")
    String inventoryServiceBaseUrl;

    /** The extracted Payment service (examples/05-payment-service, :8085). */
    @ConfigProperty(name = "strangler.payment.base-url")
    String paymentServiceBaseUrl;

    /** The extracted Shipping service (examples/06-shipping-service, :8088). */
    @ConfigProperty(name = "strangler.shipping.base-url")
    String shippingServiceBaseUrl;

    /** The extracted Order service (examples/07-order-service, :8087) -- the
     *  sixth and last context cut over. Not :8086 (Debezium) or :8090 (the
     *  GraphQL gateway, order-plan.md DRQ-069, which has its own front door
     *  and is never routed through this proxy). */
    @ConfigProperty(name = "strangler.order.base-url")
    String orderServiceBaseUrl;

    @Override
    public void configure() {

        from("platform-http:/api?matchOnUriPrefix=true")
            .routeId("edge-router")
            .log("edge-router: ${header.CamelHttpMethod} ${header.CamelHttpPath}")

            // --- Unconditional content-based routing, by URI path prefix only ---
            // order-plan.md S10 collapse (DRQ-070): six independent per-context
            // choice()+flag branches (one target-selection choice(), one
            // backend-dispatch choice(), joined by an exchangeProperty) have
            // collapsed into this single choice() that both decides AND
            // dispatches in one step -- there is no flag left to consult, so
            // there is nothing left to stage between "which context" and
            // "which backend". bridgeEndpoint=true makes the HTTP producer
            // reuse the inbound CamelHttpPath/CamelHttpQuery/CamelHttpMethod
            // as-is (method, path, query string, headers, and body all pass
            // through unchanged); throwExceptionOnFailure=false stops Camel
            // turning a 4xx/5xx backend response into a thrown exception, so
            // the real status code and body flow straight back to the caller
            // instead of a Camel error response -- exactly what the
            // contract/acceptance suite needs to see (same behavior the
            // strangler-era route always had).
            //
            // NOTE: platform-http's CamelHttpPath carries the FULL incoming
            // path (e.g. "/api/reviews/5"), not a path relative to this
            // route's own "/api" consumer prefix -- the r02/S10 lesson
            // (CUTOVER.md §2: a route-relative "/reviews" prefix silently
            // never matches) applies to every branch below, for a sixth and
            // final time.
            .choice()
                .when(simple("${header.CamelHttpPath} startsWith '/api/reviews'"))
                    .to(reviewServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .when(simple("${header.CamelHttpPath} startsWith '/api/notifications'"))
                    .to(notificationServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .when(simple("${header.CamelHttpPath} startsWith '/api/inventory'"))
                    .to(inventoryServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .when(simple("${header.CamelHttpPath} startsWith '/api/payments'"))
                    .to(paymentServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .when(simple("${header.CamelHttpPath} startsWith '/api/shipments'"))
                    .to(shippingServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                // The sixth and last context: routes BOTH the POST checkout
                // command and every GET read through a single branch -- the
                // whole order bounded context moves at once (order-plan S8),
                // there being no narrower read/write split worth staging at
                // this layer (the CQRS write/read split, DRQ-067, happens
                // inside the order service itself).
                .when(simple("${header.CamelHttpPath} startsWith '/api/orders'"))
                    .to(orderServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                // There is no monolith left to fall back to (DRQ-070): an
                // unmatched /api/** path -- or any path outside /api/** this
                // platform-http consumer's matchOnUriPrefix also catches --
                // is answered directly with 404, never forwarded anywhere.
                .otherwise()
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                    .setHeader("Content-Type", constant("application/json"))
                    .setBody(constant("{\"error\":\"NOT_FOUND\",\"message\":\"No route for this path -- the monolith has been decommissioned (order-plan.md DRQ-070)\"}"))
            .end();
    }
}
