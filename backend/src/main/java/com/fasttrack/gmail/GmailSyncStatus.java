package com.fasttrack.gmail;

/** Where a connected mailbox stands. Mirrors the check constraint on gmail_accounts. */
public enum GmailSyncStatus {
    IDLE,
    RUNNING,
    ERROR,
    REVOKED
}
