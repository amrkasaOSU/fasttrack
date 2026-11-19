package com.fasttrack.gmail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A connected mailbox. One per user.
 *
 * <p>Only the refresh token is stored, and only as ciphertext plus its nonce.
 * Access tokens are short-lived and are never persisted.
 */
@Entity
@Table(name = "gmail_accounts")
public class GmailAccount {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "email_address", nullable = false)
    private String emailAddress;

    @Column(name = "refresh_token_cipher")
    private byte[] refreshTokenCipher;

    @Column(name = "refresh_token_iv")
    private byte[] refreshTokenIv;

    @Column(name = "scopes", nullable = false)
    private String scopes;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt = Instant.now();

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    /** Gmail's cursor for incremental sync. Null until the first full sync finishes. */
    @Column(name = "last_history_id")
    private String lastHistoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private GmailSyncStatus syncStatus = GmailSyncStatus.IDLE;

    @Column(name = "sync_error")
    private String syncError;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected GmailAccount() {
        // for JPA
    }

    public GmailAccount(UUID userId, String emailAddress, String scopes) {
        this.userId = userId;
        this.emailAddress = emailAddress;
        this.scopes = scopes;
    }

    public void storeToken(byte[] cipher, byte[] nonce) {
        this.refreshTokenCipher = cipher;
        this.refreshTokenIv = nonce;
        this.revokedAt = null;
        this.syncStatus = GmailSyncStatus.IDLE;
        this.syncError = null;
        this.connectedAt = Instant.now();
    }

    /** Forgets the token. The row stays so the UI can show "reconnect". */
    public void revoke() {
        this.refreshTokenCipher = null;
        this.refreshTokenIv = null;
        this.revokedAt = Instant.now();
        this.syncStatus = GmailSyncStatus.REVOKED;
        this.lastHistoryId = null;
    }

    public boolean hasToken() {
        return refreshTokenCipher != null && refreshTokenIv != null;
    }

    public void syncStarted() {
        this.syncStatus = GmailSyncStatus.RUNNING;
        this.syncError = null;
    }

    public void syncSucceeded(String historyId, Instant when) {
        this.syncStatus = GmailSyncStatus.IDLE;
        this.syncError = null;
        this.lastSyncAt = when;
        if (historyId != null && !historyId.isBlank()) {
            this.lastHistoryId = historyId;
        }
    }

    public void syncFailed(String message) {
        // REVOKED is a stronger state than ERROR: a revoked grant needs the
        // user to reconnect, and a generic failure must not mask that.
        if (this.syncStatus != GmailSyncStatus.REVOKED) {
            this.syncStatus = GmailSyncStatus.ERROR;
        }
        // Never store a provider message verbatim; it can contain a token.
        this.syncError = message == null ? "Sync failed" : message;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }
    public byte[] getRefreshTokenCipher() { return refreshTokenCipher; }
    public byte[] getRefreshTokenIv() { return refreshTokenIv; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Instant getConnectedAt() { return connectedAt; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public String getLastHistoryId() { return lastHistoryId; }
    public void setLastHistoryId(String lastHistoryId) { this.lastHistoryId = lastHistoryId; }
    public GmailSyncStatus getSyncStatus() { return syncStatus; }
    public String getSyncError() { return syncError; }
    public Instant getRevokedAt() { return revokedAt; }
}
