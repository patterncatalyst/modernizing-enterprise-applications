package dev.patterncatalyst.strangler;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The Camel strangler-fig proxy (ch.14 "The Strangler Fig Pattern", r02-plan S7).
 *
 * <p>This is the seam machinery that sits in front of the Spring Boot monolith
 * (examples/00-monolith/) so that individual bounded contexts can be peeled off
 * onto Quarkus one at a time, with clients none the wiser about which backend
 * actually served a given request.
 *
 * <p><b>The strangler seam:</b> every request arrives here first, on
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
 * <p>Explicitly {@code @ApplicationScoped} so Quarkus/CDI — not plain
 * reflection — constructs this bean and resolves the {@code @ConfigProperty}
 * fields before {@link #configure()} runs.
 */
@ApplicationScoped
public class StranglerProxyRoute extends RouteBuilder {

    /** The strangler cutover flag for Review traffic. Defaults to the monolith. */
    @ConfigProperty(name = "strangler.review.enabled", defaultValue = "false")
    boolean reviewEnabled;

    /** The strangler cutover flag for Notification traffic. Defaults to the monolith. */
    @ConfigProperty(name = "strangler.notification.enabled", defaultValue = "false")
    boolean notificationEnabled;

    /** The strangler cutover flag for Inventory traffic (inventory-plan.md S9, ch.19, DRQ-045).
     *  Defaults to the monolith; read-side only (see class javadoc for the ACL honesty note). */
    @ConfigProperty(name = "strangler.inventory.enabled", defaultValue = "false")
    boolean inventoryEnabled;

    /** The monolith — the default backend for everything until a context is cut over. */
    @ConfigProperty(name = "strangler.monolith.base-url")
    String monolithBaseUrl;

    /** The (eventually) extracted Review service — only selected once the flag is on. */
    @ConfigProperty(name = "strangler.review.base-url")
    String reviewServiceBaseUrl;

    /** The extracted Notification service — only selected once the flag is on. */
    @ConfigProperty(name = "strangler.notification.base-url")
    String notificationServiceBaseUrl;

    /** The extracted Inventory service (examples/04-inventory-service, :8084) — only
     *  selected once strangler.inventory.enabled is on. */
    @ConfigProperty(name = "strangler.inventory.base-url")
    String inventoryServiceBaseUrl;

    private static final String TARGET_PROPERTY = "stranglerTarget";
    private static final String TARGET_REVIEW = "review";
    private static final String TARGET_NOTIFICATION = "notification";
    private static final String TARGET_INVENTORY = "inventory";
    private static final String TARGET_MONOLITH = "monolith";

    @Override
    public void configure() {

        from("platform-http:/api?matchOnUriPrefix=true")
            .routeId("strangler-fig-proxy")
            .log("strangler-proxy: ${header.CamelHttpMethod} ${header.CamelHttpPath}")

            // --- Content-based routing on the Review seam -----------------------
            // Only the Review context's path prefix is seam-aware; every other
            // /api/** path is monolith-only for the whole of r02. The flag is
            // read once per request but only ever resolves to one of the two
            // fixed, operator-configured base URLs below — never a value derived
            // from the request itself (secure-by-default dynamic-URI discipline,
            // build-plan.md §F.4).
            //
            // NOTE: platform-http's CamelHttpPath carries the FULL incoming path
            // (e.g. "/api/reviews/5"), not a path relative to this route's own
            // "/api" consumer prefix — an r02/S10 bugfix corrected an earlier
            // version of this predicate that checked for a "/reviews" prefix and
            // so never matched, silently sending 100% of Review traffic to the
            // monolith regardless of the flag. (It went unnoticed under S7-S9
            // because the monolith and review-service read/wrote the SAME shared
            // `reviews` table, so responses were identical either way — the
            // equivalence suite stayed green while testing the wrong backend.
            // Caught before decommission by stopping the monolith and confirming
            // the Review route failed instead of falling over to review-service.)
            // NOTE (notification-plan S6): the Notification seam reuses the
            // exact lesson CUTOVER.md §2 paid for on Review — match the FULL
            // incoming path "/api/notifications", never a route-relative
            // "/notifications", or the predicate silently never matches and
            // every request falls through to the monolith regardless of the
            // flag.
            .choice()
                .when(PredicateBuilder.and(
                        simple("${header.CamelHttpPath} startsWith '/api/reviews'"),
                        exchange -> reviewEnabled))
                    .setProperty(TARGET_PROPERTY, constant(TARGET_REVIEW))
                .when(PredicateBuilder.and(
                        simple("${header.CamelHttpPath} startsWith '/api/notifications'"),
                        exchange -> notificationEnabled))
                    .setProperty(TARGET_PROPERTY, constant(TARGET_NOTIFICATION))
                // NOTE (inventory-plan S9): the Inventory seam reuses the exact lesson
                // CUTOVER.md paragraph 2 paid for on Review -- match the FULL incoming
                // path "/api/inventory", never a route-relative "/inventory", or the
                // predicate silently never matches and every request falls through to
                // the monolith regardless of the flag.
                .when(PredicateBuilder.and(
                        simple("${header.CamelHttpPath} startsWith '/api/inventory'"),
                        exchange -> inventoryEnabled))
                    .setProperty(TARGET_PROPERTY, constant(TARGET_INVENTORY))
                .otherwise()
                    .setProperty(TARGET_PROPERTY, constant(TARGET_MONOLITH))
            .end()

            // --- Reverse-proxy to the chosen backend -----------------------------
            // bridgeEndpoint=true makes the HTTP producer reuse the inbound
            // CamelHttpPath/CamelHttpQuery/CamelHttpMethod as-is, so method, path,
            // query string, headers, and body all pass through unchanged.
            // throwExceptionOnFailure=false stops Camel turning a 4xx/5xx backend
            // response into a thrown exception, so the real status code and body
            // flow straight back to the caller instead of a Camel error response —
            // exactly what the equivalence suite needs to see.
            .choice()
                .when(simple("${exchangeProperty." + TARGET_PROPERTY + "} == '" + TARGET_REVIEW + "'"))
                    .to(reviewServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .when(simple("${exchangeProperty." + TARGET_PROPERTY + "} == '" + TARGET_NOTIFICATION + "'"))
                    .to(notificationServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .when(simple("${exchangeProperty." + TARGET_PROPERTY + "} == '" + TARGET_INVENTORY + "'"))
                    .to(inventoryServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
                .otherwise()
                    .to(monolithBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
            .end();
    }
}
