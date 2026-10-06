package dev.patterncatalyst.monolith.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc OpenAPI surface. Originally seeded the Newman behavior-equivalence
 * suite (S6) with the live REST contexts this monolith served.
 *
 * <p><b>order-plan.md S10 update (DRQ-070):</b> this bean is now harmless and
 * vacuous — there are no REST controllers left anywhere in this module, so
 * the generated spec documents zero paths. Left in place rather than deleted
 * (same scope-discipline treatment already given to {@code SecurityConfig}
 * after Review's own decommission) since removing it is not required for the
 * module to build or behave correctly as a frozen shell.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI monolithOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Reference Monolith API")
                        .version("0.1.0-r02")
                        .description(
                                "This monolith originally served six bounded contexts (order, inventory, "
                                        + "payment, shipping, notification, review) as one Spring MVC REST "
                                        + "surface. All six have since been extracted to standalone Quarkus "
                                        + "services and decommissioned from this module (order last, "
                                        + "order-plan.md S10, DRQ-070) — this module now serves NOTHING under "
                                        + "/api/**; every path 404s. It is kept frozen in-repo as the "
                                        + "behavior-equivalence suite's permanent golden-baseline referent "
                                        + "(DRQ-024). The complete, runnable \"before\" (all six contexts live) "
                                        + "is preserved on the reference/monolith-before branch (tag "
                                        + "v0-monolith); stage/NN-*-extracted tags step through each extraction. "
                                        + "See SMELLS.md for the six deliberate smells planted in this codebase "
                                        + "— all six are now cured."));
    }
}
