package com.fasttrack.auth;

import com.fasttrack.config.AppProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Google sign-in, registered only when a client id and secret are present.
 *
 * <p>Scopes are exactly what the product needs and nothing more:
 * {@code openid email profile} for identity and
 * {@code gmail.readonly} to read messages. There is no scope here that can
 * send, label, modify or delete mail.
 */
@Configuration
@Conditional(GoogleConfiguredCondition.class)
public class GoogleOAuthConfig {

    public static final String REGISTRATION_ID = "google";
    public static final String GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(AppProperties props) {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE
                .getBuilder(REGISTRATION_ID)
                .clientId(props.getGoogle().getClientId())
                .clientSecret(props.getGoogle().getClientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("openid", "email", "profile", GMAIL_READONLY_SCOPE)
                .build();
        return new InMemoryClientRegistrationRepository(List.of(google));
    }

    /**
     * Google only returns a refresh token when the authorization request asks
     * for offline access and forces the consent screen. Without this, a second
     * sign-in would return no refresh token and background sync would break.
     */
    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository repo) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");

        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return customize(delegate.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                return customize(delegate.resolve(request, clientRegistrationId));
            }

            private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest req) {
                if (req == null) {
                    return null;
                }
                return OAuth2AuthorizationRequest.from(req)
                        .additionalParameters(params -> {
                            params.put("access_type", "offline");
                            params.put("prompt", "consent");
                            params.put("include_granted_scopes", "true");
                        })
                        .build();
            }
        };
    }
}
