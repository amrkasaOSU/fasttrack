package com.fasttrack.classify;

/** How a classification was produced. */
public enum ClassificationMethod {
    /** A language model produced and validated the structured result. */
    LLM,
    /** The deterministic rule-based classifier (no key, or the LLM failed). */
    RULE,
    /** The user corrected it by hand. */
    MANUAL
}
