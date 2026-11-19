package com.fasttrack.gmail;

import java.util.List;

/**
 * A page of message ids.
 *
 * @param nextPageToken null when this is the last page
 */
public record GmailPage(List<String> messageIds, String nextPageToken) {
}
