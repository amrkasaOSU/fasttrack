package com.fasttrack.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html, OpenAPI JSON at /v3/api-docs. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fastTrackOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FastTrack API")
                .version("0.1.0")
                .description("""
                        Internship application tracker. Reads job-application email \
                        (Gmail, read-only), tracks each application's status, and scores \
                        resume fit against a job posting.

                        Authentication is a session cookie. Sign in with Google at \
                        /oauth2/authorization/google, or start a demo session with \
                        POST /api/auth/demo. State-changing requests require the \
                        X-XSRF-TOKEN header.""")
                .license(new License().name("MIT")));
    }
}
