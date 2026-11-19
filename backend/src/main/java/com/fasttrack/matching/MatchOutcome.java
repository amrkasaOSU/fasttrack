package com.fasttrack.matching;

import com.fasttrack.application.JobApplication;

/**
 * What the matcher decided for one classified email.
 *
 * @param application  the application this email belongs to
 * @param created      true when no existing application matched
 * @param needsReview  true when the match was plausible but not certain
 * @param rule         which matching rule fired, for the review queue and metrics
 * @param reason       a sentence the user can read
 */
public record MatchOutcome(
        JobApplication application,
        boolean created,
        boolean needsReview,
        String rule,
        String reason) {
}
