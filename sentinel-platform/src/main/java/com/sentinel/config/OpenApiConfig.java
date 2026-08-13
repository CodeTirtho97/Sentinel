package com.sentinel.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI so the API is explorable without Postman.
 *
 * <p>The API key is declared as a security scheme rather than left implicit, so the "Authorize"
 * button in Swagger UI actually works — an interactive doc that 401s on every call is worse than
 * no doc.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKey";

    @Bean
    OpenAPI sentinelOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sentinel")
                        .version("v1")
                        .description(
                                """
                                SLO evaluation, dependency-aware incident correlation, and drafted root-cause \
                                hypotheses.

                                Every endpoint under /api/v1 requires an `X-Api-Key` header. The Compose stack \
                                ships with `local-dev-key`; press Authorize and paste it.
                                """)
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(
                                API_KEY_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(ApiKeyFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
