package com.fasttrack.classify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deterministic classifier - production's fallback and the baseline the
 * LLM is measured against. The cases here are the ones that decide whether a
 * live application is wrongly marked dead.
 */
class RuleBasedClassifierTest {

    private final RuleBasedClassifier classifier = new RuleBasedClassifier();

    private ClassificationResult classify(String subject, String body) {
        return classify("no-reply@greenhouse-mail.io", "Acme Recruiting", subject, body);
    }

    private ClassificationResult classify(String sender, String senderName, String subject, String body) {
        return classifier.classify(new EmailContent(sender, senderName, subject, body,
                Instant.parse("2027-02-16T12:00:00Z")));
    }

    @Test
    @DisplayName("a polite rejection is a rejection, not a confirmation")
    void politeRejection() {
        var result = classify("Your application to Stripe",
                "Thank you for your interest in the Software Engineer Intern position. "
                + "After careful review, we have decided to move forward with other candidates.");

        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
        assertThat(result.method()).isEqualTo(ClassificationMethod.RULE);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    @DisplayName("a confirmation that mentions interviews in passing is still a confirmation")
    void confirmationMentioningInterviews() {
        var result = classify("We received your application - Backend Engineer Intern",
                "This is confirmation that your application has been received. "
                + "Candidates selected for interviews will be contacted directly.");

        assertThat(result.category()).isEqualTo(EmailCategory.APPLICATION_CONFIRMATION);
    }

    @Test
    @DisplayName("an assessment invitation is not read as an interview request")
    void assessmentBeatsInterviewWording() {
        var result = classify("Next steps for your application",
                "As the next step, please complete a HackerRank coding challenge within 5 days.");

        assertThat(result.category()).isEqualTo(EmailCategory.ASSESSMENT_INVITE);
        assertThat(result.platform()).isEqualTo("HackerRank");
    }

    @Test
    @DisplayName("a confirmed interview outranks a generic interview invitation")
    void scheduledBeatsRequest() {
        var result = classify("Interview confirmation",
                "Your interview is scheduled for Tuesday, March 9 at 10:30 AM Eastern.");

        assertThat(result.category()).isEqualTo(EmailCategory.INTERVIEW_SCHEDULED);
    }

    @Test
    @DisplayName("an offer is an offer even when the body also says \"unfortunately\"")
    void offerWithHedging() {
        var result = classify("Your offer from Figma",
                "We are pleased to offer you the Software Engineer Intern position. "
                + "Unfortunately the start date cannot move.");

        assertThat(result.category()).isEqualTo(EmailCategory.OFFER);
    }

    @Test
    @DisplayName("a job alert is never treated as an application update")
    void jobAlert() {
        var result = classify("alerts@linkedin.com", "LinkedIn Job Alerts",
                "20 new jobs for \"software engineer intern\"",
                "Stripe - Software Engineer Intern. Complete your application before Friday.");

        assertThat(result.category()).isEqualTo(EmailCategory.NOT_JOB_RELATED);
    }

    @Test
    @DisplayName("portal wording does not turn a decision email into account noise")
    void portalWordingDoesNotWin() {
        var offer = classify("Action required: review your offer",
                "An offer for Data Engineer Intern is ready for your review in the candidate portal.");
        var confirmation = classify("Application received",
                "We received your application. You can check its status in the candidate portal.");

        assertThat(offer.category()).isNotEqualTo(EmailCategory.SURVEY_OR_ACCOUNT);
        assertThat(confirmation.category()).isEqualTo(EmailCategory.APPLICATION_CONFIRMATION);
    }

    @Test
    @DisplayName("an EEO survey is account noise, not an application event")
    void eeoSurvey() {
        var result = classify("Voluntary self-identification",
                "We invite all applicants to complete a voluntary self-identification form. "
                + "Your responses have no effect on your application.");

        assertThat(result.category()).isEqualTo(EmailCategory.SURVEY_OR_ACCOUNT);
    }

    @Test
    @DisplayName("cold outreach is separated from mail about an application you made")
    void recruiterOutreach() {
        var result = classify("sourcing@scaleai.com", "Jordan at Scale AI",
                "Backend Engineer Intern opportunity at Scale AI",
                "I came across your profile and thought you might be a great fit.");

        assertThat(result.category()).isEqualTo(EmailCategory.RECRUITER_OUTREACH);
    }

    @Test
    @DisplayName("an ambiguous email gets low confidence so it lands in the review queue")
    void ambiguousGetsLowConfidence() {
        var result = classify("Update", "Unfortunately things have changed on our end.");

        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
        assertThat(result.confidence()).isLessThan(ClassificationResult.REVIEW_THRESHOLD);
        assertThat(result.needsReview()).isTrue();
    }

    @Test
    @DisplayName("an email that matches nothing is NOT_JOB_RELATED with low confidence")
    void noMatch() {
        var result = classify("Lunch tomorrow?", "Are you free at noon?");

        assertThat(result.category()).isEqualTo(EmailCategory.NOT_JOB_RELATED);
        assertThat(result.needsReview()).isTrue();
    }

    @Test
    @DisplayName("company, position and requisition id are pulled out when they are stated")
    void extraction() {
        var result = classify("no-reply@greenhouse-mail.io", "Datadog Careers",
                "Your application for Software Engineer Intern - Summer 2027",
                "Thank you for applying. Requisition ID: JR884412.");

        assertThat(result.company()).isEqualTo("Datadog");
        assertThat(result.positionTitle()).contains("Software Engineer Intern");
        assertThat(result.requisitionId()).isEqualTo("JR884412");
    }

    @Test
    @DisplayName("nothing is invented: a sparse email yields nulls, not guesses")
    void noHallucinatedFields() {
        var result = classify("no-reply@myworkday.com", null, "Application update", "Please log in.");

        assertThat(result.positionTitle()).isNull();
        assertThat(result.requisitionId()).isNull();
        assertThat(result.interviewDatetime()).isNull();
        assertThat(result.assessmentDeadline()).isNull();
    }

    @Test
    @DisplayName("the shared ATS domain is never used as the company name")
    void sharedDomainIsNotACompany() {
        var result = classify("no-reply@greenhouse-mail.io", null, "Application received", "Thanks.");

        assertThat(result.company()).isNotEqualTo("Greenhouse-mail");
        assertThat(result.company()).isNull();
    }

    @Test
    @DisplayName("a position cancellation is distinct from a rejection")
    void cancellationIsNotRejection() {
        var result = classify("Update on the Platform Engineer Intern role",
                "The requisition has been closed due to a change in our hiring plan. "
                + "This is not a reflection of your candidacy.");

        assertThat(result.category()).isEqualTo(EmailCategory.POSITION_CANCELLED);
    }
}
