package dev.patterncatalyst.strangler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Backs {@link OrderRouteFlagOnTest}: {@code strangler.order.enabled} is
 * overridden to {@code true} (the order-plan S9 cutover state), with both
 * the monolith's and the order service's base-urls redirected to in-JVM
 * {@link StubBackendServer} instances, so the test proves the ROUTE's target
 * selection in isolation — see {@link OrderFlagOffProfile} for why no real
 * backend process is needed here.
 */
public class OrderFlagOnProfile implements QuarkusTestProfile {

    static final StubBackendServer MONOLITH = new StubBackendServer("monolith");
    static final StubBackendServer ORDER = new StubBackendServer("order");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "strangler.order.enabled", "true",
                "strangler.monolith.base-url", MONOLITH.baseUrl(),
                "strangler.order.base-url", ORDER.baseUrl());
    }
}
