package com.fasttrack.llm;

/**
 * The only way the rest of the application talks to a language model.
 *
 * <p>One interface, one HTTP implementation speaking the OpenAI
 * {@code /chat/completions} shape. Groq's free tier, Ollama, OpenRouter,
 * llama.cpp and vLLM all speak it, so switching providers is an environment
 * variable, and tests point it at WireMock. No vendor SDK is on the classpath
 * (DECISIONS.md D-003).
 */
public interface LlmProvider {

    /** False when no API key is configured; callers then use their fallback path. */
    boolean isConfigured();

    /** The configured model name, for display and for the metrics table. */
    String model();

    /**
     * Sends one completion request.
     *
     * @throws LlmUnavailableException when the provider is unconfigured,
     *         unreachable, rate limited or returns an error status
     */
    LlmResponse complete(LlmRequest request);
}
