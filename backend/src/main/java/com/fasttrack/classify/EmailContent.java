package com.fasttrack.classify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * One email as the classifier sees it. The body is held only for the duration
 * of the call - nothing here is persisted except the hash (docs/privacy.md).
 */
public record EmailContent(
        String senderEmail,
        String senderName,
        String subject,
        String body,
        Instant receivedAt) {

    /** Bodies are truncated before they reach a prompt: the decision is always near the top. */
    public static final int MAX_BODY_CHARS = 4000;

    public String truncatedBody() {
        if (body == null) {
            return "";
        }
        String collapsed = body.replaceAll("[\\t\\x0B\\f\\r]", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return collapsed.length() <= MAX_BODY_CHARS
                ? collapsed
                : collapsed.substring(0, MAX_BODY_CHARS) + "\n[truncated]";
    }

    /**
     * Stable cache key for this email's content. Two identical emails - the
     * same template sent to the same person twice - classify once.
     */
    public String contentHash() {
        return sha256(nullToEmpty(senderEmail) + "\u0000" + nullToEmpty(subject)
                + "\u0000" + nullToEmpty(body));
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
