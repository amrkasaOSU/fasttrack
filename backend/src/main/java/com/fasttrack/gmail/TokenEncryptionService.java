package com.fasttrack.gmail;

import com.fasttrack.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Encrypts the Gmail refresh token before it reaches the database.
 *
 * <p>AES-256-GCM with a 96-bit nonce generated per encryption. GCM is
 * authenticated, so a tampered ciphertext fails to decrypt rather than
 * producing garbage that might be sent to Google.
 *
 * <p>Rules this class enforces:
 * <ul>
 *   <li>The key comes from {@code TOKEN_ENCRYPTION_KEY} and lives only in the
 *       process environment. It is never written anywhere.</li>
 *   <li>If the key is missing or not 32 bytes, encryption is refused. The
 *       application still starts and reports Gmail as unconfigured - a token
 *       is never stored in plaintext as a fallback.</li>
 *   <li>Nothing here logs a token, a key, or a ciphertext.</li>
 * </ul>
 */
@Service
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public TokenEncryptionService(AppProperties props) {
        this.key = parseKey(props.getTokenEncryptionKey());
    }

    private SecretKeySpec parseKey(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must be base64. Generate one with: openssl rand -base64 32");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256); got " + decoded.length
                    + ". Generate one with: openssl rand -base64 32");
        }
        return new SecretKeySpec(decoded, ALGORITHM);
    }

    /** False when no usable key is configured; callers must not store tokens. */
    public boolean isConfigured() {
        return key != null;
    }

    /** Ciphertext and the nonce it was produced with. Stored in separate columns. */
    public record Encrypted(byte[] cipherText, byte[] nonce) {}

    public Encrypted encrypt(String plaintext) {
        requireKey();
        if (plaintext == null) {
            throw new IllegalArgumentException("Nothing to encrypt");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return new Encrypted(cipherText, nonce);
        } catch (GeneralSecurityException ex) {
            // Deliberately opaque: the message must never echo any input.
            throw new IllegalStateException("Token encryption failed");
        }
    }

    public String decrypt(byte[] cipherText, byte[] nonce) {
        requireKey();
        if (cipherText == null || nonce == null) {
            throw new IllegalArgumentException("Nothing to decrypt");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "Stored token could not be decrypted. The encryption key has changed, "
                    + "or the stored value was tampered with. Reconnect Gmail to store a new token.");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY is not set, so tokens cannot be stored. "
                    + "Generate one with: openssl rand -base64 32");
        }
    }
}
