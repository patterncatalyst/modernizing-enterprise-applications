package dev.patterncatalyst.strangler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Backs {@link ShippingRouteFlagOffTest}: {@code strangler.shipping.enabled}
 * stays at its committed default ({@code false}), and both the monolith's
 * and the shipping service's base-urls are redirected to in-JVM
 * {@link StubBackendServer} instances, so the test proves the ROUTE's target
 * selection in isolation — no real monolith or shipping-service process
 * needs to be running (that end-to-end proof, through the full podman
 * stack, is shipping-plan S8).
 */
public class ShippingFlagOffProfile implements QuarkusTestProfile {

    static final StubBackendServer MONOLITH = new StubBackendServer("monolith");
    static final StubBackendServer SHIPPING = new StubBackendServer("shipping");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "strangler.shipping.enabled", "false",
                "strangler.monolith.base-url", MONOLITH.baseUrl(),
                "strangler.shipping.base-url", SHIPPING.baseUrl());
    }
}
