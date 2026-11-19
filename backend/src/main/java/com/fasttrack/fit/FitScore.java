package com.fasttrack.fit;

import java.util.List;

/**
 * The full, explainable result of one fit check.
 *
 * <p>Every component is kept, not just the total, because a score with no
 * breakdown is not actionable - and because the arithmetic has to be checkable
 * by the person reading it.
 */
public record FitScore(
        int overall,
        FitBand band,
        double requiredCoverage,
        double preferredCoverage,
        double experienceScore,
        double eligibilityScore,
        double atsKeywordCoverage,
        boolean hardMismatch,
        List<String> matchedRequired,
        List<String> missingRequired,
        List<String> matchedPreferred,
        List<String> missingPreferred,
        List<String> matchedKeywords,
        List<String> missingKeywords,
        List<EligibilityCheck> eligibility,
        List<Suggestion> suggestions,
        String experienceReason,
        String weightingNote,
        String scoringVersion) {

    public static final String VERSION = "fit-v1";

    /**
     * One eligibility requirement and whether the resume meets it.
     *
     * @param hard a hard requirement caps the whole score when it fails
     */
    public record EligibilityCheck(String name, Status status, boolean hard, String detail) {

        public enum Status { PASS, FAIL, UNKNOWN }
    }

    /**
     * A suggestion the user can act on honestly.
     *
     * <p>{@link Kind#REWORD} and {@link Kind#SURFACE} only ever refer to
     * things the resume already evidences. {@link Kind#LEARN} is for genuine
     * gaps, and says how to close them - it never suggests claiming them.
     */
    public record Suggestion(Kind kind, String text) {

        public enum Kind { REWORD, SURFACE, LEARN, ELIGIBILITY }
    }
}
