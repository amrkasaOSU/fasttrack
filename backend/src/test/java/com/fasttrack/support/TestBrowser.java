package com.fasttrack.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.auth.AuthenticatedUser;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A browser-shaped test client: it keeps cookies, echoes the CSRF token the
 * way the SPA does, and acts as a specific signed-in user.
 *
 * <p>Using the real CSRF cookie (rather than Spring's {@code with(csrf())}
 * post-processor, which swaps the token repository on the shared filter chain)
 * keeps these tests exercising the production configuration.
 */
public final class TestBrowser {

    private final MockMvc mockMvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();
    private AppUserPrincipal identity;

    public TestBrowser(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** Act as this user for every subsequent request. */
    public TestBrowser as(AppUserPrincipal principal) {
        this.identity = principal;
        return this;
    }

    public ResultActions get(String path) throws Exception {
        return send(MockMvcRequestBuilders.get(path));
    }

    public ResultActions post(String path, String jsonBody) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(path);
        if (jsonBody != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        return send(builder);
    }

    public ResultActions put(String path, String jsonBody) throws Exception {
        return send(MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody == null ? "{}" : jsonBody));
    }

    public ResultActions delete(String path) throws Exception {
        return send(MockMvcRequestBuilders.delete(path));
    }

    public ResultActions upload(String path, String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
        return send(MockMvcRequestBuilders.multipart(path).file(file));
    }

    /** Sends a request without the CSRF header, to prove protection is on. */
    public ResultActions postWithoutCsrf(String path, String jsonBody) throws Exception {
        primeCsrf();
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody == null ? "{}" : jsonBody);
        Cookie[] jar = cookieArray();
        if (jar.length > 0) {
            builder.cookie(jar);
        }
        if (identity != null) {
            builder.with(authentication(new AuthenticatedUser(identity)));
        }
        return mockMvc.perform(builder);
    }

    private ResultActions send(MockHttpServletRequestBuilder builder) throws Exception {
        primeCsrf();
        Cookie[] jar = cookieArray();
        if (jar.length > 0) {
            builder.cookie(jar);
        }
        String token = csrfToken();
        if (token != null) {
            builder.header("X-XSRF-TOKEN", token);
        }
        if (identity != null) {
            builder.with(authentication(new AuthenticatedUser(identity)));
        }
        ResultActions actions = mockMvc.perform(builder);
        absorb(actions.andReturn());
        return actions;
    }

    private void primeCsrf() throws Exception {
        if (csrfToken() == null) {
            absorb(mockMvc.perform(MockMvcRequestBuilders.get("/api/config")).andReturn());
        }
    }

    private void absorb(MvcResult result) {
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie);
            }
        }
    }

    private Cookie[] cookieArray() {
        return cookies.values().toArray(new Cookie[0]);
    }

    private String csrfToken() {
        Cookie cookie = cookies.get("XSRF-TOKEN");
        return cookie == null ? null : cookie.getValue();
    }
}
