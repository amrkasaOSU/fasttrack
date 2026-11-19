package com.fasttrack.gmail;

import com.fasttrack.common.NotConfiguredException;
import com.fasttrack.common.RateLimiter;
import com.fasttrack.config.AppProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls mail from Gmail and turns it into application history.
 *
 * <p>Properties that matter more than speed:
 * <ul>
 *   <li><b>Idempotent.</b> A Gmail message id is processed at most once per
 *       user, enforced by a unique index as well as by a check, so re-running
 *       or overlapping syncs cannot duplicate anything.</li>
 *   <li><b>Resumable.</b> The first sync walks the last N days; afterwards
 *       Gmail's {@code historyId} is the cursor. The cursor is read
 *       <em>before</em> listing and saved only after a successful run, so a
 *       crash re-processes rather than skips.</li>
 *   <li><b>Polite.</b> Retryable failures back off exponentially; a
 *       per-message failure is skipped rather than aborting the run; an auth
 *       failure stops immediately and asks the user to reconnect.</li>
 *   <li><b>Cheap.</b> The prefilter runs before any model call, and every
 *       count lands on the {@link SyncRun} so the savings are measured.</li>
 *   <li><b>Private.</b> Bodies live in memory for the length of one message
 *       and are never stored.</li>
 * </ul>
 *
 * <p>The run as a whole is deliberately not one transaction: each message
 * commits on its own through {@link SyncMessageProcessor}.
 */
