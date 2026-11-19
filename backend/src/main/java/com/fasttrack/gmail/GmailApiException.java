package com.fasttrack.gmail;

/**
 * A Gmail call failed. {@code retryable} separates "try again in a moment"
 * (429, 5xx, network) from "this will never work" (401 after a revoked grant,
 * 403 insufficient scope).
 */
public class GmailApiException extends RuntimeException {

    private final boolean retryable;
    private final boolean authFailure;

    public GmailApiException(String message, Throwable cause, boolean retryable, boolean authFailure) {
        super(message, cause);
        this.retryable = retryable;
        this.authFailure = authFailure;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** True when the stored grant is no longer usable and the user must reconnect. */
    public boolean isAuthFailure() {
        return authFailure;
    }
}
