package com.fasttrack.email;

/** Whether the rule-based prefilter passed an email on to the classifier. */
public enum PrefilterResult {
    PENDING,
    KEPT,
    DROPPED
}
