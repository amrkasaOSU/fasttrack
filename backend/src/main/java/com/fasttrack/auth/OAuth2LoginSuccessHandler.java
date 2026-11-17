package com.fasttrack.auth;

import com.fasttrack.account.User;
import com.fasttrack.account.UserService;
import com.fasttrack.config.AppProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Runs after Google returns a valid authorization code.
 *
 * <p>Creates or updates the local account, swaps the OAuth2 authentication for
 * the application's own {@link AuthenticatedUser} (so the rest of the codebase
 * has one principal type), and redirects the browser back to the frontend.
 *
 * <p>Listeners are invoked afterwards: the Gmail module uses one to persist the
 * encrypted refresh token. Nothing token-shaped is ever logged here.
 */
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    /** Hook so the Gmail module can capture the refresh token without this class depending on it. */
    public interface LoginListener {
        void onGoogleLogin(User user, OAuth2AuthenticationToken authentication);
    }

    private final UserService users;
    private final AppProperties props;
    private final SecurityContextRepository securityContextRepository;
    private final List<LoginListener> listeners;

    public OAuth2LoginSuccessHandler(UserService users,
                                     AppProperties props,
                                     SecurityContextRepository securityContextRepository,
                                     List<LoginListener> listeners) {
        this.users = users;
        this.props = props;
        this.securityContextRepository = securityContextRepository;
        this.listeners = listeners;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            response.sendRedirect(props.getFrontendUrl() + "/?error=login_failed");
            return;
        }

        OAuth2User oauthUser = oauthToken.getPrincipal();
        String subject = stringAttr(oauthUser, "sub");
        String email = stringAttr(oauthUser, "email");
        String name = stringAttr(oauthUser, "name");
        String picture = stringAttr(oauthUser, "picture");

        if (subject == null || email == null) {
            log.warn("Google login returned no subject or email; refusing to create an account");
            response.sendRedirect(props.getFrontendUrl() + "/?error=missing_profile");
            return;
        }

        User user = users.upsertGoogleUser(subject, email, name, picture);

        for (LoginListener listener : listeners) {
            try {
                listener.onGoogleLogin(user, oauthToken);
            } catch (RuntimeException ex) {
                // A listener failure must not cost the user their sign-in.
                log.error("Login listener {} failed", listener.getClass().getSimpleName(), ex);
            }
        }

        AuthenticatedUser appAuth = new AuthenticatedUser(AppUserPrincipal.from(user));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(appAuth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        response.sendRedirect(props.getFrontendUrl() + "/dashboard");
    }

    private String stringAttr(OAuth2User user, String name) {
        Object value = user.getAttributes().get(name);
        return value == null ? null : value.toString();
    }
}
