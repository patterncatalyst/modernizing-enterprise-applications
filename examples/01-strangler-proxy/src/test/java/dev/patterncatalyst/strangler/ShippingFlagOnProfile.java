package dev.patterncatalyst.strangler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Backs {@link ShippingRouteFlagOnTest}: {@code strangler.shipping.enabled} is
 * overridden to {@code true} (the shipping-plan S8 cutover state), with both
 * the monolith's and the shipping service's base-urls redirected to in-JVM
 * {@link StubBackendServer} instances, so the test proves the ROUTE's target
 * selection in isolation — see {@link ShippingFlagOffProfile} for why no real
 * backend process is needed here.
 */
public class ShippingFlagOnProfile implements QuarkusTestProfile {

    static final StubBackendServer MONOLITH = new StubBackendServer("monolith");
    static final StubBackendServer SHIPPING = new StubBackendServer("shipping");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "strangler.shipping.enabled", "true",
                "strangler.monolith.base-url", MONOLITH.baseUrl(),
                "strangler.shipping.base-url", SHIPPING.baseUrl());
    }
}
