package com.fasttrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasttrack.account.User;
import com.fasttrack.account.UserService;
import com.fasttrack.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

/** What happens after Google hands back a valid code. */
class OAuth2LoginSuccessHandlerTest {

    private final UserService userService = mock(UserService.class);
    private final SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
    private final AppProperties props = new AppProperties();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private OAuth2AuthenticationToken googleToken(Map<String, Object> attributes) {
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    private User persistedUser() {
        User user = User.googleUser("amr@example.com", "Amr", null, "sub-123");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    @DisplayName("a successful login creates the session and redirects to the dashboard")
    void successCreatesSessionAndRedirects() throws Exception {
        User user = persistedUser();
        when(userService.upsertGoogleUser(anyString(), anyString(), any(), any())).thenReturn(user);
        var handler = new OAuth2LoginSuccessHandler(userService, props, contextRepository, List.of());

        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, googleToken(Map.of(
                "sub", "sub-123", "email", "amr@example.com", "name", "Amr", "picture", "https://p/1")));

        assertThat(response.getRedirectedUrl()).isEqualTo(props.getFrontendUrl() + "/dashboard");
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(AuthenticatedUser.class);
        assertThat(((AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication())
                .getPrincipal().email()).isEqualTo("amr@example.com");
        verify(contextRepository).saveContext(any(), any(), any());
    }

    @Test
    @DisplayName("listeners are notified so the Gmail module can capture the refresh token")
    void listenersAreNotified() throws Exception {
        User user = persistedUser();
        when(userService.upsertGoogleUser(anyString(), anyString(), any(), any())).thenReturn(user);
        List<User> seen = new ArrayList<>();
        var handler = new OAuth2LoginSuccessHandler(userService, props, contextRepository,
                List.of((u, auth) -> seen.add(u)));

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(),
                googleToken(Map.of("sub", "sub-123", "email", "amr@example.com")));

        assertThat(seen).containsExactly(user);
    }

    @Test
    @DisplayName("a listener that blows up does not cost the user their sign-in")
    void listenerFailureDoesNotBreakLogin() throws Exception {
        User user = persistedUser();
        when(userService.upsertGoogleUser(anyString(), anyString(), any(), any())).thenReturn(user);
        var handler = new OAuth2LoginSuccessHandler(userService, props, contextRepository,
                List.of((u, auth) -> { throw new IllegalStateException("token storage down"); }));

        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                googleToken(Map.of("sub", "sub-123", "email", "amr@example.com")));

        assertThat(response.getRedirectedUrl()).endsWith("/dashboard");
    }

    @Test
    @DisplayName("a Google response with no email never creates an account")
    void missingEmailIsRefused() throws Exception {
        var handler = new OAuth2LoginSuccessHandler(userService, props, contextRepository, List.of());
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                googleToken(Map.of("sub", "sub-123")));

        assertThat(response.getRedirectedUrl()).contains("error=missing_profile");
        verify(userService, never()).upsertGoogleUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a non-OAuth2 authentication is rejected rather than trusted")
    void nonOauthAuthenticationIsRejected() throws Exception {
        var handler = new OAuth2LoginSuccessHandler(userService, props, contextRepository, List.of());
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new TestingAuthenticationToken("someone", "creds"));

        assertThat(response.getRedirectedUrl()).contains("error=login_failed");
        verify(userService, never()).upsertGoogleUser(any(), any(), any(), any());
    }
}
