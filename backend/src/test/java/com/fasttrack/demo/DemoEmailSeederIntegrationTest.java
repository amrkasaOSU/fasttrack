package com.fasttrack.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.account.User;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.PrefilterResult;
import com.fasttrack.insight.InsightsService;
import com.fasttrack.review.ReviewService;
import com.fasttrack.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The demo mailbox has to be internally consistent with the demo
 * applications, or the demo teaches a visitor the wrong thing about the
 * product.
 */
class DemoEmailSeederIntegrationTest extends AbstractIntegrationTest {

    @Autowired DemoAccountService demoAccounts;
    @Autowired DemoDataSeeder applicationSeeder;
    @Autowired DemoEmailSeeder emailSeeder;
    @Autowired EmailMessageRepository emails;
    @Autowired EmailClassificationRepository classifications;
    @Autowired ApplicationRepository applications;
    @Autowired ReviewService review;
    @Autowired InsightsService insights;

    private User seed() {
        User demo = demoAccounts.ensureDemoUser();
        applicationSeeder.seedApplications(demo);
        emailSeeder.seedEmails(demo.getId());
        return demo;
    }

    @Test
    @DisplayName("the demo mailbox has enough mail to be worth looking at")
    void seedsAUsefulNumberOfEmails() {
        User demo = seed();

        long stored = emails.countByUserId(demo.getId());

        assertThat(stored).isGreaterThan(100);
        assertThat(classifications.countByUserId(demo.getId())).isGreaterThan(50);
    }

    @Test
    @DisplayName("seeding twice does not duplicate the mailbox")
    void idempotent() {
        User demo = seed();
        long first = emails.countByUserId(demo.getId());

        assertThat(emailSeeder.seedEmails(demo.getId())).isZero();
        assertThat(emails.countByUserId(demo.getId())).isEqualTo(first);
    }

    @Test
    @DisplayName("noise is included and the prefilter drops it, so the saved-calls number is real")
    void noiseIsDroppedByThePrefilter() {
        User demo = seed();

        long dropped = emails.countByUserIdAndPrefilterResult(demo.getId(), PrefilterResult.DROPPED);
        long kept = emails.countByUserIdAndPrefilterResult(demo.getId(), PrefilterResult.KEPT);

        assertThat(dropped).as("job alerts and newsletters").isGreaterThan(20);
        assertThat(kept).as("real application mail").isGreaterThan(60);
    }

    @Test
    @DisplayName("every classified email belongs to an application that exists")
    void emailsAreLinkedConsistently() {
        User demo = seed();

        emails.findAll().stream()
                .filter(email -> email.getUserId().equals(demo.getId()))
                .filter(email -> email.getApplicationId() != null)
                .forEach(email -> assertThat(
                        applications.findByIdAndUserId(email.getApplicationId(), demo.getId()))
                        .as("email %s points at a real application", email.getSubject())
                        .isPresent());
    }

    @Test
    @DisplayName("the review queue is not empty, so the demo shows what uncertainty looks like")
    void reviewQueueHasContent() {
        User demo = seed();

        var queue = review.queue(demo.getId());

        assertThat(queue.total()).isGreaterThan(0);
        assertThat(queue.uncertainEmails()).allSatisfy(item ->
                assertThat(item.confidence()).isLessThan(0.7));
    }

    @Test
    @DisplayName("the dashboard reports the seeded mail alongside the applications")
    void dashboardReflectsTheMailbox() {
        User demo = seed();

        var result = insights.forUser(demo.getId());

        assertThat(result.totals().applications()).isEqualTo(DemoDataSeeder.TARGET_APPLICATIONS);
        assertThat(result.needsReviewCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("no email body is stored - only the snippet")
    void bodiesAreNotStored() {
        User demo = seed();

        emails.findAll().stream()
                .filter(email -> email.getUserId().equals(demo.getId()))
                .forEach(email -> assertThat(email.getSnippet().length()).isLessThanOrEqualTo(200));
    }
}
