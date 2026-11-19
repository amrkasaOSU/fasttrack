package com.fasttrack.fit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.ApplicationService;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.resume.ResumeService;
import com.fasttrack.support.ResumePdfs;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.TestBrowser;
import com.fasttrack.support.UserFixtures;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Resume upload through to a scored posting, over a real database - including
 * the paths a user hits by mistake.
 */
class FitApiIntegrationTest extends AbstractIntegrationTest {

    private static final String POSTING = """
            Software Engineer Intern - Summer 2027

            Responsibilities
            - Build and test REST APIs in Java and Spring Boot
            - Work with PostgreSQL schemas

            Requirements
            - Pursuing a Bachelor's degree in Computer Science
            - Java
            - SQL
            - Git

            Preferred qualifications
            - Spring Boot
            - Docker
            """;

    @Autowired MockMvc mockMvc;
    @Autowired ResumeService resumes;
    @Autowired FitService fit;
    @Autowired ApplicationService applications;
    @Autowired UserFixtures fixtures;

    private AppUserPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = fixtures.newPrincipal("fit");
    }

    private byte[] resumePdf() throws IOException {
        return ResumePdfs.backendResume();
    }

    private UUID uploadResume() throws IOException {
        return resumes.upload(principal.userId(), "resume.pdf", resumePdf()).id();
    }

    @Test
    @DisplayName("an uploaded resume is parsed, versioned and made active")
    void uploadParsesAndActivates() throws IOException {
        var stored = resumes.upload(principal.userId(), "resume.pdf", resumePdf());

        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.active()).isTrue();
        assertThat(stored.profile().allSkills()).isNotEmpty();
        assertThat(stored.profile().allSkills()).anyMatch(skill -> skill.contains("Java"));
        assertThat(stored.profile().education()).isNotNull();
        assertThat(stored.profile().education().expectedGraduation()).contains("2027");
    }

    @Test
    @DisplayName("a second upload becomes version 2 and the active one")
    void versionsAreKept() throws IOException {
        uploadResume();
        var second = resumes.upload(principal.userId(), "resume-v2.pdf", resumePdf());

        assertThat(second.version()).isEqualTo(2);
        assertThat(resumes.list(principal.userId())).hasSize(2);
        assertThat(resumes.list(principal.userId()).stream().filter(r -> r.active()).count()).isEqualTo(1);
        assertThat(resumes.activeResume(principal.userId()).orElseThrow().getId()).isEqualTo(second.id());
    }

    @Test
    @DisplayName("a fit check scores the active resume against a pasted posting")
    void scoresAPastedPosting() throws IOException {
        uploadResume();

        var result = fit.check(principal.userId(), POSTING, null, null);

        assertThat(result.score().overall()).isBetween(0, 100);
        assertThat(result.score().matchedRequired()).contains("Java", "SQL");
        assertThat(result.score().band()).isNotNull();
        assertThat(result.score().suggestions()).isNotEmpty();
        assertThat(result.score().scoringVersion()).isEqualTo(FitScore.VERSION);
    }

    @Test
    @DisplayName("checking without a resume says what to do instead of failing obscurely")
    void needsAResumeFirst() throws Exception {
        new TestBrowser(mockMvc).as(principal)
                .post("/api/fit", "{\"postingText\":\"" + POSTING.replace("\n", "\\n") + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Upload a resume")));
    }

    @Test
    @DisplayName("a posting too short to score is refused with a reason")
    void refusesTinyPostings() throws IOException {
        uploadResume();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> fit.check(principal.userId(), "SWE intern wanted", null, null)))
                .hasMessageContaining("too short");
    }

    @Test
    @DisplayName("a fit check can be attached to an application and read back from it")
    void linksToAnApplication() throws IOException {
        uploadResume();
        var application = applications.create(principal.userId(),
                new UpsertApplication("Acme", "Software Engineer Intern", null, null, null, null, null, null),
                ApplicationSource.MANUAL, EventActor.USER);

        var result = fit.check(principal.userId(), POSTING, null, application.id());

        assertThat(result.applicationId()).isEqualTo(application.id());
        assertThat(fit.forApplication(principal.userId(), application.id())).hasSize(1);
    }

    @Test
    @DisplayName("the same posting text is stored once, not duplicated per check")
    void postingsAreDeduplicated() throws IOException {
        uploadResume();

        var first = fit.check(principal.userId(), POSTING, null, null);
        var second = fit.check(principal.userId(), POSTING, null, null);

        assertThat(second.jobPostingId()).isEqualTo(first.jobPostingId());
        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    @DisplayName("the API exposes upload, check, history and the scoring legend")
    void apiSurface() throws Exception {
        var browser = new TestBrowser(mockMvc).as(principal);

        browser.get("/api/fit/explanation")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weights").value(org.hamcrest.Matchers.containsString("50%")))
                .andExpect(jsonPath("$.hardMismatchCap").value(40))
                .andExpect(jsonPath("$.bands.length()").value(4));

        browser.upload("/api/resumes", "resume.pdf", "application/pdf", resumePdf())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        fit.check(principal.userId(), POSTING, null, null);
        browser.get("/api/fit").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("resumes and fit checks are never visible to another user")
    void scopedToTheUser() throws Exception {
        UUID resumeId = uploadResume();
        var result = fit.check(principal.userId(), POSTING, null, null);
        var stranger = fixtures.newPrincipal("fit-stranger");
        var browser = new TestBrowser(mockMvc).as(stranger);

        browser.get("/api/resumes/" + resumeId).andExpect(status().isNotFound());
        browser.get("/api/fit/" + result.id()).andExpect(status().isNotFound());
        browser.get("/api/resumes").andExpect(jsonPath("$.length()").value(0));
        browser.get("/api/fit").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("deleting the active resume promotes the next newest")
    void deletingActivePromotesNext() throws IOException {
        UUID first = uploadResume();
        UUID second = resumes.upload(principal.userId(), "resume2.pdf", resumePdf()).id();

        resumes.delete(principal.userId(), second);

        assertThat(resumes.activeResume(principal.userId()).orElseThrow().getId()).isEqualTo(first);
    }

    @Test
    @DisplayName("corrections to the parsed profile are what later checks use")
    void correctionsAreUsed() throws IOException {
        UUID resumeId = uploadResume();
        var corrected = new com.fasttrack.resume.ResumeProfile(
                java.util.List.of("Java", "SQL", "Kotlin"), java.util.List.of("Spring Boot"),
                java.util.List.of("PostgreSQL"), java.util.List.of("Git"), java.util.List.of("AWS"),
                java.util.List.of(), java.util.List.of(),
                new com.fasttrack.resume.ResumeProfile.Education("OSU", "Bachelor of Science", "CS", "May 2027"),
                "US Citizen");

        resumes.correct(principal.userId(), resumeId, corrected);
        var result = fit.check(principal.userId(), POSTING, null, null);

        assertThat(resumes.get(principal.userId(), resumeId).profile().languages()).contains("Kotlin");
        assertThat(result.score().matchedRequired()).contains("Java", "SQL");
    }
}
