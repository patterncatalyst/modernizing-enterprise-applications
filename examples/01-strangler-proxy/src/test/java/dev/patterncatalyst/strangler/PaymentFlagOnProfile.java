package dev.patterncatalyst.strangler;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Backs {@link PaymentRouteFlagOnTest}: {@code strangler.payment.enabled} is
 * overridden to {@code true} (the payment-plan S8 cutover state), with both
 * the monolith's and the payment service's base-urls redirected to in-JVM
 * {@link StubBackendServer} instances, so the test proves the ROUTE's target
 * selection in isolation — see {@link PaymentFlagOffProfile} for why no real
 * backend process is needed here.
 */
public class PaymentFlagOnProfile implements QuarkusTestProfile {

    static final StubBackendServer MONOLITH = new StubBackendServer("monolith");
    static final StubBackendServer PAYMENT = new StubBackendServer("payment");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "strangler.payment.enabled", "true",
                "strangler.monolith.base-url", MONOLITH.baseUrl(),
                "strangler.payment.base-url", PAYMENT.baseUrl());
    }
}
