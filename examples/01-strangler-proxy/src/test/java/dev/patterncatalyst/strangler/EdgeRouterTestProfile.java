package dev.patterncatalyst.strangler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Backs {@link EdgeRouterRoutingTest}: redirects all SIX extracted services'
 * base-urls to in-JVM {@link StubBackendServer} instances, so the test
 * proves {@link StranglerProxyRoute}'s unconditional, flagless
 * content-based routing (order-plan.md S10, DRQ-070) in isolation — no real
 * service process needs to be running. There is no flag-off/flag-on pair of
 * profiles anymore (unlike the pre-S10 per-context Flag*Profile classes this
 * replaces): since the strangler.*.enabled flags and the
 * strangler.monolith.base-url fallback have been retired, there is only ONE
 * routing state left to prove — every path routes straight to its extracted
 * service, always.
 */
public class EdgeRouterTestProfile implements QuarkusTestProfile {

    static final StubBackendServer REVIEW = new StubBackendServer("review");
    static final StubBackendServer NOTIFICATION = new StubBackendServer("notification");
    static final StubBackendServer INVENTORY = new StubBackendServer("inventory");
    static final StubBackendServer PAYMENT = new StubBackendServer("payment");
    static final StubBackendServer SHIPPING = new StubBackendServer("shipping");
    static final StubBackendServer ORDER = new StubBackendServer("order");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "strangler.review.base-url", REVIEW.baseUrl(),
                "strangler.notification.base-url", NOTIFICATION.baseUrl(),
                "strangler.inventory.base-url", INVENTORY.baseUrl(),
                "strangler.payment.base-url", PAYMENT.baseUrl(),
                "strangler.shipping.base-url", SHIPPING.baseUrl(),
                "strangler.order.base-url", ORDER.baseUrl());
    }
}
