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
                                "The six bounded contexts of the reference monolith (order, inventory, "
                                        + "payment, shipping, notification, review) exposed as one Spring MVC REST "
                                        + "surface. See SMELLS.md for the six deliberate smells planted in this "
                                        + "codebase."));
    }
}
