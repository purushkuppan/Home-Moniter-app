package com.homehub;

import com.homehub.events.VersionController;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API docs (ADR 11): the OpenAPI spec at {@code /v3/api-docs} and Swagger UI at {@code /swagger-ui.html}, for
 * {@code /api/**} only ({@code springdoc.*} in application.properties, where they can be switched off).
 * <p>
 * The household PIN is declared once here as the {@value #PIN} security scheme (header {@code X-HomeHub-Pin}), and
 * only the endpoints that check it say so with {@code @SecurityRequirement(name = ApiDocs.PIN)}. Swagger UI's
 * "Authorize" button then sends it with those requests.
 */
@Configuration
public class ApiDocs {

    /** Name of the security scheme for the household PIN. */
    public static final String PIN = "pin";

    @Bean
    OpenAPI kudilApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kudil API")
                        .version(VersionController.buildVersion())
                        .description("The API behind the Kudil dashboard. Endpoints that change something in the "
                                + "home need the household PIN: press Authorize and type it."))
                .components(new Components().addSecuritySchemes(PIN, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-HomeHub-Pin")
                        .description("The household PIN (homehub.control.pin).")));
    }
}
