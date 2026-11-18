package com.fasttrack.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.classify.EmailCategory;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The rules that decide what an email does to an application.
 *
 * <p>Two of these tests exist because of the failure mode that matters most:
 * an application being marked rejected when it was not.
 */
class ApplicationStateMachineTest {

    private final ApplicationStateMachine machine = new ApplicationStateMachine();

    @ParameterizedTest(name = "{0} email moves a fresh application to {1}")
    @CsvSource({
            "APPLICATION_CONFIRMATION, APPLICATION_RECEIVED",
            "ASSESSMENT_INVITE,        ASSESSMENT_REQUESTED",
            "RECRUITER_OUTREACH,       RECRUITER_CONTACT",
            "INTERVIEW_REQUEST,        INTERVIEW_REQUESTED",
            "INTERVIEW_SCHEDULED,      INTERVIEW_SCHEDULED",
            "OFFER,                    OFFER_RECEIVED",
            "REJECTION,                REJECTED",
            "POSITION_CANCELLED,       POSITION_CANCELLED"
    })
    void movesForwardFromApplied(EmailCategory category, ApplicationStatus expected) {
        StatusTransition transition = machine.apply(ApplicationStatus.APPLIED, category);

        assertThat(transition.applied()).isTrue();
        assertThat(transition.to()).isEqualTo(expected);
        assertThat(transition.reason()).isNotBlank();
    }

    @ParameterizedTest(name = "{0} never changes the status")
    @EnumSource(value = EmailCategory.class, names = {"SURVEY_OR_ACCOUNT", "NOT_JOB_RELATED"})
    void nonActionableCategoriesDoNothing(EmailCategory category) {
        StatusTransition transition = machine.apply(ApplicationStatus.UNDER_REVIEW, category);

        assertThat(transition.applied()).isFalse();
        assertThat(transition.to()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
    }

    @Test
    @DisplayName("a confirmation arriving after an interview invite does not move the status back")
    void lateMailDoesNotRegress() {
        StatusTransition transition = machine.apply(
                ApplicationStatus.INTERVIEW_SCHEDULED, EmailCategory.APPLICATION_CONFIRMATION);

        assertThat(transition.applied()).isFalse();
        assertThat(transition.to()).isEqualTo(ApplicationStatus.INTERVIEW_SCHEDULED);
        assertThat(transition.reason()).contains("not ahead of");
    }

    @Test
    @DisplayName("an assessment invite after an interview was scheduled does not regress either")
    void assessmentAfterInterviewDoesNotRegress() {
        StatusTransition transition = machine.apply(
                ApplicationStatus.INTERVIEW_SCHEDULED, EmailCategory.ASSESSMENT_INVITE);

        assertThat(transition.applied()).isFalse();
    }

    @Test
    @DisplayName("a rejection is accepted from any live stage, including the day before an interview")
    void rejectionAlwaysAppliesToLiveApplications() {
        for (ApplicationStatus status : ApplicationStatus.ACTIVE) {
            StatusTransition transition = machine.apply(status, EmailCategory.REJECTION);

            assertThat(transition.applied())
                    .as("rejection from %s", status)
                    .isTrue();
            assertThat(transition.to()).isEqualTo(ApplicationStatus.REJECTED);
        }
    }

    @Test
    @DisplayName("nothing in the state machine can produce REJECTED without a rejection email")
    void silenceIsNeverARejection() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            for (EmailCategory category : EmailCategory.values()) {
                StatusTransition transition = machine.apply(status, category);
                if (transition.to() == ApplicationStatus.REJECTED && transition.applied()) {
                    assertThat(category)
                            .as("only a rejection email may produce REJECTED (was %s from %s)", category, status)
                            .isEqualTo(EmailCategory.REJECTION);
                }
            }
        }
    }

    @ParameterizedTest(name = "an email cannot change a {0} application")
    @EnumSource(value = ApplicationStatus.class,
            names = {"REJECTED", "POSITION_CANCELLED", "WITHDRAWN", "OFFER_ACCEPTED", "OFFER_DECLINED"})
    void terminalStatesAreSticky(ApplicationStatus terminal) {
        for (EmailCategory category : EmailCategory.values()) {
            StatusTransition transition = machine.apply(terminal, category);

            assertThat(transition.applied()).isFalse();
            assertThat(transition.to()).isEqualTo(terminal);
        }
    }

    @Test
    @DisplayName("a rejection after an offer was accepted is ignored, not applied")
    void rejectionAfterAcceptedOfferIsIgnored() {
        StatusTransition transition = machine.apply(ApplicationStatus.OFFER_ACCEPTED, EmailCategory.REJECTION);

        assertThat(transition.applied()).isFalse();
        assertThat(transition.reason()).contains("only you can change it");
    }

    @Test
    @DisplayName("null inputs are rejected rather than silently treated as 'no change'")
    void nullsAreRejected() {
        assertThatThrownBy(() -> machine.apply(null, EmailCategory.OFFER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.apply(ApplicationStatus.APPLIED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the funnel ranks are strictly increasing, which is what 'forward only' relies on")
    void funnelRanksAreOrdered() {
        ApplicationStatus[] funnel = {
                ApplicationStatus.APPLIED, ApplicationStatus.APPLICATION_RECEIVED,
                ApplicationStatus.UNDER_REVIEW, ApplicationStatus.ASSESSMENT_REQUESTED,
                ApplicationStatus.ASSESSMENT_COMPLETED, ApplicationStatus.INTERVIEW_REQUESTED,
                ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEW_COMPLETED,
                ApplicationStatus.FINAL_ROUND, ApplicationStatus.OFFER_RECEIVED};

        for (int i = 1; i < funnel.length; i++) {
            assertThat(funnel[i].rank())
                    .as("%s must rank above %s", funnel[i], funnel[i - 1])
                    .isGreaterThan(funnel[i - 1].rank());
        }
        assertThat(machine.initialStatus()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    @DisplayName("status groups agree with each other")
    void statusGroupsAreConsistent() {
        assertThat(ApplicationStatus.REJECTED.isResponse()).isTrue();
        assertThat(ApplicationStatus.REJECTED.isActive()).isFalse();
        assertThat(ApplicationStatus.APPLIED.isResponse()).isFalse();
        assertThat(ApplicationStatus.APPLIED.isActive()).isTrue();
        assertThat(Arrays.stream(ApplicationStatus.values()).filter(ApplicationStatus::isTerminal).count())
                .isEqualTo(5);
        assertThat(machine.isUserSettable(ApplicationStatus.WITHDRAWN)).isTrue();
        assertThat(EmailCategory.REJECTION.isActionable()).isTrue();
        assertThat(EmailCategory.NOT_JOB_RELATED.isActionable()).isFalse();
        assertThat(EmailCategory.parse("rejection")).contains(EmailCategory.REJECTION);
        assertThat(EmailCategory.parse("interview-request")).contains(EmailCategory.INTERVIEW_REQUEST);
        assertThat(EmailCategory.parse("nonsense")).isEmpty();
        assertThat(EmailCategory.parse(null)).isEmpty();
    }
}
