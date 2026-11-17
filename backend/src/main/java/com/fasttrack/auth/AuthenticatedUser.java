package com.fasttrack.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The {@code Authentication} stored in the session for both login paths
 * (Google OAuth2 and demo). Holding one type keeps the authorization code
 * from having to care how the user signed in.
 */
public class AuthenticatedUser extends AbstractAuthenticationToken {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_DEMO = "ROLE_DEMO";

    private final AppUserPrincipal principal;

    public AuthenticatedUser(AppUserPrincipal principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(AppUserPrincipal principal) {
        return principal.demo()
                ? List.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority(ROLE_DEMO))
                : List.of(new SimpleGrantedAuthority(ROLE_USER));
    }

    @Override
    public Object getCredentials() {
        return null; // never stored
    }

    @Override
    public AppUserPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.userId().toString();
    }
}
