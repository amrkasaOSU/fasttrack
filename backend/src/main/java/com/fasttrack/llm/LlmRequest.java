package com.fasttrack.llm;

/**
 * One completion request, in provider-neutral terms.
 *
 * @param systemPrompt  instructions that frame the task
 * @param userPrompt    the content to act on
 * @param temperature   0 for the deterministic extraction this app does
 * @param maxTokens     upper bound on the reply
 * @param jsonObject    ask the provider to constrain output to a JSON object
 * @param purpose       what the call is for, recorded with the metrics
 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens,
        boolean jsonObject,
        LlmPurpose purpose) {

    public static LlmRequest json(String systemPrompt, String userPrompt, LlmPurpose purpose) {
        return new LlmRequest(systemPrompt, userPrompt, 0.0, 800, true, purpose);
    }
}