@Service
public class GmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final GmailApi gmail;
    private final GmailAccountService accountService;
    private final SyncMessageProcessor processor;
    private final SyncRunRepository syncRuns;
    private final TokenEncryptionService tokens;
    private final RateLimiter rateLimiter;
    private final AppProperties props;

    public GmailSyncService(GmailApi gmail,
                            GmailAccountService accountService,
                            SyncMessageProcessor processor,
                            SyncRunRepository syncRuns,
                            TokenEncryptionService tokens,
                            RateLimiter rateLimiter,
                            AppProperties props) {
        this.gmail = gmail;
        this.accountService = accountService;
        this.processor = processor;
        this.syncRuns = syncRuns;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    public SyncRun sync(UUID userId, SyncTrigger trigger) {
        GmailAccount account = accountService.findByUserId(userId).orElse(null);
        if (account == null || !account.hasToken()) {
            SyncRun skipped = new SyncRun(userId, trigger);
            skipped.finished(SyncStatus.SKIPPED, "No Gmail account is connected");
            return syncRuns.save(skipped);
        }
        if (!tokens.isConfigured()) {
            throw new NotConfiguredException("gmail",
                    "This deployment has no token encryption key, so Gmail cannot be used.");
        }
        if (trigger == SyncTrigger.MANUAL) {
            rateLimiter.checkAndConsume(userId, "sync", props.getRateLimit().getSyncPerHour());
        }

        SyncRun run = syncRuns.save(new SyncRun(userId, trigger));
        accountService.markRunning(account.getId());

        try {
            GmailCredentials credentials = accountService.credentialsFor(account);

            // Read the cursor first: mail that arrives during this run is then
            // picked up next time instead of being skipped.
            String cursorBefore = currentHistoryIdOrNull(credentials);

            List<String> messageIds = account.getLastHistoryId() == null
                    ? listWindow(credentials)
                    : listSince(credentials, account.getLastHistoryId());

            int cap = props.getSync().getMaxMessagesPerRun();
            for (String messageId : messageIds) {
                if (run.getMessagesFetched() >= cap) {
                    log.info("Sync for user {} stopped at the per-run cap of {} messages", userId, cap);
                    break;
                }
                handleMessage(userId, credentials, messageId, run);
            }

            run.finished(SyncStatus.SUCCESS, null);
            syncRuns.save(run);
            accountService.markSucceeded(account.getId(), cursorBefore);
            return run;

        } catch (GmailApiException ex) {
            String message = ex.isAuthFailure()
                    ? "Gmail access has expired. Reconnect Gmail to keep syncing."
                    : "Gmail sync could not complete. It will be retried automatically.";
            log.warn("Gmail sync failed for user {}: {}", userId, ex.getMessage());
            run.finished(SyncStatus.FAILED, message);
            syncRuns.save(run);
            accountService.markFailed(account.getId(), message, ex.isAuthFailure());
            return run;
        } catch (RuntimeException ex) {
            log.error("Unexpected failure during Gmail sync for user {}", userId, ex);
            run.finished(SyncStatus.FAILED, "Sync failed unexpectedly.");
            syncRuns.save(run);
            accountService.markFailed(account.getId(), "Sync failed unexpectedly.", false);
            return run;
        }
    }

    /** Fetches one message and hands it to the processor, counting the result. */
    private void handleMessage(UUID userId, GmailCredentials credentials, String messageId, SyncRun run) {
        GmailMessage message;
        try {
            message = withRetry(() -> gmail.getMessage(credentials, messageId));
        } catch (GmailApiException ex) {
            if (ex.isAuthFailure()) {
                throw ex;
            }
            log.warn("Skipping message {} after {} failed attempts", messageId, MAX_ATTEMPTS);
            return;
        }

        SyncMessageProcessor.Outcome outcome = processor.process(userId, message);
        if (outcome.alreadySeen()) {
            return;
        }

        run.countFetched();
        run.countNew();
        if (outcome.dropped()) {
            run.countDropped();
        }
        if (outcome.classified()) {
            run.countClassified();
        }
        if (outcome.cacheHit()) {
            run.countCacheHit();
        }
        if (outcome.llmCall()) {
            run.countLlmCall();
        }
        if (outcome.applicationCreated()) {
            run.countApplicationCreated();
        }
        if (outcome.applicationUpdated()) {
            run.countApplicationUpdated();
        }
    }

    /** The first sync: everything in the configured window, page by page. */
    private List<String> listWindow(GmailCredentials credentials) {
        String query = "newer_than:" + props.getSync().getInitialDays() + "d -in:chats";
        Set<String> ids = new LinkedHashSet<>();
        String pageToken = null;
        int cap = props.getSync().getMaxMessagesPerRun();

        do {
            final String token = pageToken;
            GmailPage page = withRetry(() ->
                    gmail.listMessageIds(credentials, query, props.getSync().getBatchSize(), token));
            ids.addAll(page.messageIds());
            pageToken = page.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank() && ids.size() < cap);

        return List.copyOf(ids);
    }

    /**
     * Incremental sync. Gmail expires history ids after roughly a week; when
     * that happens, falling back to a window sync is correct, and the unique
     * index on (user, gmail message id) keeps it idempotent.
     */
    private List<String> listSince(GmailCredentials credentials, String historyId) {
        try {
            return withRetry(() -> gmail.listAddedMessageIdsSince(credentials, historyId));
        } catch (GmailApiException ex) {
            if (ex.isAuthFailure()) {
                throw ex;
            }
            log.info("History cursor is no longer valid; falling back to a window sync");
            return listWindow(credentials);
        }
    }

    private String currentHistoryIdOrNull(GmailCredentials credentials) {
        try {
            return gmail.currentHistoryId(credentials);
        } catch (GmailApiException ex) {
            if (ex.isAuthFailure()) {
                throw ex;
            }
            return null;
        }
    }

    private interface GmailCall<T> {
        T execute();
    }

    /** Exponential backoff for the failures Gmail says are worth retrying. */
    private <T> T withRetry(GmailCall<T> call) {
        GmailApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.execute();
            } catch (GmailApiException ex) {
                last = ex;
                if (!ex.isRetryable() || attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                sleep(250L * (1L << (attempt - 1)));
            }
        }
        throw last;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public Optional<SyncRun> lastRun(UUID userId) {
        return syncRuns.findFirstByUserIdOrderByStartedAtDesc(userId);
    }

    public List<SyncRun> recentRuns(UUID userId) {
        return syncRuns.findTop20ByUserIdOrderByStartedAtDesc(userId);
    }
}
