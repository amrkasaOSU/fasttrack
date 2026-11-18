package com.fasttrack.application;

/** The kinds of entry that can appear in an application's history. */
public enum ApplicationEventType {
    CREATED,
    STATUS_CHANGED,
    EMAIL_LINKED,
    NOTE_ADDED,
    MERGED,
    SPLIT,
    EDITED,
    FLAGGED_FOR_REVIEW,
    REVIEW_RESOLVED
}
