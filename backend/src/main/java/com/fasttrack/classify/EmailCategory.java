package com.fasttrack.classify;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * What an email means for an application. These ten values are the complete
 * output space of the classifier - the LLM is constrained to them and the
 * rule-based fallback produces the same set.
 */
public enum EmailCategory {

    /** "We received your application." */
    APPLICATION_CONFIRMATION,
    /** An explicit no, however politely worded. */
    REJECTION,
    /** An online assessment / coding challenge invitation. */
    ASSESSMENT_INVITE,
    /** A request to interview, or availability request. */
    INTERVIEW_REQUEST,
    /** A confirmed interview with a date and time. */
    INTERVIEW_SCHEDULED,
    /** An offer. */
    OFFER,
    /** A recruiter reaching out, not tied to an application the user submitted. */
    RECRUITER_OUTREACH,
    /** The role was closed or cancelled without a decision on the candidate. */
    POSITION_CANCELLED,
    /** Account creation, password resets, EEO/demographic surveys, portal notices. */
    SURVEY_OR_ACCOUNT,
    /** Everything else, including job alerts and newsletters. */
    NOT_JOB_RELATED;

    /** Categories that describe a real event in an application's life. */
    public static final Set<EmailCategory> ACTIONABLE = EnumSet.of(
            APPLICATION_CONFIRMATION, REJECTION, ASSESSMENT_INVITE, INTERVIEW_REQUEST,
            INTERVIEW_SCHEDULED, OFFER, POSITION_CANCELLED);

    public boolean isActionable() {
        return ACTIONABLE.contains(this);
    }

    /** Lenient parse used when reading LLM output, which may vary in case. */
    public static Optional<EmailCategory> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (EmailCategory category : values()) {
            if (category.name().equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
