package com.fasttrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.account.UserRepository;
import com.fasttrack.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The session layer end to end, against a real PostgreSQL and Redis.
 *
 * <p>These tests deliberately do <em>not</em> use Spring Security's
 * {@code with(csrf())} post-processor: that replaces the CSRF repository on
 * the shared filter chain and would stop exercising the real cookie. Instead
 * each test performs the same handshake a browser does - read the
 * {@code XSRF-TOKEN} cookie, echo it back in {@code X-XSRF-TOKEN}, and carry
 * the session cookie between requests.
 */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository users;

    /** A browser-like cookie jar: last value for a given name wins. */
    private static final class CookieJar {
        private final Map<String, Cookie> cookies = new LinkedHashMap<>();

        void absorb(MvcResult result) {
            for (Cookie cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) {
                    cookies.remove(cookie.getName());
                } else {
                    cookies.put(cookie.getName(), cookie);
                }
            }
        }

        Cookie[] all() {
            return cookies.values().toArray(new Cookie[0]);
        }

        String csrfToken() {
            Cookie cookie = cookies.get("XSRF-TOKEN");
            return cookie == null ? null : cookie.getValue();
        }
    }

    /** Performs the public GET every SPA does on load, and keeps the cookies. */
    private CookieJar openSession() throws Exception {
        CookieJar jar = new CookieJar();
        jar.absorb(mockMvc.perform(get("/api/config")).andExpect(status().isOk()).andReturn());
        return jar;
    }

    @Test
    @DisplayName("the application starts and serves traffic with no Gmail and no LLM credentials")
    void runsWithoutOptionalIntegrations() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gmailConfigured").value(false))
                .andExpect(jsonPath("$.llmConfigured").value(false))
                .andExpect(jsonPath("$.demoEnabled").value(true))
                .andExpect(jsonPath("$.llmModel").doesNotExist());
    }

    @Test
    @DisplayName("/api/auth/me answers 200 with authenticated=false when signed out")
    void meIsPublicAndHonest() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    @DisplayName("an unauthenticated API call gets 401 JSON, never an HTML login redirect")
    void protectedEndpointReturns401Json() throws Exception {
        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    @DisplayName("every response carries a readable XSRF-TOKEN cookie for the SPA")
    void csrfCookieIsIssued() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    @DisplayName("a state-changing request without the CSRF token is rejected")
    void csrfIsEnforced() throws Exception {
        mockMvc.perform(post("/api/auth/demo"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a CSRF cookie without the matching header is still rejected")
    void csrfCookieAloneIsNotEnough() throws Exception {
        CookieJar jar = openSession();

        mockMvc.perform(post("/api/auth/demo").cookie(jar.all()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("demo sign-in creates the demo user and returns a session usable on later requests")
    void demoLoginStartsASession() throws Exception {
        CookieJar jar = openSession();

        MvcResult login = mockMvc.perform(post("/api/auth/demo")
                        .cookie(jar.all())
                        .header("X-XSRF-TOKEN", jar.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.email").value("demo@fasttrack.app"))
                .andReturn();
        jar.absorb(login);

        assertThat(users.findFirstByDemoIsTrue()).isPresent();

        // The session cookie alone is enough to be recognised on the next call.
        mockMvc.perform(get("/api/auth/me").cookie(jar.all()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.demo").value(true));
    }

    @Test
    @DisplayName("repeated demo sign-ins reuse the one demo account instead of creating more")
    void demoLoginIsIdempotent() throws Exception {
        CookieJar first = openSession();
        mockMvc.perform(post("/api/auth/demo").cookie(first.all())
                .header("X-XSRF-TOKEN", first.csrfToken())).andExpect(status().isOk());
        long afterFirst = users.findAll().stream().filter(u -> u.isDemo()).count();

        CookieJar second = openSession();
        mockMvc.perform(post("/api/auth/demo").cookie(second.all())
                .header("X-XSRF-TOKEN", second.csrfToken())).andExpect(status().isOk());
        long afterSecond = users.findAll().stream().filter(u -> u.isDemo()).count();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(afterSecond).isEqualTo(1);
    }

    @Test
    @DisplayName("sign-out destroys the session, so the same cookie is anonymous afterwards")
    void logoutEndsTheSession() throws Exception {
        CookieJar jar = openSession();
        jar.absorb(mockMvc.perform(post("/api/auth/demo")
                        .cookie(jar.all())
                        .header("X-XSRF-TOKEN", jar.csrfToken()))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(jar.all())
                        .header("X-XSRF-TOKEN", jar.csrfToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").cookie(jar.all()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    @DisplayName("Swagger UI and the OpenAPI document are reachable without a session")
    void apiDocsArePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("FastTrack API"));
    }

    @Test
    @DisplayName("the health endpoint is public and reports the database and Redis as up")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
