package com.fasttrack.gmail;

/** What started a sync. */
public enum SyncTrigger {
    /** The user pressed "Sync now". */
    MANUAL,
    /** The periodic job. */
    SCHEDULED,
    /** The first sync after connecting a mailbox. */
    INITIAL
}
