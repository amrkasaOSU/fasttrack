package com.fasttrack.gmail;

import java.util.List;

/**
 * The slice of Gmail this product uses. Read-only by construction: there is no
 * method here that could send, label, move or delete anything, so no caller
 * can do so by accident.
 */
public interface GmailApi {

    /** The address of the connected mailbox, used to confirm what was connected. */
    String profileEmailAddress(GmailCredentials credentials);

    /** Gmail's current history cursor for the mailbox. */
    String currentHistoryId(GmailCredentials credentials);

    /** One page of message ids matching a Gmail search query. */
    GmailPage listMessageIds(GmailCredentials credentials, String query, int pageSize, String pageToken);

    /** Message ids added since {@code startHistoryId}; empty when nothing changed. */
    List<String> listAddedMessageIdsSince(GmailCredentials credentials, String startHistoryId);

    /** Full message, including the body needed for classification. */
    GmailMessage getMessage(GmailCredentials credentials, String messageId);
}
