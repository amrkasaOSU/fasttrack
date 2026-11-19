package com.fasttrack.gmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.JobApplication;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.PrefilterResult;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.UserFixtures;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole sync, end to end, against a stubbed Gmail: real database, real
 * Redis, real prefilter, real classifier (rule path, since no LLM key is set
 * in tests), real matcher, real state machine.
 *
 * <p>What these tests are really checking is the set of promises in
 * docs/privacy.md and the sync's own contract: bodies are never stored, a
 * message is processed once, the cursor advances only on success, and a
 * failure part-way through keeps the work already done.
 */
class GmailSyncIntegrationTest extends AbstractIntegrationTest {

    private static final GmailStub GMAIL = new GmailStub();
    private static final String ENCRYPTION_KEY = newKey();

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void gmailProperties(DynamicPropertyRegistry registry) {
        registry.add("app.gmail.root-url", GMAIL::rootUrl);
        registry.add("app.gmail.token-uri", GMAIL::tokenUri);
        registry.add("app.google.client-id", () -> "test-client-id");
        registry.add("app.google.client-secret", () -> "test-client-secret");
        registry.add("app.token-encryption-key", () -> ENCRYPTION_KEY);
        registry.add("app.sync.initial-days", () -> 120);
        registry.add("app.sync.batch-size", () -> 50);
    }

    @Autowired GmailSyncService syncService;
    @Autowired GmailAccountService accountService;
    @Autowired GmailAccountRepository accounts;
    @Autowired EmailMessageRepository emails;
    @Autowired EmailClassificationRepository classifications;
    @Autowired ApplicationRepository applications;
    @Autowired SyncRunRepository syncRuns;
    @Autowired UserFixtures fixtures;

    private UUID userId;

    private static final String REJECTION_BODY = """
            Hi Amr,

            Thank you for your interest in the Software Engineer Intern position at Stripe.
            After careful review, we have decided to move forward with other candidates.

            Best wishes,
            Stripe Talent Team
            """;

    private static final String CONFIRMATION_BODY = """
            Hi Amr,

            Thank you for applying to the Data Engineer Intern position at Datadog.
            We have received your application (requisition JR884412).
            """;

    private static final String JOB_ALERT_BODY = """
            20 new jobs for "software engineer intern"

            Stripe - Software Engineer Intern
            See all new jobs matching your search.
            """;

    @BeforeEach
    void setUp() {
        GMAIL.reset();
        userId = fixtures.newUser("gmail-sync").getId();
        accountService.connect(userId, "amr@example.com",
                "openid email profile https://www.googleapis.com/auth/gmail.readonly",
                "stub-refresh-token");
    }

    private void stubThreeMessages() {
        GMAIL.stubProfile("amr@example.com", "900100");
        GMAIL.stubMessageList(List.of("m1", "m2", "m3"));
        GMAIL.stubMessage("m1", "Stripe Recruiting <no-reply@greenhouse-mail.io>",
                "Your application to Stripe", REJECTION_BODY, 1771243200000L);
        GMAIL.stubMessage("m2", "Datadog Careers <careers@greenhouse-mail.io>",
                "We received your application - Data Engineer Intern", CONFIRMATION_BODY, 1771329600000L);
        GMAIL.stubMessage("m3", "LinkedIn Job Alerts <jobalerts-noreply@linkedin.com>",
                "20 new jobs for \"software engineer intern\"", JOB_ALERT_BODY, 1771416000000L);
    }

