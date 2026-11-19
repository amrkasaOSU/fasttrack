package com.fasttrack.gmail;

import com.fasttrack.account.AccountDeletionService;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.auth.AuthorizationService;
import com.fasttrack.config.AppProperties;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.PrefilterResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings: the state of the Gmail connection, manual sync, disconnect, and
 * deleting everything.
 *
 * <p>Both destructive actions are refused for the shared demo account, which
 * is read-only in the ways that matter.
 */
@RestController
@RequestMapping("/api/settings")
@Tag(name = "Settings", description = "Gmail connection, sync, and data deletion")
public class SettingsController {

    private final AuthorizationService authorization;
    private final GmailAccountService accounts;
    private final GmailSyncService syncService;
    private final AccountDeletionService deletion;
    private final EmailMessageRepository emails;
    private final EmailClassificationRepository classifications;
    private final ApplicationRepository applications;
    private final AppProperties props;

    public SettingsController(AuthorizationService authorization,
                              GmailAccountService accounts,
                              GmailSyncService syncService,
                              AccountDeletionService deletion,
                              EmailMessageRepository emails,
                              EmailClassificationRepository classifications,
                              ApplicationRepository applications,
                              AppProperties props) {
        this.authorization = authorization;
        this.accounts = accounts;
        this.syncService = syncService;
        this.deletion = deletion;
        this.emails = emails;
        this.classifications = classifications;
        this.applications = applications;
        this.props = props;
    }

    public record GmailStatus(
            boolean available,
            boolean connected,
            String emailAddress,
            GmailSyncStatus syncStatus,
            String syncError,
            Instant connectedAt,
            Instant lastSyncAt,
            boolean needsReconnect,
            String scopes) {}

    public record SyncSummary(
            UUID id,
            SyncTrigger trigger,
            SyncStatus status,
            Instant startedAt,
            Instant finishedAt,
            int messagesFetched,
            int messagesDropped,
            int messagesClassified,
            int llmCalls,
            int cacheHits,
            int applicationsCreated,
            int applicationsUpdated,
            String errorMessage) {

        static SyncSummary from(SyncRun run) {
            return new SyncSummary(run.getId(), run.getTriggerSource(), run.getStatus(),
                    run.getStartedAt(), run.getFinishedAt(), run.getMessagesFetched(),
                    run.getMessagesDropped(), run.getMessagesClassified(), run.getLlmCalls(),
                    run.getCacheHits(), run.getApplicationsCreated(), run.getApplicationsUpdated(),
                    run.getErrorMessage());
        }
    }

    public record DataSummary(
            long applications,
            long emailsStored,
            long emailsDroppedByPrefilter,
            long classifications) {}

    public record SettingsResponse(GmailStatus gmail, SyncSummary lastSync, DataSummary data) {}

    @GetMapping
    @Operation(summary = "Gmail connection state, the last sync, and what is stored")
    public SettingsResponse get() {
        UUID userId = authorization.requireUserId();
        var account = accounts.findByUserId(userId);

        GmailStatus gmail = account
                .map(a -> new GmailStatus(
                        props.getGoogle().isConfigured() && props.isTokenEncryptionConfigured(),
                        a.hasToken(),
                        a.getEmailAddress(),
                        a.getSyncStatus(),
                        a.getSyncError(),
                        a.getConnectedAt(),
                        a.getLastSyncAt(),
                        !a.hasToken() || a.getSyncStatus() == GmailSyncStatus.REVOKED,
                        a.getScopes()))
                .orElseGet(() -> new GmailStatus(
                        props.getGoogle().isConfigured() && props.isTokenEncryptionConfigured(),
                        false, null, null, null, null, null, false, null));

        return new SettingsResponse(
                gmail,
                syncService.lastRun(userId).map(SyncSummary::from).orElse(null),
                new DataSummary(
                        applications.countByUserId(userId),
                        emails.countByUserId(userId),
                        emails.countByUserIdAndPrefilterResult(userId, PrefilterResult.DROPPED),
                        classifications.countByUserId(userId)));
    }

    @PostMapping("/gmail/sync")
    @Operation(summary = "Sync now")
    public SyncSummary syncNow() {
        UUID userId = authorization.requireUserId();
        authorization.requireNotDemo("sync Gmail");
        return SyncSummary.from(syncService.sync(userId, SyncTrigger.MANUAL));
    }

    @PostMapping("/gmail/disconnect")
    @Operation(summary = "Disconnect Gmail and revoke the token at Google")
    public GmailStatus disconnect() {
        UUID userId = authorization.requireUserId();
        authorization.requireNotDemo("disconnect Gmail");
        deletion.disconnectGmail(userId);
        return get().gmail();
    }

    public record DeletionResponse(boolean deleted, boolean googleGrantRevoked, String message) {}

    @DeleteMapping("/data")
    @Operation(summary = "Delete the account and every piece of data belonging to it")
    public ResponseEntity<DeletionResponse> deleteEverything(HttpServletRequest request) {
        UUID userId = authorization.requireUserId();
        authorization.requireNotDemo("delete the shared demo data");

        var report = deletion.deleteEverything(userId);

        // The session now points at a user that no longer exists.
        request.getSession(false);
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }

        return ResponseEntity.ok(new DeletionResponse(
                report.userDeleted(),
                report.gmailTokenRevokedAtGoogle(),
                report.userDeleted()
                        ? "Everything has been deleted. You have been signed out."
                        : "There was nothing left to delete."));
    }
}
