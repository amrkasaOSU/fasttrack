package com.fasttrack.gmail;

import com.fasttrack.account.User;
import com.fasttrack.auth.GoogleConfiguredCondition;
import com.fasttrack.auth.GoogleOAuthConfig;
import com.fasttrack.auth.OAuth2LoginSuccessHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Captures the Gmail refresh token at the moment Google hands it over.
 *
 * <p>Google returns a refresh token only on the authorization request that
 * carries {@code access_type=offline} and {@code prompt=consent}, which
 * {@link GoogleOAuthConfig} always sends. If no refresh token comes back -
 * which happens when a user re-authorises without consent being forced - the
 * existing stored token is left alone rather than being wiped.
 *
 * <p>Nothing in this class logs a token.
 *
 * <p>Registered only when Google credentials exist: without them there is no
 * login path to capture a token from, and requiring Spring Security's
 * authorized-client service unconditionally would stop the application
 * starting at all in a deployment that runs without Gmail.
 */
@Component
@Conditional(GoogleConfiguredCondition.class)
public class GmailTokenCapture implements OAuth2LoginSuccessHandler.LoginListener {

    private static final Logger log = LoggerFactory.getLogger(GmailTokenCapture.class);

    private final OAuth2AuthorizedClientService authorizedClients;
    private final GmailAccountService accounts;
    private final TokenEncryptionService tokens;
    private final ApplicationEventPublisher events;

    public GmailTokenCapture(OAuth2AuthorizedClientService authorizedClients,
                             GmailAccountService accounts,
                             TokenEncryptionService tokens,
                             ApplicationEventPublisher events) {
        this.authorizedClients = authorizedClients;
        this.accounts = accounts;
        this.tokens = tokens;
        this.events = events;
    }

    @Override
    public void onGoogleLogin(User user, OAuth2AuthenticationToken authentication) {
        if (!tokens.isConfigured()) {
            log.warn("Google sign-in succeeded but TOKEN_ENCRYPTION_KEY is not set, "
                    + "so no Gmail token was stored. Gmail stays disconnected.");
            return;
        }

        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(
                authentication.getAuthorizedClientRegistrationId(), authentication.getName());

        if (client == null || client.getRefreshToken() == null) {
            log.info("Google sign-in returned no refresh token; keeping any existing Gmail connection");
            return;
        }

        String scopes = String.join(" ", client.getClientRegistration().getScopes());
        accounts.connect(user.getId(), user.getEmail(), scopes, client.getRefreshToken().getTokenValue());
        log.info("Stored an encrypted Gmail refresh token for user {}", user.getId());

        events.publishEvent(new GmailConnectedEvent(user.getId()));
    }

    /** Raised after a mailbox is connected, so the first sync can start at once. */
    public record GmailConnectedEvent(java.util.UUID userId) {}
}