    @Test
    @DisplayName("a first sync fetches the window, classifies what matters, and builds applications")
    void initialSync() {
        stubThreeMessages();

        SyncRun run = syncService.sync(userId, SyncTrigger.INITIAL);

        assertThat(run.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(run.getMessagesFetched()).isEqualTo(3);
        assertThat(run.getMessagesNew()).isEqualTo(3);
        assertThat(run.getMessagesDropped()).as("the job alert is dropped before classification").isEqualTo(1);
        assertThat(run.getMessagesClassified()).isEqualTo(2);
        assertThat(run.getApplicationsCreated()).isEqualTo(2);

        assertThat(emails.countByUserId(userId)).isEqualTo(3);
        assertThat(emails.countByUserIdAndPrefilterResult(userId, PrefilterResult.DROPPED)).isEqualTo(1);
        assertThat(classifications.countByUserId(userId)).isEqualTo(2);

        List<JobApplication> created = applications.findAllByUserIdOrderByCreatedAtDesc(userId);
        assertThat(created).hasSize(2);
        assertThat(created).extracting(JobApplication::getCompanyName)
                .containsExactlyInAnyOrder("Stripe", "Datadog");
    }

    @Test
    @DisplayName("a rejection email moves its application to Rejected, with the email linked")
    void rejectionDrivesTheStatus() {
        stubThreeMessages();

        syncService.sync(userId, SyncTrigger.INITIAL);

        JobApplication stripe = applications.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(a -> a.getCompanyName().equals("Stripe"))
                .findFirst().orElseThrow();

        assertThat(stripe.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(stripe.getFirstResponseAt()).isNotNull();
        assertThat(emails.findAllByApplicationIdAndUserIdOrderByReceivedAtDesc(stripe.getId(), userId))
                .hasSize(1);
    }

    @Test
    @DisplayName("message bodies are never stored - only metadata and the extracted fields")
    void bodiesAreNotStored() {
        stubThreeMessages();

        syncService.sync(userId, SyncTrigger.INITIAL);

        emails.findAll().forEach(email -> {
            assertThat(email.getSnippet()).doesNotContain("After careful review");
            assertThat(email.getSnippet().length()).isLessThanOrEqualTo(200);
        });
        // The classification keeps the extracted fields, not the prose.
        classifications.findAll().forEach(classification ->
                assertThat(classification.getReason() == null ? "" : classification.getReason())
                        .doesNotContain("Best wishes"));
    }

    @Test
    @DisplayName("running the same sync twice processes nothing twice")
    void syncIsIdempotent() {
        stubThreeMessages();

        syncService.sync(userId, SyncTrigger.INITIAL);
        long emailsAfterFirst = emails.countByUserId(userId);
        long applicationsAfterFirst = applications.countByUserId(userId);

        GmailAccount account = accounts.findByUserId(userId).orElseThrow();
        account.setLastHistoryId(null); // force a window sync again
        accounts.save(account);

        SyncRun second = syncService.sync(userId, SyncTrigger.MANUAL);

        assertThat(second.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(second.getMessagesNew()).isZero();
        assertThat(emails.countByUserId(userId)).isEqualTo(emailsAfterFirst);
        assertThat(applications.countByUserId(userId)).isEqualTo(applicationsAfterFirst);
    }

    @Test
    @DisplayName("the history cursor is stored after a successful sync and used next time")
    void incrementalSyncUsesTheCursor() {
        stubThreeMessages();
        syncService.sync(userId, SyncTrigger.INITIAL);

        assertThat(accounts.findByUserId(userId).orElseThrow().getLastHistoryId()).isEqualTo("900100");

        GMAIL.reset();
        GMAIL.stubProfile("amr@example.com", "900200");
        GMAIL.stubHistory(List.of("m4"), "900200");
        GMAIL.stubMessage("m4", "Datadog Careers <careers@greenhouse-mail.io>",
                "Interview invitation - Data Engineer Intern",
                "Hi Amr, we would like to invite you to interview for the Data Engineer Intern role.",
                1771502400000L);

        SyncRun second = syncService.sync(userId, SyncTrigger.MANUAL);

        assertThat(second.getMessagesFetched()).isEqualTo(1);
        assertThat(GMAIL.requestCountFor("/gmail/v1/users/me/history")).isEqualTo(1);
        assertThat(accounts.findByUserId(userId).orElseThrow().getLastHistoryId()).isEqualTo("900200");
    }

    @Test
    @DisplayName("a later email advances an existing application instead of creating a second one")
    void secondEmailUpdatesTheSameApplication() {
        stubThreeMessages();
        syncService.sync(userId, SyncTrigger.INITIAL);
        long applicationsAfterFirst = applications.countByUserId(userId);

        GMAIL.reset();
        GMAIL.stubProfile("amr@example.com", "900300");
        GMAIL.stubHistory(List.of("m5"), "900300");
        GMAIL.stubMessage("m5", "Datadog Careers <careers@greenhouse-mail.io>",
                "Interview invitation - Data Engineer Intern",
                "Hi Amr, we would like to invite you to interview for the Data Engineer Intern position.",
                1771502400000L);

        SyncRun second = syncService.sync(userId, SyncTrigger.MANUAL);

        assertThat(applications.countByUserId(userId)).isEqualTo(applicationsAfterFirst);
        assertThat(second.getApplicationsUpdated()).isEqualTo(1);

        JobApplication datadog = applications.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(a -> a.getCompanyName().equals("Datadog"))
                .findFirst().orElseThrow();
        assertThat(datadog.getStatus()).isEqualTo(ApplicationStatus.INTERVIEW_REQUESTED);
    }

    @Test
    @DisplayName("a rate-limited message is retried and still processed")
    void retriesTransientFailures() {
        GMAIL.stubProfile("amr@example.com", "900100");
        GMAIL.stubMessageList(List.of("m1"));
        GMAIL.stubFlakyMessage("m1", "Stripe Recruiting <no-reply@greenhouse-mail.io>",
                "Your application to Stripe", REJECTION_BODY, 1771243200000L);

        SyncRun run = syncService.sync(userId, SyncTrigger.INITIAL);

        assertThat(run.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(run.getMessagesFetched()).isEqualTo(1);
        assertThat(GMAIL.requestCountFor("/gmail/v1/users/me/messages/m1")).isEqualTo(2);
    }

    @Test
    @DisplayName("one unreadable message does not lose the rest of the run")
    void oneBadMessageDoesNotAbortTheSync() {
        GMAIL.stubProfile("amr@example.com", "900100");
        GMAIL.stubMessageList(List.of("m1", "bad", "m2"));
        GMAIL.stubMessage("m1", "Stripe Recruiting <no-reply@greenhouse-mail.io>",
                "Your application to Stripe", REJECTION_BODY, 1771243200000L);
        GMAIL.stubMessageFailure("bad", 500);
        GMAIL.stubMessage("m2", "Datadog Careers <careers@greenhouse-mail.io>",
                "We received your application - Data Engineer Intern", CONFIRMATION_BODY, 1771329600000L);

        SyncRun run = syncService.sync(userId, SyncTrigger.INITIAL);

        assertThat(run.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(run.getMessagesFetched()).isEqualTo(2);
        assertThat(applications.countByUserId(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a revoked grant stops the sync and asks the user to reconnect")
    void revokedGrantIsReported() {
        GMAIL.reset();
        GMAIL.stubTokenRejected();
        GMAIL.stubProfile("amr@example.com", "900100");
        GMAIL.stubMessageList(List.of("m1"));

        SyncRun run = syncService.sync(userId, SyncTrigger.MANUAL);

        assertThat(run.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("Reconnect Gmail");

        GmailAccount account = accounts.findByUserId(userId).orElseThrow();
        assertThat(account.getSyncStatus()).isEqualTo(GmailSyncStatus.REVOKED);
        assertThat(account.hasToken()).isFalse();
    }

    @Test
    @DisplayName("a user with no mailbox connected gets a skipped run, not an error")
    void noMailboxIsSkipped() {
        UUID other = fixtures.newUser("no-gmail").getId();

        SyncRun run = syncService.sync(other, SyncTrigger.SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(SyncStatus.SKIPPED);
        assertThat(run.getErrorMessage()).contains("No Gmail account");
    }

    @Test
    @DisplayName("sync history is recorded, with the counts the dashboard reports")
    void syncRunsAreRecorded() {
        stubThreeMessages();

        syncService.sync(userId, SyncTrigger.INITIAL);

        assertThat(syncRuns.countByUserId(userId)).isEqualTo(1);
        SyncRun stored = syncService.lastRun(userId).orElseThrow();
        assertThat(stored.getTriggerSource()).isEqualTo(SyncTrigger.INITIAL);
        assertThat(stored.getFinishedAt()).isNotNull();
        assertThat(stored.messagesPerMinute()).isGreaterThan(0);
    }

    @Test
    @DisplayName("the stored token is encrypted, not readable in the database")
    void tokenIsStoredEncrypted() {
        GmailAccount account = accounts.findByUserId(userId).orElseThrow();

        assertThat(account.getRefreshTokenCipher()).isNotNull();
        assertThat(account.getRefreshTokenIv()).isNotNull().hasSize(12);
        assertThat(new String(account.getRefreshTokenCipher(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("stub-refresh-token");
    }

    @Test
    @DisplayName("disconnecting forgets the token but keeps the applications already found")
    void disconnectKeepsHistory() {
        stubThreeMessages();
        syncService.sync(userId, SyncTrigger.INITIAL);
        long before = applications.countByUserId(userId);

        accountService.disconnect(userId);

        GmailAccount account = accounts.findByUserId(userId).orElseThrow();
        assertThat(account.hasToken()).isFalse();
        assertThat(account.getSyncStatus()).isEqualTo(GmailSyncStatus.REVOKED);
        assertThat(applications.countByUserId(userId)).isEqualTo(before);

        SyncRun run = syncService.sync(userId, SyncTrigger.MANUAL);
        assertThat(run.getStatus()).isEqualTo(SyncStatus.SKIPPED);
    }

    @Test
    @DisplayName("the sender's display name and address are parsed out of the From header")
    void fromHeaderParsing() {
        stubThreeMessages();
        syncService.sync(userId, SyncTrigger.INITIAL);

        var stored = emails.findByUserIdAndGmailMessageId(userId, "m1").orElseThrow();

        assertThat(stored.getSenderEmail()).isEqualTo("no-reply@greenhouse-mail.io");
        assertThat(stored.getSenderName()).isEqualTo("Stripe Recruiting");
        assertThat(stored.getSenderDomain()).isEqualTo("greenhouse-mail.io");
        assertThat(stored.getReceivedAt()).isEqualTo(Instant.ofEpochMilli(1771243200000L));
    }

    @Test
    @DisplayName("the job alert is stored as dropped, with the reason, and never classified")
    void droppedMailIsRecordedWithItsReason() {
        stubThreeMessages();
        syncService.sync(userId, SyncTrigger.INITIAL);

        var alert = emails.findByUserIdAndGmailMessageId(userId, "m3").orElseThrow();

        assertThat(alert.getPrefilterResult()).isEqualTo(PrefilterResult.DROPPED);
        assertThat(alert.getPrefilterReason()).isNotBlank();
        assertThat(classifications.findByEmailId(alert.getId())).isEmpty();
    }

    @Test
    @DisplayName("classifications record how they were produced")
    void classificationsRecordTheirMethod() {
        stubThreeMessages();
        syncService.sync(userId, SyncTrigger.INITIAL);

        var email = emails.findByUserIdAndGmailMessageId(userId, "m1").orElseThrow();
        var classification = classifications.findByEmailId(email.getId()).orElseThrow();

        assertThat(classification.getCategory()).isEqualTo(EmailCategory.REJECTION);
        // No LLM key is configured in tests, so the deterministic path runs.
        assertThat(classification.getMethod()).isEqualTo(com.fasttrack.classify.ClassificationMethod.RULE);
        assertThat(classification.getPromptVersion()).isEqualTo("rules-v1");
        assertThat(classification.getConfidence()).isBetween(0.0, 1.0);
    }
}
