package com.fasttrack.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The feature-availability contract. The frontend renders "not configured"
 * states from exactly these booleans, so each combination is pinned down here
 * without needing a Spring context.
 */
class ConfigControllerTest {

    private AppProperties propsWith(String googleId, String googleSecret, String tokenKey, String llmKey) {
        AppProperties props = new AppProperties();
        props.getGoogle().setClientId(googleId);
        props.getGoogle().setClientSecret(googleSecret);
        props.setTokenEncryptionKey(tokenKey);
        props.getLlm().setApiKey(llmKey);
        return props;
    }

    @Test
    @DisplayName("nothing configured: every optional feature reports false, no model name leaks")
    void emptyDeployment() {
        var response = new ConfigController(propsWith("", "", "", "")).get();

        assertThat(response.gmailConfigured()).isFalse();
        assertThat(response.llmConfigured()).isFalse();
        assertThat(response.tokenEncryptionConfigured()).isFalse();
        assertThat(response.llmModel()).isNull();
        assertThat(response.demoEnabled()).isTrue();
    }

    @Test
    @DisplayName("Gmail counts as configured only when OAuth AND the encryption key are present")
    void gmailNeedsEncryptionKeyToo() {
        assertThat(new ConfigController(propsWith("id", "secret", "", "")).get().gmailConfigured())
                .as("OAuth credentials without a token encryption key must not enable Gmail")
                .isFalse();

        assertThat(new ConfigController(propsWith("id", "secret", "a-key", "")).get().gmailConfigured())
                .isTrue();
    }

    @Test
    @DisplayName("the model name is exposed only when an LLM key is actually set")
    void modelNameOnlyWhenConfigured() {
        var configured = new ConfigController(propsWith("", "", "", "sk-test")).get();

        assertThat(configured.llmConfigured()).isTrue();
        assertThat(configured.llmModel()).isEqualTo("llama-3.3-70b-versatile");
    }

    @Test
    @DisplayName("a blank client id does not count as configured")
    void blankValuesAreNotConfiguration() {
        assertThat(propsWith("   ", "secret", "k", "k").getGoogle().isConfigured()).isFalse();
        assertThat(propsWith("id", "   ", "k", "k").getGoogle().isConfigured()).isFalse();
    }
}
