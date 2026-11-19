package com.fasttrack.gmail;

/**
 * What one Gmail call needs. Constructed per sync from the decrypted refresh
 * token and discarded afterwards - never stored, never logged.
 */
public record GmailCredentials(String refreshToken) {

    @Override
    public String toString() {
        // Guards against a token reaching a log line through string interpolation.
        return "GmailCredentials[refreshToken=***]";
    }
}
