package com.fasttrack.classify;

import com.fasttrack.llm.LlmInvocation;
import com.fasttrack.llm.LlmInvocationRecorder;
import com.fasttrack.llm.LlmProvider;
import com.fasttrack.llm.LlmPurpose;
import com.fasttrack.llm.LlmRequest;
import com.fasttrack.llm.LlmResponse;
import com.fasttrack.llm.LlmUnavailableException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Classifies one email, with every degradation path the product promises.
 *
 * <pre>
 *   cache hit ─────────────────────────────────► result (0 calls)
 *   no LLM key ────────────────────────────────► rule-based result
 *   LLM returns valid JSON ────────────────────► result, cached
 *   LLM returns invalid JSON ──► retry once ───► result, cached
 *                                  └─ invalid ─► rule-based result
 *   LLM unreachable / rate limited ────────────► rule-based result
 * </pre>
 *
 * <p>The user never sees a failure here: a classification always comes back,
 * and the {@code method} field says how it was produced. Low-confidence
 * results are flagged for review rather than acted on silently.
 */
@Service
public class EmailClassificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailClassificationService.class);

    /** Appended on the retry when the first reply was not valid JSON. */
    private static final String RETRY_REMINDER = """

            Your previous reply could not be parsed. Reply with ONLY a JSON \
            object matching the required shape - no prose, no markdown, no code \
            fences.""";

    private final LlmProvider llm;
    private final PromptLibrary prompts;
    private final ClassificationJsonParser parser;
    private final RuleBasedClassifier rules;
    private final ClassificationCache cache;
    private final LlmInvocationRecorder invocations;

    public EmailClassificationService(LlmProvider llm,
                                      PromptLibrary prompts,
                                      ClassificationJsonParser parser,
                                      RuleBasedClassifier rules,
                                      ClassificationCache cache,
                                      LlmInvocationRecorder invocations) {
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
        this.rules = rules;
        this.cache = cache;
        this.invocations = invocations;
    }

    /** Classifies without recording metrics - used by the offline evaluation. */
    public ClassificationResult classify(EmailContent email) {
        return classify(email, null, true);
    }

    public ClassificationResult classify(EmailContent email, UUID userId) {
        return classify(email, userId, true);
    }

    public ClassificationResult classify(EmailContent email, UUID userId, boolean useCache) {
        String hash = email.contentHash();
        String model = llm.model();

        if (useCache) {
            var cached = cache.get(hash, PromptLibrary.CLASSIFY_VERSION, model);
            if (cached.isPresent()) {
                return cached.get().withCacheHit(true, 0);
            }
        }

        if (!llm.isConfigured()) {
            return rules.classify(email);
        }

        LlmInvocation invocation = new LlmInvocation(
                userId, LlmPurpose.CLASSIFY_EMAIL, model, PromptLibrary.CLASSIFY_VERSION);
        long started = System.nanoTime();
        boolean retried = false;

        try {
            String system = prompts.classifySystemPrompt();
            String user = prompts.classifyUserPrompt(email);

            LlmResponse response = llm.complete(
                    LlmRequest.json(system, user, LlmPurpose.CLASSIFY_EMAIL));

            ClassificationResult result;
            try {
                result = parser.parse(response.content(), response.model(),
                        PromptLibrary.CLASSIFY_VERSION, response.latencyMs());
            } catch (InvalidClassificationException first) {
                // One retry, with an explicit reminder. Models that drift into
                // prose almost always come back correctly the second time.
                retried = true;
                log.info("Classifier reply was invalid ({}), retrying once", first.getMessage());
                LlmResponse second = llm.complete(
                        LlmRequest.json(system, user + RETRY_REMINDER, LlmPurpose.CLASSIFY_EMAIL));
                result = parser.parse(second.content(), second.model(),
                        PromptLibrary.CLASSIFY_VERSION, second.latencyMs());
                response = second;
            }

            invocations.record(invocation.succeeded(
                    elapsedMs(started), response.promptTokens(), response.completionTokens(), retried));

            if (useCache) {
                cache.put(hash, PromptLibrary.CLASSIFY_VERSION, model, result);
            }
            return result;

        } catch (InvalidClassificationException ex) {
            log.warn("Classifier reply was invalid twice; using rules instead: {}", ex.getMessage());
            invocations.record(invocation.failed(elapsedMs(started), retried));
            return fallback(email);
        } catch (LlmUnavailableException ex) {
            log.warn("LLM unavailable ({}); using rules instead", ex.getMessage());
            invocations.record(invocation.failed(elapsedMs(started), retried));
            return fallback(email);
        }
    }

    private ClassificationResult fallback(EmailContent email) {
        return rules.classify(email);
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
