package dev.patterncatalyst.gateway;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Overrides the production depth/complexity bounds (application.properties:
 * depth 8 / complexity 200, sized to comfortably fit the real GG-a
 * aggregation shape) down to a deliberately tiny budget, so {@link
 * GatewayDepthComplexityLimitTest} can prove the DRQ-069 instrumentation is
 * actually wired and active without needing a pathologically large query --
 * a SHALLOW, ordinary-looking aggregation query is enough to trip a depth of
 * 1 / complexity of 1. A separate {@code @QuarkusTest} class running under
 * THIS profile gets its own application instance (Quarkus test profiles
 * start a fresh context per profile), so {@link GatewayApiTest}'s happy-path
 * query is unaffected and keeps running under the real, production-sized
 * bounds from application.properties.
 */
public class StrictGraphQLLimitsProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "smallrye.graphql.instrumentation.queryDepth", "1",
                "smallrye.graphql.instrumentation.queryComplexity", "1");
    }
}
