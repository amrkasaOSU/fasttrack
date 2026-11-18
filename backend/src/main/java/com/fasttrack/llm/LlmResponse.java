package com.fasttrack.llm;

/**
 * What a provider returned.
 *
 * @param content           the assistant message text
 * @param model             the model that actually served the request
 * @param promptTokens      may be null: not every provider reports usage
 * @param completionTokens  may be null
 * @param latencyMs         wall-clock time of the call, measured locally
 * @param attempts          how many HTTP attempts it took (retries included)
 */
public record LlmResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs,
        int attempts) {
}
