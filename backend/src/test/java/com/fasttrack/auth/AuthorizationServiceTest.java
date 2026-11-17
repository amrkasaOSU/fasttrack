package com.fasttrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.common.ForbiddenException;
import com.fasttrack.common.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The ownership rules every endpoint depends on. If these break, one user can
 * read another's data, so they are asserted directly rather than only through
 * the HTTP layer.
 */
class AuthorizationServiceTest {

    private final AuthorizationService authorization = new AuthorizationService();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AppUserPrincipal signIn(boolean demo) {
        AppUserPrincipal principal = new AppUserPrincipal(
                UUID.randomUUID(), "a@example.com", "A", null, demo, false);
        SecurityContextHolder.getContext().setAuthentication(new AuthenticatedUser(principal));
        return principal;
    }

    @Test
    @DisplayName("anonymous request has no principal and cannot be forced to produce one")
    void anonymousHasNoPrincipal() {
        assertThat(authorization.currentPrincipalOrNull()).isNull();
        assertThatThrownBy(authorization::requirePrincipal).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(authorization::requireUserId).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("an anonymous Spring authentication is still treated as signed out")
    void springAnonymousTokenIsNotAUser() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(authorization.currentPrincipalOrNull()).isNull();
    }

    @Test
    @DisplayName("the signed-in user owns their own resources")
    void ownerPasses() {
        AppUserPrincipal principal = signIn(false);
        assertThatCode(() -> authorization.requireOwner(principal.userId(), "Application"))
                .doesNotThrowAnyException();
        assertThat(authorization.requireUserId()).isEqualTo(principal.userId());
    }

    @Test
    @DisplayName("another user's resource is reported as not found, never as forbidden")
    void foreignResourceLooksMissing() {
        signIn(false);
        UUID someoneElse = UUID.randomUUID();

        assertThatThrownBy(() -> authorization.requireOwner(someoneElse, "Application"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Application not found");
    }

    @Test
    @DisplayName("a null owner id is refused rather than silently allowed")
    void nullOwnerIsRefused() {
        signIn(false);
        assertThatThrownBy(() -> authorization.requireOwner(null, "Resume"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("the demo account is blocked from account-level destructive actions")
    void demoCannotDestroy() {
        signIn(true);
        assertThatThrownBy(() -> authorization.requireNotDemo("delete all data"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("delete all data");
    }

    @Test
    @DisplayName("a normal account is not blocked by the demo guard")
    void normalUserPassesDemoGuard() {
        signIn(false);
        assertThatCode(() -> authorization.requireNotDemo("delete all data"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("demo sessions carry an extra authority so method security can use it")
    void demoGetsDemoAuthority() {
        AppUserPrincipal demo = new AppUserPrincipal(
                UUID.randomUUID(), "demo@fasttrack.app", "Demo", null, true, false);

        assertThat(new AuthenticatedUser(demo).getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(AuthenticatedUser.ROLE_USER, AuthenticatedUser.ROLE_DEMO);
        assertThat(new AuthenticatedUser(demo).getCredentials()).isNull();
    }
}
