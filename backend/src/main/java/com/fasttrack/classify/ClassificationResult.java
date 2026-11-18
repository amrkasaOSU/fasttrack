package com.fasttrack.classify;

import java.time.Instant;

/**
 * The structured meaning of one email.
 *
 * @param confidence  0..1; anything below {@link #REVIEW_THRESHOLD} is queued
 *                    for human review rather than acted on silently
 */
public record ClassificationResult(
        EmailCategory category,
        String company,
        String positionTitle,
        String requisitionId,
        Instant interviewDatetime,
        Instant assessmentDeadline,
        String platform,
        double confidence,
        String reason,
        ClassificationMethod method,
        String model,
        String promptVersion,
        boolean cacheHit,
        long latencyMs) {

    /** Below this, a result goes to the Needs review queue. */
    public static final double REVIEW_THRESHOLD = 0.7;

    public boolean needsReview() {
        return confidence < REVIEW_THRESHOLD;
    }

    public ClassificationResult withCacheHit(boolean hit, long latency) {
        return new ClassificationResult(category, company, positionTitle, requisitionId,
                interviewDatetime, assessmentDeadline, platform, confidence, reason,
                method, model, promptVersion, hit, latency);
    }
}
