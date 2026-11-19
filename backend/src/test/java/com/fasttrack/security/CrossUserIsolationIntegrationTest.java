package com.fasttrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationService;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.classify.ClassificationMethod;
import com.fasttrack.classify.ClassificationResult;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.email.EmailClassification;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailMessage;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.EmailSource;
import com.fasttrack.email.PrefilterResult;
import com.fasttrack.fit.FitService;
import com.fasttrack.resume.ResumeService;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.ResumePdfs;
import com.fasttrack.support.TestBrowser;
import com.fasttrack.support.UserFixtures;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One user, every resource the product has, and a second user trying to reach
 * all of it.
 *
 * <p>This is the test that has to hold for the product to be shippable at
 * all: a tracker that leaks one student's rejections to another is worse than
 * no tracker. Every read is expected to 404 (not 403 - a 403 would confirm
 * the id exists) and every write is expected to change nothing.
 */
class CrossUserIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final String POSTING = """
            Software Engineer Intern - Summer 2027

            Responsibilities
            - Build REST APIs in Java and Spring Boot

            Requirements
            - Java
            - SQL
            - Pursuing a Bachelor's degree
            """;

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationService applications;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ResumeService resumes;
    @Autowired FitService fit;
    @Autowired EmailMessageRepository emails;
    @Autowired EmailClassificationRepository classifications;
    @Autowired UserFixtures fixtures;

    private AppUserPrincipal alice;
    private AppUserPrincipal mallory;

    private UUID applicationId;
    private UUID resumeId;
    private UUID fitCheckId;
    private UUID emailId;

    @BeforeEach
    void seedAlicesAccount() throws IOException {
        alice = fixtures.newPrincipal("alice-isolation");
        mallory = fixtures.newPrincipal("mallory-isolation");

        applicationId = applications.create(alice.userId(),
                new UpsertApplication("Stripe", "Software Engineer Intern", "JR-ALICE", "NYC",
                        "Greenhouse", null, null, "alice's private note"),
                ApplicationSource.MANUAL, EventActor.USER).id();

        resumeId = resumes.upload(alice.userId(), "alice.pdf", ResumePdfs.backendResume()).id();
        fitCheckId = fit.check(alice.userId(), POSTING, null, applicationId).id();

        EmailMessage email = new EmailMessage(alice.userId(), "gmail-alice-1",
                "no-reply@greenhouse-mail.io", "Your application to Stripe",
                Instant.parse("2027-02-16T12:00:00Z"), UUID.randomUUID().toString());
        email.setSnippet("alice's private email snippet");
        email.setSource(EmailSource.TEST);
        email.prefiltered(PrefilterResult.KEPT, "test");
        emailId = emails.save(email).getId();

        classifications.save(EmailClassification.from(emailId, alice.userId(),
                new ClassificationResult(EmailCategory.REJECTION, "Stripe", "SWE Intern", null,
                        null, null, null, 0.4, "unsure", ClassificationMethod.LLM,
                        "test-model", "v1", false, 10)));
    }

    private TestBrowser asMallory() {
        return new TestBrowser(mockMvc).as(mallory);
    }

    @Test
    @DisplayName("none of Alice's resources can be read by another signed-in user")
    void readsAreRefused() throws Exception {
        var mallorysBrowser = asMallory();

        mallorysBrowser.get("/api/applications/" + applicationId).andExpect(status().isNotFound());
        mallorysBrowser.get("/api/resumes/" + resumeId).andExpect(status().isNotFound());
        mallorysBrowser.get("/api/fit/" + fitCheckId).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("listing endpoints show a stranger nothing of Alice's")
    void listsAreEmptyForAStranger() throws Exception {
        var mallorysBrowser = asMallory();

        mallorysBrowser.get("/api/applications").andExpect(jsonPath("$.totalItems").value(0));
        mallorysBrowser.get("/api/resumes").andExpect(jsonPath("$.length()").value(0));
        mallorysBrowser.get("/api/fit").andExpect(jsonPath("$.length()").value(0));
        mallorysBrowser.get("/api/review").andExpect(jsonPath("$.total").value(0));
        mallorysBrowser.get("/api/insights").andExpect(jsonPath("$.totals.applications").value(0));
        mallorysBrowser.get("/api/settings").andExpect(jsonPath("$.data.applications").value(0));
    }

    @Test
    @DisplayName("no write to Alice's data succeeds, and nothing of hers changes")
    void writesAreRefusedAndChangeNothing() throws Exception {
        var mallorysBrowser = asMallory();

        mallorysBrowser.put("/api/applications/" + applicationId,
                        "{\"companyName\":\"Mallory Inc\",\"positionTitle\":\"Hijacked\"}")
                .andExpect(status().isNotFound());
        mallorysBrowser.post("/api/applications/" + applicationId + "/status",
                        "{\"status\":\"OFFER_ACCEPTED\"}")
                .andExpect(status().isNotFound());
        mallorysBrowser.post("/api/applications/" + applicationId + "/notes",
                        "{\"note\":\"seen by mallory\"}")
                .andExpect(status().isNotFound());
        mallorysBrowser.delete("/api/applications/" + applicationId).andExpect(status().isNotFound());
        mallorysBrowser.delete("/api/resumes/" + resumeId).andExpect(status().isNotFound());
        mallorysBrowser.post("/api/resumes/" + resumeId + "/activate", null)
                .andExpect(status().isNotFound());
        mallorysBrowser.post("/api/review/emails/" + emailId + "/correct",
                        "{\"category\":\"OFFER\"}")
                .andExpect(status().isNotFound());

        var stored = applicationRepository.findById(applicationId).orElseThrow();
        assertThat(stored.getCompanyName()).isEqualTo("Stripe");
        assertThat(stored.getNotes()).isEqualTo("alice's private note");
        assertThat(stored.getStatus().name()).isEqualTo("APPLIED");
        assertThat(resumes.list(alice.userId())).hasSize(1);
    }

    @Test
    @DisplayName("a stranger cannot attach their own fit check to Alice's application")
    void cannotLinkAcrossAccounts() throws Exception {
        resumes.upload(mallory.userId(), "mallory.pdf", ResumePdfs.backendResume());
        var mallorysCheck = fit.check(mallory.userId(), POSTING, null, null);

        asMallory().post("/api/fit/" + mallorysCheck.id() + "/link/" + applicationId, null)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a stranger cannot merge Alice's application into their own")
    void cannotMergeAcrossAccounts() throws Exception {
        var mallorysApplication = applications.create(mallory.userId(),
                new UpsertApplication("Datadog", "SWE Intern", null, null, null, null, null, null),
                ApplicationSource.MANUAL, EventActor.USER);

        asMallory().post("/api/applications/" + mallorysApplication.id() + "/merge",
                        "{\"sourceId\":\"" + applicationId + "\"}")
                .andExpect(status().isNotFound());

        assertThat(applicationRepository.findById(applicationId)).isPresent();
    }

    @Test
    @DisplayName("no response body ever contains another user's content")
    void noResponseLeaksContent() throws Exception {
        var mallorysBrowser = asMallory();

        for (String path : new String[]{"/api/applications", "/api/review", "/api/insights",
                "/api/fit", "/api/resumes", "/api/settings"}) {
            mallorysBrowser.get(path).andExpect(result -> {
                String body = result.getResponse().getContentAsString();
                assertThat(body)
                        .as("%s must not leak Alice's data", path)
                        .doesNotContain("alice's private note")
                        .doesNotContain("alice's private email snippet")
                        .doesNotContain("JR-ALICE")
                        .doesNotContain(alice.userId().toString());
            });
        }
    }

    @Test
    @DisplayName("deleting a stranger's account leaves Alice's data untouched")
    void deletionIsScoped() throws Exception {
        asMallory().delete("/api/settings/data").andExpect(status().isOk());

        assertThat(applicationRepository.findById(applicationId)).isPresent();
        assertThat(resumes.list(alice.userId())).hasSize(1);
        assertThat(emails.findByIdAndUserId(emailId, alice.userId())).isPresent();
    }

    @Test
    @DisplayName("Alice can still do everything with her own data")
    void theOwnerIsUnaffected() throws Exception {
        var alicesBrowser = new TestBrowser(mockMvc).as(alice);

        alicesBrowser.get("/api/applications/" + applicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.companyName").value("Stripe"));
        alicesBrowser.get("/api/resumes/" + resumeId).andExpect(status().isOk());
        alicesBrowser.get("/api/fit/" + fitCheckId).andExpect(status().isOk());
        alicesBrowser.post("/api/review/emails/" + emailId + "/correct",
                        "{\"category\":\"REJECTION\"}")
                .andExpect(status().isOk());
    }
}
