package com.fasttrack.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * The Gmail scope is the privacy promise of this product, so it is asserted
 * here rather than trusted: the registration must request read-only access and
 * nothing that can send, modify or delete mail.
 */
class GoogleOAuthConfigTest {

    private final GoogleOAuthConfig config = new GoogleOAuthConfig();

    private AppProperties props() {
        AppProperties props = new AppProperties();
        props.getGoogle().setClientId("test-client-id");
        props.getGoogle().setClientSecret("test-client-secret");
        return props;
    }

    @Test
    @DisplayName("only read-only Gmail access is requested")
    void requestsReadOnlyGmailScopeOnly() {
        ClientRegistration google = config.clientRegistrationRepository(props())
                .findByRegistrationId("google");

        assertThat(google.getScopes())
                .containsExactlyInAnyOrder("openid", "email", "profile",
                        "https://www.googleapis.com/auth/gmail.readonly");
        assertThat(google.getScopes())
                .as("no scope may allow sending, modifying or deleting mail")
                .noneMatch(scope -> scope.contains("gmail.send")
                        || scope.contains("gmail.modify")
                        || scope.contains("gmail.compose")
                        || scope.contains("mail.google.com"));
    }

    @Test
    @DisplayName("the client id and secret come from configuration, not from code")
    void credentialsComeFromProperties() {
        ClientRegistration google = config.clientRegistrationRepository(props())
                .findByRegistrationId("google");

        assertThat(google.getClientId()).isEqualTo("test-client-id");
        assertThat(google.getClientSecret()).isEqualTo("test-client-secret");
        assertThat(google.getRedirectUri()).isEqualTo("{baseUrl}/{action}/oauth2/code/{registrationId}");
    }

    @Test
    @DisplayName("the authorization request asks for offline access so a refresh token is returned")
    void authorizationRequestAsksForOfflineAccess() {
        ClientRegistrationRepository repo = config.clientRegistrationRepository(props());
        OAuth2AuthorizationRequestResolver resolver = config.authorizationRequestResolver(repo);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setServletPath("/oauth2/authorization/google");

        OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request);

        assertThat(authorizationRequest).isNotNull();
        assertThat(authorizationRequest.getAdditionalParameters())
                .containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent");
    }

    @Test
    @DisplayName("a request that is not an authorization request resolves to null, not an exception")
    void unrelatedRequestResolvesToNull() {
        ClientRegistrationRepository repo = config.clientRegistrationRepository(props());
        OAuth2AuthorizationRequestResolver resolver = config.authorizationRequestResolver(repo);

        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/config");

        assertThat(resolver.resolve(request)).isNull();
        assertThat(resolver.resolve(request, "google")).isNotNull();
    }

    @Test
    @DisplayName("Google sign-in is registered only when both credentials are present")
    void conditionRequiresBothValues() {
        GoogleConfiguredCondition condition = new GoogleConfiguredCondition();

        assertThat(matches(condition, "", "")).isFalse();
        assertThat(matches(condition, "id", "")).isFalse();
        assertThat(matches(condition, "", "secret")).isFalse();
        assertThat(matches(condition, "   ", "secret")).isFalse();
        assertThat(matches(condition, "id", "secret")).isTrue();
    }

    private boolean matches(GoogleConfiguredCondition condition, String id, String secret) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.google.client-id", id);
        environment.setProperty("app.google.client-secret", secret);

        org.springframework.context.annotation.ConditionContext context =
                new org.springframework.context.annotation.ConditionContext() {
                    @Override
                    public org.springframework.beans.factory.support.BeanDefinitionRegistry getRegistry() {
                        return new org.springframework.beans.factory.support.DefaultListableBeanFactory();
                    }

                    @Override
                    public org.springframework.beans.factory.config.ConfigurableListableBeanFactory getBeanFactory() {
                        return new org.springframework.beans.factory.support.DefaultListableBeanFactory();
                    }

                    @Override
                    public org.springframework.core.env.Environment getEnvironment() {
                        return environment;
                    }

                    @Override
                    public org.springframework.core.io.ResourceLoader getResourceLoader() {
                        return new org.springframework.core.io.DefaultResourceLoader();
                    }

                    @Override
                    public ClassLoader getClassLoader() {
                        return getClass().getClassLoader();
                    }
                };

        return condition.matches(context, new org.springframework.core.type.StandardAnnotationMetadata(Object.class));
    }
}
