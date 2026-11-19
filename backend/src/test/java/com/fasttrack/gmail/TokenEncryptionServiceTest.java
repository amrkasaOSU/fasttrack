package com.fasttrack.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The refresh token is the most sensitive thing this product stores, so the
 * properties asserted here are the ones that keep it safe: it round-trips,
 * tampering is detected, a different key cannot read it, and with no key
 * configured nothing is stored at all.
 */
class TokenEncryptionServiceTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private TokenEncryptionService service(String key) {
        AppProperties props = new AppProperties();
        props.setTokenEncryptionKey(key);
        return new TokenEncryptionService(props);
    }

    @Test
    @DisplayName("a token round-trips exactly")
    void roundTrip() {
        TokenEncryptionService service = service(randomKey());
        String token = "1//0eXaMpLe-refresh-token_value.with-punctuation";

        var encrypted = service.encrypt(token);

        assertThat(service.decrypt(encrypted.cipherText(), encrypted.nonce())).isEqualTo(token);
    }

    @Test
    @DisplayName("the ciphertext never contains the plaintext")
    void cipherTextLooksNothingLikeTheToken() {
        TokenEncryptionService service = service(randomKey());
        String token = "super-secret-refresh-token";

        var encrypted = service.encrypt(token);

        assertThat(new String(encrypted.cipherText(), StandardCharsets.ISO_8859_1))
                .doesNotContain("secret")
                .doesNotContain(token);
    }

    @Test
    @DisplayName("encrypting the same token twice produces different ciphertext")
    void nonceIsFresh() {
        TokenEncryptionService service = service(randomKey());

        var first = service.encrypt("same-token");
        var second = service.encrypt("same-token");

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.cipherText()).isNotEqualTo(second.cipherText());
        assertThat(service.decrypt(second.cipherText(), second.nonce())).isEqualTo("same-token");
    }

    @Test
    @DisplayName("a tampered ciphertext fails loudly instead of decrypting to rubbish")
    void tamperingIsDetected() {
        TokenEncryptionService service = service(randomKey());
        var encrypted = service.encrypt("token");

        byte[] tampered = encrypted.cipherText().clone();
        tampered[0] ^= 0x01;

        assertThatThrownBy(() -> service.decrypt(tampered, encrypted.nonce()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reconnect Gmail");
    }

    @Test
    @DisplayName("a different key cannot read the token")
    void wrongKeyCannotDecrypt() {
        var encrypted = service(randomKey()).encrypt("token");
        TokenEncryptionService other = service(randomKey());

        assertThatThrownBy(() -> other.decrypt(encrypted.cipherText(), encrypted.nonce()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("with no key configured, encryption is refused rather than skipped")
    void noKeyMeansNoStorage() {
        TokenEncryptionService service = service("");

        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.encrypt("token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOKEN_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("a key of the wrong length is rejected at start-up, with instructions")
    void wrongLengthKeyRejected() {
        AppProperties props = new AppProperties();
        props.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> new TokenEncryptionService(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    @DisplayName("a key that is not base64 is rejected with instructions")
    void nonBase64KeyRejected() {
        AppProperties props = new AppProperties();
        props.setTokenEncryptionKey("this is not base64!!!");

        assertThatThrownBy(() -> new TokenEncryptionService(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    @DisplayName("null inputs are rejected rather than silently encrypting nothing")
    void nullsRejected() {
        TokenEncryptionService service = service(randomKey());

        assertThatThrownBy(() -> service.encrypt(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decrypt(null, new byte[12])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("credentials never print the token, even by accident")
    void credentialsDoNotLeakInToString() {
        var credentials = new GmailCredentials("1//secret-token");

        assertThat(credentials.toString()).doesNotContain("secret-token").contains("***");
    }
}
