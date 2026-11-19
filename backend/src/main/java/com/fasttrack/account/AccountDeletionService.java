package com.fasttrack.account;

import com.fasttrack.gmail.GmailAccount;
import com.fasttrack.gmail.GmailAccountService;
import com.fasttrack.gmail.GoogleTokenRevoker;
import com.fasttrack.gmail.TokenEncryptionService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Delete all my data", for real.
 *
 * <p>Order matters: the Google grant is revoked first, while the token can
 * still be read, and only then is the user row deleted. Every user-owned
 * table has {@code ON DELETE CASCADE} on {@code user_id}, so one delete takes
 * applications, events, emails, classifications, corrections, resumes,
 * postings, fit checks and sync history with it.
 *
 * <p>A failure to reach Google does not block the deletion - the user asked
 * for their data to be gone.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository users;
    private final GmailAccountService gmailAccounts;
    private final TokenEncryptionService tokens;
    private final GoogleTokenRevoker revoker;

    public AccountDeletionService(UserRepository users,
                                  GmailAccountService gmailAccounts,
                                  TokenEncryptionService tokens,
                                  GoogleTokenRevoker revoker) {
        this.users = users;
        this.gmailAccounts = gmailAccounts;
        this.tokens = tokens;
        this.revoker = revoker;
    }

    /** What was actually done, so the UI can tell the user plainly. */
    public record DeletionReport(boolean userDeleted, boolean gmailTokenRevokedAtGoogle) {}

    @Transactional
    public DeletionReport deleteEverything(UUID userId) {
        boolean revoked = revokeGrant(userId);

        Optional<User> user = users.findById(userId);
        user.ifPresent(users::delete);

        log.info("Deleted all data for user {} (Google grant revoked: {})", userId, revoked);
        return new DeletionReport(user.isPresent(), revoked);
    }

    /** Disconnects Gmail but keeps the account and the history already built. */
    @Transactional
    public boolean disconnectGmail(UUID userId) {
        boolean revoked = revokeGrant(userId);
        gmailAccounts.disconnect(userId);
        return revoked;
    }

    private boolean revokeGrant(UUID userId) {
        Optional<GmailAccount> account = gmailAccounts.findByUserId(userId);
        if (account.isEmpty() || !account.get().hasToken() || !tokens.isConfigured()) {
            return false;
        }
        try {
            String refreshToken = tokens.decrypt(
                    account.get().getRefreshTokenCipher(), account.get().getRefreshTokenIv());
            return revoker.revoke(refreshToken);
        } catch (RuntimeException ex) {
            // A token we cannot decrypt is already unusable.
            log.warn("Could not decrypt the stored token during deletion; continuing");
            return false;
        }
    }
}
