package com.fasttrack.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The prefilter decides what never reaches the LLM, so both of its failure
 * modes cost something: keeping noise costs a call, dropping real mail loses
 * an application update. Both directions are tested.
 */
class EmailPrefilterTest {

    private final EmailPrefilter prefilter = new EmailPrefilter();

    @ParameterizedTest(name = "keeps mail from {0}")
    @ValueSource(strings = {
            "no-reply@greenhouse-mail.io",
            "careers@myworkday.com",
            "notifications@lever.co",
            "no-reply@ashbyhq.com",
            "talent@icims.com",
            "invites@codesignal.com",
            "no-reply@us.greenhouse-mail.io"})
    void keepsApplicantTrackingSystems(String sender) {
        var decision = prefilter.evaluate(sender, "A message about your candidacy", "Details inside.");

        assertThat(decision.keep()).isTrue();
    }

    @ParameterizedTest(name = "drops \"{0}\"")
    @CsvSource({
            "'20 new jobs for you this week'",
            "'Job alert: Software Engineer Intern'",
            "'Recommended jobs based on your profile'",
            "'Someone viewed your profile'",
            "'Register now: Spring Career Fair'",
            "'This week in tech - our newsletter'",
            "'Your weekly roundup from the ACM chapter'",
            "'Don''t forget to apply - your saved job expires soon'"})
    void dropsNoise(String subject) {
        var decision = prefilter.evaluate("jobalerts-noreply@linkedin.com", subject, "Body text here.");

        assertThat(decision.keep()).isFalse();
        assertThat(decision.rule()).isEqualTo("noise_phrase");
        assertThat(decision.reason()).isNotBlank();
    }

    @Test
    @DisplayName("a real confirmation from a job board is kept despite board-shaped wording")
    void strongSignalBeatsNoise() {
        var decision = prefilter.evaluate(
                "jobs-noreply@linkedin.com",
                "Your application was sent to Datadog",
                "Your application was sent to Datadog for Software Engineer Intern. "
                        + "See similar jobs you may like.");

        assertThat(decision.keep()).isTrue();
        assertThat(decision.rule()).isEqualTo("strong_signal");
    }

    @Test
    @DisplayName("a rejection from a company's own domain is kept on keywords alone")
    void keepsRejectionFromUnknownDomain() {
        var decision = prefilter.evaluate(
                "university-recruiting@somecompany.com",
                "An update on your candidacy",
                "After careful review we have decided to move forward with other candidates.");

        assertThat(decision.keep()).isTrue();
    }

    @Test
    @DisplayName("recruiter outreach is kept even though it never says \"application\"")
    void keepsColdOutreach() {
        var decision = prefilter.evaluate(
                "sourcing@scaleai.com",
                "Backend Engineer Intern opportunity at Scale AI",
                "I came across your profile and I'm recruiting for our summer internship.");

        assertThat(decision.keep()).isTrue();
    }

    @Test
    @DisplayName("an unrelated email with no job signal at all is dropped")
    void dropsUnrelatedMail() {
        var decision = prefilter.evaluate(
                "billing@example.com",
                "Your receipt from the campus bookstore",
                "Thanks for your purchase.");

        assertThat(decision.keep()).isFalse();
    }

    @Test
    @DisplayName("subdomains of ATS providers are recognised")
    void recognisesAtsSubdomains() {
        assertThat(prefilter.isAtsDomain("mail.greenhouse-mail.io")).isTrue();
        assertThat(prefilter.isAtsDomain("wd3.myworkday.com")).isTrue();
        assertThat(prefilter.isAtsDomain("MYWORKDAY.COM")).isTrue();
        assertThat(prefilter.isAtsDomain("notanats.com")).isFalse();
        assertThat(prefilter.isAtsDomain("")).isFalse();
        assertThat(prefilter.isAtsDomain(null)).isFalse();
    }

    @Test
    @DisplayName("null subject and body are handled without blowing up")
    void toleratesNulls() {
        var decision = prefilter.evaluate("someone@example.com", null, null);

        assertThat(decision.keep()).isFalse();
        assertThat(decision.rule()).isEqualTo("no_signal");
    }

    @Test
    @DisplayName("the decision always explains itself, for the metrics and the UI")
    void decisionsCarryReasons() {
        var kept = prefilter.evaluate("no-reply@lever.co", "Interview invitation", "Body");
        var dropped = prefilter.evaluate("alerts@linkedin.com", "Job alert", "Body");

        assertThat(kept.rule()).isNotBlank();
        assertThat(kept.reason()).isNotBlank();
        assertThat(dropped.rule()).isNotBlank();
        assertThat(dropped.reason()).isNotBlank();
        assertThat(PrefilterDecision.keep("r", "why").keep()).isTrue();
        assertThat(PrefilterDecision.drop("r", "why").keep()).isFalse();
    }
}
