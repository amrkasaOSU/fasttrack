package com.fasttrack.application;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of one application.
 *
 * <p>Order matters: {@link #rank()} is how the state machine decides whether an
 * incoming email moves an application forward. An email that implies an
 * earlier stage than the one already recorded never moves it backwards - a
 * confirmation that arrives after an interview invite is late mail, not a
 * regression.
 *
 * <p>Nothing here infers a rejection from silence. {@link #REJECTED} is only
 * ever set by an explicit rejection email or by the user.
 */
public enum ApplicationStatus {

    APPLIED(0, false),
    APPLICATION_RECEIVED(10, false),
    UNDER_REVIEW(20, false),
    ASSESSMENT_REQUESTED(30, false),
    ASSESSMENT_COMPLETED(40, false),
    RECRUITER_CONTACT(45, false),
    INTERVIEW_REQUESTED(50, false),
    INTERVIEW_SCHEDULED(60, false),
    INTERVIEW_COMPLETED(70, false),
    FINAL_ROUND(80, false),
    OFFER_RECEIVED(90, false),
    OFFER_ACCEPTED(100, true),
    OFFER_DECLINED(100, true),

    REJECTED(-1, true),
    POSITION_CANCELLED(-1, true),
    WITHDRAWN(-1, true);

    private final int rank;
    private final boolean terminal;

    ApplicationStatus(int rank, boolean terminal) {
        this.rank = rank;
        this.terminal = terminal;
    }

    /** Progress order within the active funnel. Terminal states carry -1. */
    public int rank() {
        return rank;
    }

    /** A terminal state is only left by an explicit user action. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Statuses that mean the company has replied in some form. */
    public static final Set<ApplicationStatus> RESPONDED = EnumSet.of(
            APPLICATION_RECEIVED, UNDER_REVIEW, ASSESSMENT_REQUESTED, ASSESSMENT_COMPLETED,
            RECRUITER_CONTACT, INTERVIEW_REQUESTED, INTERVIEW_SCHEDULED, INTERVIEW_COMPLETED,
            FINAL_ROUND, OFFER_RECEIVED, OFFER_ACCEPTED, OFFER_DECLINED, REJECTED,
            POSITION_CANCELLED);

    /** Statuses that mean the application is still alive. */
    public static final Set<ApplicationStatus> ACTIVE = EnumSet.of(
            APPLIED, APPLICATION_RECEIVED, UNDER_REVIEW, ASSESSMENT_REQUESTED,
            ASSESSMENT_COMPLETED, RECRUITER_CONTACT, INTERVIEW_REQUESTED,
            INTERVIEW_SCHEDULED, INTERVIEW_COMPLETED, FINAL_ROUND, OFFER_RECEIVED);

    public static final Set<ApplicationStatus> INTERVIEW_STAGES = EnumSet.of(
            INTERVIEW_REQUESTED, INTERVIEW_SCHEDULED, INTERVIEW_COMPLETED, FINAL_ROUND);

    public static final Set<ApplicationStatus> ASSESSMENT_STAGES = EnumSet.of(
            ASSESSMENT_REQUESTED, ASSESSMENT_COMPLETED);

    public static final Set<ApplicationStatus> OFFER_STAGES = EnumSet.of(
            OFFER_RECEIVED, OFFER_ACCEPTED, OFFER_DECLINED);

    public boolean isResponse() {
        return RESPONDED.contains(this);
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
