package com.fasttrack.gmail;

/** How a sync ended. */
public enum SyncStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    /** Nothing to do: no mailbox connected, or already running. */
    SKIPPED
}
