package com.fasttrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.TestBrowser;
import com.fasttrack.support.UserFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The properties a security review would look for, asserted rather than
 * assumed: response headers, what an anonymous caller can reach, what a
 * signed-in caller cannot do without a CSRF token, and whether error
 * responses leak anything.
 */
class SecurityHardeningIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserFixtures fixtures;

    @Test
    @DisplayName("every response carries the hardening headers")
    void securityHeaders() throws Exception {
        new TestBrowser(mockMvc).get("/api/config")
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Permissions-Policy",
                        org.hamcrest.Matchers.containsString("camera=()")));
    }

    @Test
    @DisplayName("HSTS is off on plain HTTP and would be on behind TLS")
    void hstsFollowsTheSecureCookieFlag() throws Exception {
        // The test profile runs with app.secure-cookies=false.
        new TestBrowser(mockMvc).get("/api/config")
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @ParameterizedTest(name = "{0} requires a session")
    @ValueSource(strings = {
            "/api/applications",
            "/api/applications/needs-review",
            "/api/insights",
            "/api/review",
            "/api/review/accuracy",
            "/api/resumes",
            "/api/fit",
            "/api/settings"})
    void protectedEndpointsRequireASession(String path) throws Exception {
        new TestBrowser(mockMvc).get(path)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @ParameterizedTest(name = "{0} is public")
    @ValueSource(strings = {"/api/config", "/api/auth/me", "/actuator/health", "/v3/api-docs"})
    void publicEndpointsStayPublic(String path) throws Exception {
        new TestBrowser(mockMvc).get(path).andExpect(status().isOk());
    }

    @Test
    @DisplayName("actuator exposes only health - no env, beans, mappings or heap dump")
    void actuatorIsMinimal() throws Exception {
        var browser = new TestBrowser(mockMvc);

        browser.get("/actuator/health").andExpect(status().isOk());
        browser.get("/actuator/env").andExpect(status().isUnauthorized());
        browser.get("/actuator/beans").andExpect(status().isUnauthorized());
        browser.get("/actuator/heapdump").andExpect(status().isUnauthorized());
        browser.get("/actuator/configprops").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the health endpoint does not reveal component details")
    void healthHidesInternals() throws Exception {
        new TestBrowser(mockMvc).get("/actuator/health")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("a state-changing request without the CSRF token is refused for every verb")
    void csrfCoversEveryMutatingVerb() throws Exception {
        AppUserPrincipal user = fixtures.newPrincipal("csrf");
        var browser = new TestBrowser(mockMvc).as(user);

        browser.postWithoutCsrf("/api/applications",
                        "{\"companyName\":\"Acme\",\"positionTitle\":\"SWE Intern\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unexpected failure returns a generic message, never a stack trace")
    void errorsDoNotLeakInternals() throws Exception {
        AppUserPrincipal user = fixtures.newPrincipal("errors");

        new TestBrowser(mockMvc).as(user)
                .get("/api/applications/not-a-uuid")
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("com.fasttrack")
                            .doesNotContain("org.springframework")
                            .doesNotContain("SQL")
                            .doesNotContain("Exception");
                });
    }

    @Test
    @DisplayName("the session cookie is HttpOnly and SameSite=Lax")
    void sessionCookieIsHardened() throws Exception {
        var result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/config"))
                .andReturn();

        var csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        // The CSRF cookie must be readable by the SPA; the session cookie must not.
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    @DisplayName("CORS allows the configured frontend and nothing else")
    void corsIsRestricted() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/applications")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/applications")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    @DisplayName("an oversized or wrong-typed upload is refused")
    void uploadsAreValidated() throws Exception {
        AppUserPrincipal user = fixtures.newPrincipal("uploads");
        var browser = new TestBrowser(mockMvc).as(user);

        browser.upload("/api/resumes", "resume.txt", "text/plain", "not a pdf".getBytes())
                .andExpect(status().isBadRequest());
        browser.upload("/api/applications/import", "data.exe", "application/octet-stream",
                        "nope".getBytes())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a client-supplied user id in the body is ignored")
    void bodyCannotChooseTheUser() throws Exception {
        AppUserPrincipal alice = fixtures.newPrincipal("alice-sec");
        AppUserPrincipal bob = fixtures.newPrincipal("bob-sec");

        new TestBrowser(mockMvc).as(bob).post("/api/applications", """
                {"companyName":"Acme","positionTitle":"SWE Intern","userId":"%s","id":"%s"}
                """.formatted(alice.userId(), java.util.UUID.randomUUID()))
                .andExpect(status().isCreated());

        new TestBrowser(mockMvc).as(alice).get("/api/applications")
                .andExpect(jsonPath("$.totalItems").value(0));
        new TestBrowser(mockMvc).as(bob).get("/api/applications")
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    @DisplayName("validation rejects oversized field values rather than storing them")
    void inputLengthsAreBounded() throws Exception {
        AppUserPrincipal user = fixtures.newPrincipal("validation");

        new TestBrowser(mockMvc).as(user).post("/api/applications", """
                {"companyName":"%s","positionTitle":"SWE Intern"}
                """.formatted("x".repeat(500)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }
}
