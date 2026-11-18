package com.fasttrack.llm;

/**
 * The provider could not be reached, refused the request, or is not
 * configured. Callers are expected to fall back to a deterministic path rather
 * than surface this to the user.
 */
public class LlmUnavailableException extends RuntimeException {

    private final boolean retryable;

    public LlmUnavailableException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmUnavailableException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
