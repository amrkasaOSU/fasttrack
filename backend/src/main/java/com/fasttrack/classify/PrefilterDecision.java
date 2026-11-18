package com.fasttrack.classify;

/**
 * Why an email was kept or dropped before any LLM call.
 *
 * @param keep      whether the email goes on to the classifier
 * @param rule      the rule that decided it, for metrics and debugging
 * @param reason    a short human-readable explanation
 */
public record PrefilterDecision(boolean keep, String rule, String reason) {

    public static PrefilterDecision keep(String rule, String reason) {
        return new PrefilterDecision(true, rule, reason);
    }

    public static PrefilterDecision drop(String rule, String reason) {
        return new PrefilterDecision(false, rule, reason);
    }
}
