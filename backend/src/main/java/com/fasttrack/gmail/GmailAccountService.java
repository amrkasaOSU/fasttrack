package com.fasttrack.gmail;

import com.fasttrack.common.NotConfiguredException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the lifecycle of a connected mailbox: storing the encrypted token,
 * recording sync state, and forgetting the token on disconnect.
 *
 * <p>Separate from {@link GmailSyncService} because these are short
 * transactions that must commit independently of a long-running sync - and
 * because a {@code @Transactional} method called from inside the same bean
 * would not be transactional at all.
 */
@Service
public class GmailAccountService {

    private final GmailAccountRepository accounts;
    private final TokenEncryptionService tokens;

    public GmailAccountService(GmailAccountRepository accounts, TokenEncryptionService tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public Optional<GmailAccount> findByUserId(UUID userId) {
        return accounts.findByUserId(userId);
    }

    /**
     * Stores (or replaces) the refresh token for a user's mailbox.
     *
     * @throws NotConfiguredException when no encryption key is set - a token
     *         is never stored in plaintext as a fallback
     */
    @Transactional
    public GmailAccount connect(UUID userId, String emailAddress, String scopes, String refreshToken) {
        if (!tokens.isConfigured()) {
            throw new NotConfiguredException("gmail",
                    "This deployment has no TOKEN_ENCRYPTION_KEY, so Gmail cannot be connected.");
        }
        GmailAccount account = accounts.findByUserId(userId)
                .orElseGet(() -> new GmailAccount(userId, emailAddress, scopes));
        account.setEmailAddress(emailAddress);
        account.setScopes(scopes);

        TokenEncryptionService.Encrypted encrypted = tokens.encrypt(refreshToken);
        account.storeToken(encrypted.cipherText(), encrypted.nonce());

        return accounts.save(account);
    }

    /** Forgets the token. History already built from the mailbox is kept. */
    @Transactional
    public void disconnect(UUID userId) {
        accounts.findByUserId(userId).ifPresent(GmailAccount::revoke);
    }

    /** Removes the record entirely - used by "Delete all my data". */
    @Transactional
    public void deleteForUser(UUID userId) {
        accounts.findByUserId(userId).ifPresent(accounts::delete);
    }

    @Transactional
    public void markRunning(UUID accountId) {
        accounts.findById(accountId).ifPresent(GmailAccount::syncStarted);
    }

    @Transactional
    public void markSucceeded(UUID accountId, String historyId) {
        accounts.findById(accountId).ifPresent(a -> a.syncSucceeded(historyId, Instant.now()));
    }

    @Transactional
    public void markFailed(UUID accountId, String message, boolean authFailure) {
        accounts.findById(accountId).ifPresent(account -> {
            if (authFailure) {
                account.revoke();
            }
            account.syncFailed(message);
        });
    }

    /** The decrypted token for one sync. Never stored or logged by the caller. */
    @Transactional(readOnly = true)
    public GmailCredentials credentialsFor(GmailAccount account) {
        return new GmailCredentials(
                tokens.decrypt(account.getRefreshTokenCipher(), account.getRefreshTokenIv()));
    }
}
