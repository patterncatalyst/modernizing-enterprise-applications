package dev.patterncatalyst.strangler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Backs {@link PaymentRouteFlagOffTest}: {@code strangler.payment.enabled}
 * stays at its committed default ({@code false}), and both the monolith's
 * and the payment service's base-urls are redirected to in-JVM
 * {@link StubBackendServer} instances, so the test proves the ROUTE's target
 * selection in isolation — no real monolith or payment-service process
 * needs to be running (that end-to-end proof, through the full podman
 * stack, is payment-plan S8).
 */
public class PaymentFlagOffProfile implements QuarkusTestProfile {

    static final StubBackendServer MONOLITH = new StubBackendServer("monolith");
    static final StubBackendServer PAYMENT = new StubBackendServer("payment");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "strangler.payment.enabled", "false",
                "strangler.monolith.base-url", MONOLITH.baseUrl(),
                "strangler.payment.base-url", PAYMENT.baseUrl());
    }
}
