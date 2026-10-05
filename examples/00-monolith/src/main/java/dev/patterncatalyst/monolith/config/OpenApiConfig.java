package dev.patterncatalyst.monolith.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc OpenAPI surface. The generated spec seeds the future Newman
 * behavior-equivalence suite (S6) — every REST context below is documented here.
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
                                "The four bounded contexts the reference monolith still fully owns (order, "
                                        + "inventory, payment, shipping) exposed as one Spring MVC REST surface. "
                                        + "Review was extracted and decommissioned in r02/S10 — it is served by "
                                        + "examples/02-review-service behind the strangler proxy's "
                                        + "strangler.review.enabled flag. Notification was likewise extracted and "
                                        + "decommissioned in r04/S8 — it is served by "
                                        + "examples/03-notification-service behind the strangler proxy's "
                                        + "strangler.notification.enabled flag; checkout now only writes a "
                                        + "transactional outbox row. See SMELLS.md for the six deliberate smells "
                                        + "planted in this codebase (smells #4 and #6 are now cured)."));
    }
}
