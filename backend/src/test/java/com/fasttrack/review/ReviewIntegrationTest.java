package com.fasttrack.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.JobApplication;
import com.fasttrack.classify.ClassificationMethod;
import com.fasttrack.classify.ClassificationResult;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.email.EmailClassification;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailCorrectionRepository;
import com.fasttrack.email.EmailMessage;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.EmailSource;
import com.fasttrack.email.PrefilterResult;
import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.TestBrowser;
import com.fasttrack.support.UserFixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The review queue and corrections: what lands in the queue, what a correction
 * changes, and how the live-accuracy figure is derived.
 */
class ReviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ReviewService review;
    @Autowired EmailMessageRepository emails;
    @Autowired EmailClassificationRepository classifications;
    @Autowired EmailCorrectionRepository corrections;
    @Autowired ApplicationRepository applications;
    @Autowired UserFixtures fixtures;

    private AppUserPrincipal principal;
    private UUID userId;

    @BeforeEach
    void setUp() {
        principal = fixtures.newPrincipal("review");
        userId = principal.userId();
    }

    private EmailMessage storeEmail(String subject, String sender) {
        EmailMessage email = new EmailMessage(userId, "g-" + UUID.randomUUID(), sender, subject,
                Instant.parse("2027-02-16T12:00:00Z"), UUID.randomUUID().toString());
        email.setSenderName("Stripe Recruiting");
        email.setSnippet("snippet");
        email.setSource(EmailSource.TEST);
        email.prefiltered(PrefilterResult.KEPT, "test");
        return emails.save(email);
    }

    private EmailClassification classify(EmailMessage email, EmailCategory category, double confidence,
                                         String company, String position) {
        ClassificationResult result = new ClassificationResult(category, company, position, null,
                null, null, null, confidence, "because", ClassificationMethod.LLM,
                "test-model", "v1", false, 120);
        return classifications.save(EmailClassification.from(email.getId(), userId, result));
    }

    @Test
    @DisplayName("a low-confidence classification shows up in the queue with its reason")
    void lowConfidenceLandsInTheQueue() {
        EmailMessage email = storeEmail("Update on your application", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.REJECTION, 0.42, "Stripe", "SWE Intern");

        var queue = review.queue(userId);

        assertThat(queue.total()).isEqualTo(1);
        assertThat(queue.uncertainEmails()).singleElement().satisfies(item -> {
            assertThat(item.subject()).isEqualTo("Update on your application");
            assertThat(item.confidence()).isEqualTo(0.42);
            assertThat(item.category()).isEqualTo(EmailCategory.REJECTION);
            assertThat(item.reason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("a confident classification does not clutter the queue")
    void confidentClassificationsAreNotQueued() {
        EmailMessage email = storeEmail("Rejection", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.REJECTION, 0.95, "Stripe", "SWE Intern");

        assertThat(review.queue(userId).total()).isZero();
    }

    @Test
    @DisplayName("correcting a category records the correction and rewrites the classification")
    void correctionIsRecorded() {
        EmailMessage email = storeEmail("Your offer from Figma", "no-reply@ashbyhq.com");
        classify(email, EmailCategory.REJECTION, 0.55, "Figma", "Product Engineer Intern");

        review.correct(userId, email.getId(), EmailCategory.OFFER, "Figma", "Product Engineer Intern");

        var stored = classifications.findByEmailId(email.getId()).orElseThrow();
        assertThat(stored.getCategory()).isEqualTo(EmailCategory.OFFER);
        assertThat(stored.getMethod()).isEqualTo(ClassificationMethod.MANUAL);
        assertThat(stored.isNeedsReview()).isFalse();

        assertThat(corrections.findTop50ByUserIdOrderByCreatedAtDesc(userId)).singleElement()
                .satisfies(correction -> {
                    assertThat(correction.getOriginalCategory()).isEqualTo(EmailCategory.REJECTION);
                    assertThat(correction.getCorrectedCategory()).isEqualTo(EmailCategory.OFFER);
                    assertThat(correction.changedCategory()).isTrue();
                    assertThat(correction.isWasLlm()).isTrue();
                });
    }

    @Test
    @DisplayName("a correction re-derives the application's status")
    void correctionUpdatesTheApplication() {
        EmailMessage email = storeEmail("Your offer from Figma", "no-reply@ashbyhq.com");
        classify(email, EmailCategory.APPLICATION_CONFIRMATION, 0.5, "Figma", "Product Engineer Intern");
        review.correct(userId, email.getId(), EmailCategory.APPLICATION_CONFIRMATION, "Figma", "Product Engineer Intern");

        JobApplication application = applications.findAllByUserIdOrderByCreatedAtDesc(userId).getFirst();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPLICATION_RECEIVED);

        review.correct(userId, email.getId(), EmailCategory.OFFER, "Figma", "Product Engineer Intern");

        JobApplication updated = applications.findById(application.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ApplicationStatus.OFFER_RECEIVED);
        assertThat(updated.isNeedsReview()).isFalse();
    }

    @Test
    @DisplayName("correcting an email to not-job-related unlinks it from the application")
    void correctingToNotJobRelatedDetaches() {
        EmailMessage email = storeEmail("Application received", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.APPLICATION_CONFIRMATION, 0.5, "Stripe", "SWE Intern");
        review.correct(userId, email.getId(), EmailCategory.APPLICATION_CONFIRMATION, "Stripe", "SWE Intern");
        assertThat(emails.findById(email.getId()).orElseThrow().getApplicationId()).isNotNull();

        review.correct(userId, email.getId(), EmailCategory.NOT_JOB_RELATED, null, null);

        assertThat(emails.findById(email.getId()).orElseThrow().getApplicationId()).isNull();
    }

    @Test
    @DisplayName("correcting the company moves the email to the right application")
    void correctingCompanyRelinks() {
        EmailMessage email = storeEmail("Application received", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.APPLICATION_CONFIRMATION, 0.5, "Strype", "SWE Intern");
        review.correct(userId, email.getId(), EmailCategory.APPLICATION_CONFIRMATION, "Strype", "SWE Intern");
        UUID wrongApplication = emails.findById(email.getId()).orElseThrow().getApplicationId();

        review.correct(userId, email.getId(), EmailCategory.APPLICATION_CONFIRMATION, "Stripe", "SWE Intern");

        UUID rightApplication = emails.findById(email.getId()).orElseThrow().getApplicationId();
        assertThat(rightApplication).isNotEqualTo(wrongApplication);
        assertThat(applications.findById(rightApplication).orElseThrow().getCompanyName()).isEqualTo("Stripe");
    }

    @Test
    @DisplayName("live accuracy is reported with the caveat that makes it honest")
    void liveAccuracyIsCaveated() {
        EmailMessage first = storeEmail("One", "no-reply@greenhouse-mail.io");
        classify(first, EmailCategory.REJECTION, 0.9, "Stripe", "SWE Intern");
        EmailMessage second = storeEmail("Two", "no-reply@greenhouse-mail.io");
        classify(second, EmailCategory.REJECTION, 0.5, "Datadog", "Data Engineer Intern");

        review.correct(userId, second.getId(), EmailCategory.OFFER, "Datadog", "Data Engineer Intern");

        var accuracy = review.liveAccuracy(userId);

        assertThat(accuracy.classifications()).isEqualTo(2);
        assertThat(accuracy.categoryCorrections()).isEqualTo(1);
        assertThat(accuracy.uncorrectedShare()).isEqualTo(0.5);
        assertThat(accuracy.caveat()).contains("not a verified accuracy");
    }

    @Test
    @DisplayName("with nothing classified yet, live accuracy is null rather than a flattering 100%")
    void noDataMeansNoNumber() {
        var accuracy = review.liveAccuracy(userId);

        assertThat(accuracy.classifications()).isZero();
        assertThat(accuracy.uncorrectedShare()).isNull();
    }

    @Test
    @DisplayName("the queue and corrections are scoped to the signed-in user")
    void scopedToTheUser() throws Exception {
        EmailMessage email = storeEmail("Mine", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.REJECTION, 0.4, "Stripe", "SWE Intern");
        var stranger = fixtures.newPrincipal("review-stranger");

        assertThat(review.queue(stranger.userId()).total()).isZero();

        new TestBrowser(mockMvc).as(stranger)
                .post("/api/review/emails/" + email.getId() + "/correct",
                        "{\"category\":\"OFFER\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the API exposes the queue, a correction, and the accuracy figure")
    void apiSurface() throws Exception {
        EmailMessage email = storeEmail("Unclear", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.REJECTION, 0.4, "Stripe", "SWE Intern");
        var browser = new TestBrowser(mockMvc).as(principal);

        browser.get("/api/review")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.uncertainEmails[0].subject").value("Unclear"));

        browser.post("/api/review/emails/" + email.getId() + "/correct",
                        "{\"category\":\"INTERVIEW_REQUEST\",\"company\":\"Stripe\",\"positionTitle\":\"SWE Intern\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("INTERVIEW_REQUEST"));

        browser.get("/api/review/accuracy")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCorrections").value(1))
                .andExpect(jsonPath("$.caveat").isNotEmpty());

        browser.get("/api/review").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("an invalid category is rejected before anything is recorded")
    void invalidCategoryRejected() throws Exception {
        EmailMessage email = storeEmail("Unclear", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.REJECTION, 0.4, "Stripe", "SWE Intern");

        new TestBrowser(mockMvc).as(principal)
                .post("/api/review/emails/" + email.getId() + "/correct", "{\"category\":\"NONSENSE\"}")
                .andExpect(status().isBadRequest());

        assertThat(corrections.countByUserId(userId)).isZero();
    }

    @Test
    @DisplayName("an application the matcher was unsure about appears in the queue too")
    void uncertainApplicationsAppear() {
        EmailMessage email = storeEmail("Application received", "no-reply@greenhouse-mail.io");
        classify(email, EmailCategory.APPLICATION_CONFIRMATION, 0.9, "Snowflake", "Data Engineer Intern");
        review.correct(userId, email.getId(), EmailCategory.APPLICATION_CONFIRMATION,
                "Snowflake", "Data Engineer Intern");

        JobApplication application = applications.findAllByUserIdOrderByCreatedAtDesc(userId).getFirst();
        application.flagForReview("Titles only overlap 50%");
        applications.save(application);

        var queue = review.queue(userId);

        assertThat(queue.uncertainApplications()).singleElement().satisfies(item -> {
            assertThat(item.companyName()).isEqualTo("Snowflake");
            assertThat(item.reason()).contains("50%");
        });
    }
}
