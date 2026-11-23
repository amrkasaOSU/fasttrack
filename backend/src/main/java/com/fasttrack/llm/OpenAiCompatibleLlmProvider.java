package com.fasttrack.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasttrack.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP client for any OpenAI-compatible {@code /chat/completions} endpoint.
 *
 * <p>Behaviour worth knowing:
 * <ul>
 *   <li>Retries only what is worth retrying - 408, 429 and 5xx - with
 *       exponential backoff and jitter. A 400 or 401 is a bug or a bad key, so
 *       it fails immediately.</li>
 *   <li>Never logs the API key, the prompt or the response body. Prompts
 *       contain email content.</li>
 *   <li>Reports its own latency, so the numbers in RESULTS.md are measured
 *       client-side rather than taken from the provider.</li>
 * </ul>
 */
@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmProvider.class);

    /** Longest we will wait on a provider's own instruction before giving up. */
    private static final long MAX_SERVER_DELAY_MS = 60_000;

    private final AppProperties.Llm config;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatibleLlmProvider(AppProperties props, ObjectMapper llmObjectMapper) {
        this.config = props.getLlm();
        this.mapper = llmObjectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public String model() {
        return config.getModel();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!isConfigured()) {
            throw new LlmUnavailableException("No LLM API key is configured", false);
        }

        int maxAttempts = Math.max(1, config.getMaxRetries() + 1);
        long started = System.nanoTime();
        LlmUnavailableException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = send(request);
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    long latencyMs = (System.nanoTime() - started) / 1_000_000;
                    return parse(response.body(), latencyMs, attempt);
                }

                boolean retryable = status == 408 || status == 429 || status >= 500;
                lastFailure = new LlmUnavailableException(
                        "LLM provider returned HTTP " + status, retryable);
                if (!retryable) {
                    throw lastFailure;
                }
                log.warn("LLM call failed with HTTP {} (attempt {}/{})", status, attempt, maxAttempts);

                // A rate-limited provider usually says how long to wait. Blind
                // exponential backoff caps out in seconds, while a token-budget
                // reset can be a minute away - so honouring the header is the
                // difference between waiting and silently degrading to the
                // fallback classifier.
                long serverDelay = retryAfterMillis(response);
                if (serverDelay > 0 && attempt < maxAttempts) {
                    log.info("Provider asked to wait {} ms before retrying", serverDelay);
                    sleep(Math.min(serverDelay, MAX_SERVER_DELAY_MS));
                    continue;
                }
            } catch (LlmUnavailableException ex) {
                if (!ex.isRetryable()) {
                    throw ex;
                }
                lastFailure = ex;
            } catch (java.io.IOException ex) {
                lastFailure = new LlmUnavailableException("LLM provider is unreachable", ex, true);
                log.warn("LLM call could not reach the provider (attempt {}/{}): {}",
                        attempt, maxAttempts, ex.getClass().getSimpleName());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LlmUnavailableException("LLM call was interrupted", ex, false);
            }

            if (attempt < maxAttempts) {
                backoff(attempt);
            }
        }

        throw lastFailure == null
                ? new LlmUnavailableException("LLM call failed", true)
                : lastFailure;
    }

    private HttpResponse<String> send(LlmRequest request) throws java.io.IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(config.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body(request)))
                .build();

        return http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private String body(LlmRequest request) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", config.getModel());
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxTokens());

        ArrayNode messages = payload.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userPrompt());

        if (request.jsonObject()) {
            payload.putObject("response_format").put("type", "json_object");
        }
        return payload.toString();
    }

    private LlmResponse parse(String body, long latencyMs, int attempts) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmUnavailableException("LLM response contained no choices", false);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new LlmUnavailableException("LLM returned an empty message", true);
            }
            JsonNode usage = root.path("usage");
            return new LlmResponse(
                    content,
                    root.path("model").asText(config.getModel()),
                    usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                    usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null,
                    latencyMs,
                    attempts);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new LlmUnavailableException("LLM response was not valid JSON", ex, true);
        }
    }

    /**
     * How long the provider asked us to wait, in milliseconds.
     *
     * <p>{@code Retry-After} is either a number of seconds or an HTTP date;
     * OpenAI-compatible hosts also send {@code x-ratelimit-reset-*} in forms
     * like {@code 2.5s} or {@code 1m30s}. Anything unparseable returns 0, and
     * the caller falls back to its own backoff.
     */
    private long retryAfterMillis(HttpResponse<String> response) {
        long fromRetryAfter = response.headers().firstValue("retry-after")
                .map(this::parseDelay).orElse(0L);
        if (fromRetryAfter > 0) {
            return fromRetryAfter;
        }
        return response.headers().firstValue("x-ratelimit-reset-tokens")
                .map(this::parseDelay).orElse(0L);
    }

    long parseDelay(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String trimmed = value.trim();
        try {
            // Plain seconds, the common Retry-After form.
            return (long) (Double.parseDouble(trimmed) * 1000);
        } catch (NumberFormatException ignored) {
            // Fall through to the duration forms below.
        }

        // Forms like "1m30s", "577ms", "2.5s".
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)")
                .matcher(trimmed.toLowerCase(java.util.Locale.ROOT));
        long total = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            double amount = Double.parseDouble(matcher.group(1));
            total += switch (matcher.group(2)) {
                case "ms" -> (long) amount;
                case "s" -> (long) (amount * 1000);
                case "m" -> (long) (amount * 60_000);
                case "h" -> (long) (amount * 3_600_000);
                default -> 0L;
            };
        }
        return matched ? total : 0;
    }

    /** Exponential backoff with jitter: ~250ms, ~500ms, ~1s, capped at 4s. */
    private void backoff(int attempt) {
        long base = Math.min(250L * (1L << (attempt - 1)), 4000L);
        long jitter = ThreadLocalRandom.current().nextLong(50, 200);
        sleep(base + jitter);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
