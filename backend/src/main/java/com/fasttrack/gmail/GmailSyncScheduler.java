package com.fasttrack.gmail;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs syncs without anyone pressing a button.
 *
 * <p>Two triggers: the periodic job (every 15 minutes by default, set by
 * {@code APP_SYNC_CRON}), and the moment a mailbox is connected, so a new user
 * sees their applications appear instead of waiting a quarter of an hour.
 *
 * <p>One account failing never stops the others: each is wrapped
 * individually, and {@code findSyncable()} already excludes accounts that are
 * mid-sync or revoked.
 */
@Component
public class GmailSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncScheduler.class);

    private final GmailAccountRepository accounts;
    private final GmailSyncService syncService;

    public GmailSyncScheduler(GmailAccountRepository accounts, GmailSyncService syncService) {
        this.accounts = accounts;
        this.syncService = syncService;
    }

    @Scheduled(cron = "${app.sync.cron:0 */15 * * * *}")
    public void syncAllConnectedMailboxes() {
        List<GmailAccount> syncable = accounts.findSyncable();
        if (syncable.isEmpty()) {
            return;
        }
        log.info("Scheduled sync starting for {} mailbox(es)", syncable.size());

        for (GmailAccount account : syncable) {
            try {
                SyncRun run = syncService.sync(account.getUserId(), SyncTrigger.SCHEDULED);
                log.info("Scheduled sync for user {} finished {}: {} fetched, {} dropped by prefilter, {} classified",
                        account.getUserId(), run.getStatus(), run.getMessagesFetched(),
                        run.getMessagesDropped(), run.getMessagesClassified());
            } catch (RuntimeException ex) {
                log.error("Scheduled sync failed for user {}", account.getUserId(), ex);
            }
        }
    }

    /** First sync, immediately after a mailbox is connected. */
    @Async
    @EventListener
    public void onGmailConnected(GmailTokenCapture.GmailConnectedEvent event) {
        try {
            SyncRun run = syncService.sync(event.userId(), SyncTrigger.INITIAL);
            log.info("Initial sync for user {} finished {}: {} messages fetched",
                    event.userId(), run.getStatus(), run.getMessagesFetched());
        } catch (RuntimeException ex) {
            log.error("Initial sync failed for user {}", event.userId(), ex);
        }
    }
}
