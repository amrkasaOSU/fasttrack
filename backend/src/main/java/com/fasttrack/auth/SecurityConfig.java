package com.fasttrack.auth;

import com.fasttrack.common.ApiError;
import com.fasttrack.config.AppProperties;
import com.fasttrack.config.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Session-based security.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Opaque session cookie stored in Redis - revocable server-side, which a
 *       JWT would not be (DECISIONS.md D-002).</li>
 *   <li>CSRF tokens in a readable {@code XSRF-TOKEN} cookie, echoed by the SPA
 *       in {@code X-XSRF-TOKEN}. State-changing requests without it are
 *       rejected.</li>
 *   <li>Unauthenticated API calls get {@code 401} with a JSON body, never a
 *       redirect to a login page.</li>
 *   <li>Google login is registered only when credentials exist; otherwise the
 *       app runs in demo / manual mode and says so.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/auth/me",
            "/api/auth/demo",
            "/api/config",
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/oauth2/**",
            "/login/**",
            "/error"
    };

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AppProperties props,
                                           SecurityContextRepository securityContextRepository,
                                           ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                           ObjectProvider<OAuth2AuthorizationRequestResolver> requestResolver,
                                           ObjectProvider<OAuth2LoginSuccessHandler> loginSuccessHandler)
            throws Exception {

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // Null attribute name => the token is resolved eagerly, which the SPA needs.
        csrfHandler.setCsrfRequestAttributeName(null);

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.secure(props.isSecureCookies()).sameSite("Lax").path("/"));

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource(props)))
            .csrf(csrf -> csrf
                    .csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(csrfHandler))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .securityContext(ctx -> ctx.securityContextRepository(securityContextRepository))
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(sf -> sf.changeSessionId()))
            .headers(headers -> {
                headers.frameOptions(frame -> frame.deny());
                headers.contentTypeOptions(Customizer.withDefaults());
                headers.referrerPolicy(r -> r.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                .ReferrerPolicy.SAME_ORIGIN));
                // This service returns JSON and nothing else: no scripts, no
                // styles, no frames, no images. Saying so costs nothing and
                // turns any future HTML-injection bug into a non-event.
                headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
                headers.permissionsPolicyHeader(permissions -> permissions.policy(
                        "camera=(), microphone=(), geolocation=(), payment=(), usb=()"));
                headers.crossOriginOpenerPolicy(coop -> coop.policy(
                        org.springframework.security.web.header.writers
                                .CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN));
                if (props.isSecureCookies()) {
                    // Only meaningful over HTTPS, and harmful on a plain-HTTP
                    // development machine, so it is tied to the same flag.
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31_536_000));
                } else {
                    headers.httpStrictTransportSecurity(hsts -> hsts.disable());
                }
            })
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(PUBLIC_PATHS).permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, authException) ->
                            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                    ApiError.of("unauthenticated", "Sign in to continue.")))
                    .accessDeniedHandler((request, response, denied) ->
                            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                    ApiError.of("forbidden", "You do not have access to that."))))
            .logout(logout -> logout
                    .logoutUrl("/api/auth/logout")
                    .deleteCookies("FASTTRACK_SESSION", "XSRF-TOKEN")
                    .invalidateHttpSession(true)
                    .logoutSuccessHandler((request, response, authentication) ->
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable());

        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        if (registrations != null) {
            OAuth2LoginSuccessHandler successHandler = loginSuccessHandler.getObject();
            http.oauth2Login(oauth -> {
                oauth.successHandler(successHandler);
                oauth.failureHandler((request, response, exception) ->
                        response.sendRedirect(props.getFrontendUrl() + "/?error=google_login_failed"));
                OAuth2AuthorizationRequestResolver resolver = requestResolver.getIfAvailable();
                if (resolver != null) {
                    oauth.authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver));
                }
            });
        }

        // Unauthenticated entry point for non-API paths stays a 401 as well:
        // this service never serves HTML login pages.
        http.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
                request -> !request.getRequestURI().startsWith("/api/")));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(props.getFrontendUrl()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, "X-XSRF-TOKEN"));
        config.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static void writeJson(HttpServletResponse response, int status, ApiError body) {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            response.getWriter().write(JsonMapper.shared().writeValueAsString(body));
        } catch (Exception ignored) {
            // The response is already committed with the right status; nothing more to do.
        }
    }
}
