package com.fasttrack.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.PrefilterResult;
import com.fasttrack.gmail.GmailAccountService;
import com.fasttrack.gmail.GmailStub;
import com.fasttrack.gmail.GmailSyncService;
import com.fasttrack.gmail.SyncRun;
import com.fasttrack.gmail.SyncStatus;
import com.fasttrack.gmail.SyncTrigger;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.UserFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Sync throughput: how many emails a minute the pipeline can take in.
 *
 * <pre>
 *   cd backend &amp;&amp; mvn -Peval test -Dtest=SyncThroughputTest
 * </pre>
 *
 * <p>Gmail is a local stub, so the number measures FastTrack's own work -
 * fetching, prefiltering, classifying, matching, writing - and not Google's
 * network latency. That is the honest thing to measure here: the network
 * cost is outside the application's control, while everything else is not.
 *
 * <p>The classifier runs on its deterministic path, because this is a
 * throughput measurement rather than a quality one and a real model's latency
 * would swamp it. RESULTS.md states both facts next to the number.
 */
@Tag("eval")
class SyncThroughputTest extends AbstractIntegrationTest {

    private static final int MESSAGE_COUNT = 300;
    private static final GmailStub GMAIL = new GmailStub();
    private static final String ENCRYPTION_KEY = newKey();

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.gmail.root-url", GMAIL::rootUrl);
        registry.add("app.gmail.token-uri", GMAIL::tokenUri);
        registry.add("app.google.client-id", () -> "throughput-client");
        registry.add("app.google.client-secret", () -> "throughput-secret");
        registry.add("app.token-encryption-key", () -> ENCRYPTION_KEY);
        registry.add("app.sync.max-messages-per-run", () -> 5000);
        registry.add("app.sync.batch-size", () -> 100);
    }

    @Autowired GmailSyncService syncService;
    @Autowired GmailAccountService accounts;
    @Autowired EmailMessageRepository emails;
    @Autowired UserFixtures fixtures;

    @Test
    @DisplayName("measure how many emails a minute a sync can process")
    void measureThroughput() throws Exception {
        UUID userId = fixtures.newUser("throughput").getId();
        accounts.connect(userId, "throughput@example.com",
                "openid email profile https://www.googleapis.com/auth/gmail.readonly",
                "stub-refresh-token");

        GMAIL.reset();
        GMAIL.stubProfile("throughput@example.com", "500100");

        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= MESSAGE_COUNT; i++) {
            String id = "perf-" + i;
            ids.add(id);
            stubOne(id, i);
        }
        GMAIL.stubMessageList(ids);

        long startedAt = System.nanoTime();
        SyncRun run = syncService.sync(userId, SyncTrigger.INITIAL);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(run.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(run.getMessagesFetched()).isEqualTo(MESSAGE_COUNT);

        double seconds = elapsedMs / 1000.0;
        double perMinute = MESSAGE_COUNT / seconds * 60;
        long dropped = emails.countByUserIdAndPrefilterResult(userId, PrefilterResult.DROPPED);
        long kept = emails.countByUserIdAndPrefilterResult(userId, PrefilterResult.KEPT);

        String report = """
                # Sync throughput

                Generated %s

                | Setting | Value |
                |---|---|
                | Messages | %d |
                | Gmail | local WireMock stub (no network latency) |
                | Classifier | deterministic path (no API key set) |
                | Database | PostgreSQL 16 in Testcontainers |
                | Command | `mvn -Peval test -Dtest=SyncThroughputTest` |

                | Measure | Value |
                |---|---:|
                | Wall-clock time | %.2f s |
                | Throughput | **%.0f emails/minute** |
                | Per message | %.1f ms |
                | Kept by the prefilter | %d |
                | Dropped by the prefilter | %d (%.0f%%) |
                | Applications created | %d |

                Each message is fetched, prefiltered, classified, matched to an
                application and written in its own transaction. The stubbed Gmail
                removes network time on purpose: what is being measured is the work
                this application does, which is the part its design controls.
                """.formatted(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                MESSAGE_COUNT, seconds, perMinute, elapsedMs / (double) MESSAGE_COUNT,
                kept, dropped, 100.0 * dropped / MESSAGE_COUNT, run.getApplicationsCreated());

        Path output = Path.of("..", "eval", "results", "sync-throughput.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println(report);

        // A guard, not a target: this catches an accidental N+1 or a
        // per-message sleep creeping in.
        assertThat(perMinute).as("emails per minute").isGreaterThan(300);
    }

    /** A realistic mix: confirmations, rejections, assessments and noise. */
    private void stubOne(String id, int index) {
        String company = "Company " + (index % 40);
        long receivedAt = Instant.parse("2027-01-01T09:00:00Z")
                .plus(Duration.ofHours(index)).toEpochMilli();

        switch (index % 4) {
            case 0 -> GMAIL.stubMessage(id,
                    company + " Careers <no-reply@greenhouse-mail.io>",
                    "Thank you for applying to " + company,
                    "Hi, thank you for applying to the Software Engineer Intern position at "
                    + company + ". We have received your application.", receivedAt);
            case 1 -> GMAIL.stubMessage(id,
                    company + " Recruiting <no-reply@myworkday.com>",
                    "Your application to " + company,
                    "Thank you for your interest. After careful review, we have decided to move "
                    + "forward with other candidates.", receivedAt);
            case 2 -> GMAIL.stubMessage(id,
                    "CodeSignal <no-reply@codesignal.com>",
                    company + " invites you to complete an online assessment",
                    company + " has invited you to complete an online assessment for the "
                    + "Software Engineer Intern position. It takes about 70 minutes.", receivedAt);
            default -> GMAIL.stubMessage(id,
                    "LinkedIn Job Alerts <jobalerts-noreply@linkedin.com>",
                    "20 new jobs for \"software engineer intern\"",
                    "Your job alert for software engineer intern. See all new jobs matching "
                    + "your search.", receivedAt);
        }
    }
}
