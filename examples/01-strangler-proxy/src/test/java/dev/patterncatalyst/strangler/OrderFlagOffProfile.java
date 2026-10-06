package dev.patterncatalyst.strangler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Backs {@link OrderRouteFlagOffTest}: {@code strangler.order.enabled} stays
 * at its committed default ({@code false}), and both the monolith's and the
 * order service's base-urls are redirected to in-JVM {@link
 * StubBackendServer} instances, so the test proves the ROUTE's target
 * selection in isolation — no real monolith or order-service process needs
 * to be running (the end-to-end cutover proof, through the full podman
 * stack, is order-plan S9).
 */
public class OrderFlagOffProfile implements QuarkusTestProfile {

    static final StubBackendServer MONOLITH = new StubBackendServer("monolith");
    static final StubBackendServer ORDER = new StubBackendServer("order");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "strangler.order.enabled", "false",
                "strangler.monolith.base-url", MONOLITH.baseUrl(),
                "strangler.order.base-url", ORDER.baseUrl());
    }
}
