package com.fasttrack.gmail;

import java.time.Instant;

/**
 * One fetched message, flattened to what the classifier needs.
 *
 * <p>{@code body} is held in memory for the duration of processing and is
 * never persisted (docs/privacy.md).
 */
public record GmailMessage(
        String id,
        String threadId,
        String fromAddress,
        String fromName,
        String subject,
        String snippet,
        String body,
        Instant receivedAt) {
}
