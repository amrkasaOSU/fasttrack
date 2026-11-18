package com.fasttrack.llm;

/**
 * Records one LLM call for the metrics in RESULTS.md.
 *
 * <p>An interface so the offline evaluation can run the real classification
 * path without a database.
 */
public interface LlmInvocationRecorder {

    void record(LlmInvocation invocation);

    /** Discards everything - for the evaluation harness and unit tests. */
    static LlmInvocationRecorder noOp() {
        return invocation -> { };
    }
}
