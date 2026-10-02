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
 * <p><b>The flag:</b> {@code strangler.review.enabled} (default {@code false})
 * is the single cutover switch for the Review bounded context, the first
 * extraction in the decomposition roadmap (build-plan.md §E). Content-based
 * routing on the {@code /api/reviews} path prefix consults this flag to pick
 * the backend:
 * <ul>
 *   <li>{@code false} (today, r02/S7) → Review requests still go to the monolith.</li>
 *   <li>{@code true} (from S10, once examples/15-review-service/ exists and
 *       has passed the behavior-equivalence suite) → Review requests go to
 *       the extracted Quarkus service instead, with the monolith's Review
 *       module eventually decommissioned.</li>
 * </ul>
 * Flipping the flag is a config change and a restart — no route code changes,
 * which is what makes the cutover (and its reversal, since reversibility is a
 * design property) safe.
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

    /** The monolith — the default backend for everything until a context is cut over. */
    @ConfigProperty(name = "strangler.monolith.base-url")
    String monolithBaseUrl;

    /** The (eventually) extracted Review service — only selected once the flag is on. */
    @ConfigProperty(name = "strangler.review.base-url")
    String reviewServiceBaseUrl;

    private static final String TARGET_PROPERTY = "stranglerTarget";
    private static final String TARGET_REVIEW = "review";
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
            .choice()
                .when(PredicateBuilder.and(
                        simple("${header.CamelHttpPath} startsWith '/reviews'"),
                        exchange -> reviewEnabled))
                    .setProperty(TARGET_PROPERTY, constant(TARGET_REVIEW))
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
                .otherwise()
                    .to(monolithBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
            .end();
    }
}
