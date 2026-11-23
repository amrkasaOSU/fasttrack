package com.fasttrack.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.classify.ClassificationJsonParser;
import com.fasttrack.classify.ClassificationResult;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.classify.EmailClassificationService;
import com.fasttrack.classify.EmailPrefilter;
import com.fasttrack.classify.NoOpClassificationCache;
import com.fasttrack.classify.PrefilterDecision;
import com.fasttrack.classify.PromptLibrary;
import com.fasttrack.classify.RuleBasedClassifier;
import com.fasttrack.config.AppProperties;
import com.fasttrack.config.JsonMapper;
import com.fasttrack.llm.LlmInvocationRecorder;
import com.fasttrack.llm.LlmProvider;
import com.fasttrack.llm.LlmRequest;
import com.fasttrack.llm.LlmResponse;
import com.fasttrack.llm.LlmUnavailableException;
import com.fasttrack.llm.OpenAiCompatibleLlmProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The classifier evaluation that produces the numbers in RESULTS.md.
 *
 * <p>Run it with:
 * <pre>
 *   cd backend &amp;&amp; mvn -Peval test -Dtest=ClassifierEvaluationTest
 * </pre>
 *
 * <p>With no {@code OPENAI_API_KEY} in the environment it measures the
 * deterministic rule-based classifier - the baseline and the production
 * fallback. With a key set it measures the LLM path against the same 200
 * emails, writing a separate report, so the two are directly comparable.
 *
 * <p>Caching is disabled here on purpose: every email must actually be
 * classified, or the latency and accuracy numbers would be meaningless.
 */
@Tag("eval")
class ClassifierEvaluationTest {

    private static final Path EVAL_DIR = Path.of("..", "eval");
    private static final Path DATASET = EVAL_DIR.resolve("emails.jsonl");

    private final ObjectMapper mapper = JsonMapper.build();
    private final EmailPrefilter prefilter = new EmailPrefilter();
    private final RuleBasedClassifier rules = new RuleBasedClassifier();

    /**
     * Pause between model calls, in milliseconds.
     *
     * <p>Free tiers rate limit by requests per minute. Without a pause the run
     * collects 429s, the provider falls back to rules, and the report would
     * then be measuring the fallback while claiming to measure the model -
     * the worst possible outcome for an evaluation. Set {@code EVAL_THROTTLE_MS}
     * to space the calls out; `scripts/run-llm-eval.sh` sets a sensible value.
     */
    private static final long THROTTLE_MS = Long.parseLong(
            System.getenv().getOrDefault("EVAL_THROTTLE_MS", "0"));

    @Test
    @DisplayName("evaluate the classifier against the 200-email labeled set")
    void evaluate() throws IOException {
        List<EvalEmail> dataset = sample(loadDataset());
        if (System.getenv("EVAL_SAMPLE") == null) {
            assertThat(dataset).as("evaluation set").hasSize(200);
        }

        // ---------------------------------------------------------- prefilter
        int droppedCorrectly = 0;
        int droppedWrongly = 0;
        int kept = 0;
        List<String> prefilterMistakes = new ArrayList<>();

        for (EvalEmail email : dataset) {
            PrefilterDecision decision =
                    prefilter.evaluate(email.sender(), email.subject(), email.body());
            if (decision.keep()) {
                kept++;
                if (email.expectsPrefilterDrop()) {
                    prefilterMistakes.add(email.id() + " kept but should drop (" + decision.rule() + ")");
                }
            } else {
                if (email.expectsPrefilterDrop()) {
                    droppedCorrectly++;
                } else {
                    droppedWrongly++;
                    prefilterMistakes.add(email.id() + " dropped but is "
                            + email.category() + " (" + decision.rule() + ")");
                }
            }
        }

        int totalDropped = dataset.size() - kept;
        double dropRate = (double) totalDropped / dataset.size();
        double prefilterAccuracy =
                (double) (droppedCorrectly + (kept - countKeptMistakes(dataset))) / dataset.size();

        // --------------------------------------------------------- classifier
        LlmProvider rawProvider = buildProviderIfConfigured();
        CountingProvider provider = rawProvider == null ? null : new CountingProvider(rawProvider);
        boolean usingLlm = provider != null && provider.isConfigured();
        EmailClassificationService classifier = usingLlm
                ? new EmailClassificationService(provider, new PromptLibrary(),
                        new ClassificationJsonParser(mapper), rules,
                        new NoOpClassificationCache(), LlmInvocationRecorder.noOp())
                : null;

        ClassificationMetrics classifierOnly = new ClassificationMetrics();
        ClassificationMetrics pipeline = new ClassificationMetrics();
        ClassificationMetrics tricky = new ClassificationMetrics();
        // Only the emails the model itself answered. Without this, a run with
        // fallbacks reports a blend and calls it the model's score.
        ClassificationMetrics modelOnly = new ClassificationMetrics();
        List<String> misclassified = new ArrayList<>();
        int fellBackToRules = 0;
        int flaggedForReview = 0;
        int errorsFlagged = 0;
        int errorsUnflagged = 0;
        double confidenceSum = 0;

        int processed = 0;
        for (EvalEmail email : dataset) {
            if (usingLlm && THROTTLE_MS > 0 && processed > 0) {
                sleepQuietly(THROTTLE_MS);
            }
            if (usingLlm && ++processed % 25 == 0) {
                System.out.printf("  ... %d/%d classified%n", processed, dataset.size());
            }
            ClassificationResult result = usingLlm
                    ? classifier.classify(email.toContent(), null, false)
                    : rules.classify(email.toContent());
            if (usingLlm && result.method() != com.fasttrack.classify.ClassificationMethod.LLM) {
                fellBackToRules++;
            }

            confidenceSum += result.confidence();
            if (result.needsReview()) {
                flaggedForReview++;
            }
            classifierOnly.record(email.category(), result.category(), result.latencyMs());
            if (usingLlm && result.method() == com.fasttrack.classify.ClassificationMethod.LLM) {
                modelOnly.record(email.category(), result.category(), result.latencyMs());
            }
            if (email.isTricky()) {
                tricky.record(email.category(), result.category(), result.latencyMs());
            }

            // The pipeline is what production actually does: an email the
            // prefilter drops is never classified, so it is as good as
            // NOT_JOB_RELATED. Mail the labels say should be dropped - job
            // alerts, password resets - is scored against that same target,
            // because "correctly ignored" is the right answer for it.
            PrefilterDecision decision =
                    prefilter.evaluate(email.sender(), email.subject(), email.body());
            EmailCategory pipelinePrediction =
                    decision.keep() ? result.category() : EmailCategory.NOT_JOB_RELATED;
            EmailCategory pipelineLabel =
                    email.expectsPrefilterDrop() ? EmailCategory.NOT_JOB_RELATED : email.category();
            pipeline.record(pipelineLabel, pipelinePrediction, result.latencyMs());

            if (pipelinePrediction != pipelineLabel) {
                if (result.needsReview()) {
                    errorsFlagged++;
                } else {
                    errorsUnflagged++;
                }
                misclassified.add(String.format("%s | expected %s, got %s | %s\"%s\" | %s",
                        email.id(), pipelineLabel, pipelinePrediction,
                        decision.keep() ? "" : "[prefilter dropped: " + decision.rule() + "] ",
                        email.subject(), email.note()));
            }
        }

        String method = usingLlm ? "llm" : "rules";
        String report = buildReport(method, usingLlm, provider, dataset.size(), kept, totalDropped,
                dropRate, prefilterAccuracy, droppedWrongly, prefilterMistakes,
                classifierOnly, pipeline, tricky, fellBackToRules, misclassified,
                flaggedForReview, errorsFlagged, errorsUnflagged, confidenceSum / dataset.size(),
                provider, modelOnly);

        Path output = EVAL_DIR.resolve("results").resolve("classifier-" + method + ".md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);

        System.out.println(report);
        System.out.println("Report written to " + output.toAbsolutePath().normalize());

        // Regression guards, set below the measured values so a real
        // regression fails the build but normal variation does not.
        assertThat(pipeline.accuracy())
                .as("end-to-end accuracy (prefilter + classifier)")
                .isGreaterThanOrEqualTo(0.80);
        assertThat(pipeline.macroF1())
                .as("macro F1 across all ten categories")
                .isGreaterThanOrEqualTo(0.70);
        assertThat(dropRate)
                .as("share of the set the prefilter removes before any LLM call")
                .isGreaterThanOrEqualTo(0.08);
    }

    private int countKeptMistakes(List<EvalEmail> dataset) {
        int mistakes = 0;
        for (EvalEmail email : dataset) {
            PrefilterDecision decision =
                    prefilter.evaluate(email.sender(), email.subject(), email.body());
            if (decision.keep() && email.expectsPrefilterDrop()) {
                mistakes++;
            }
        }
        return mistakes;
    }

    /** Builds the HTTP provider only when a key is present in the environment. */
    private LlmProvider buildProviderIfConfigured() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        AppProperties props = new AppProperties();
        props.getLlm().setApiKey(apiKey);
        // More retries than production uses: a rate-limited call that falls
        // back to rules would be recorded as an LLM result, which would make
        // the whole measurement dishonest.
        props.getLlm().setMaxRetries(4);
        props.getLlm().setTimeoutSeconds(60);
        String baseUrl = System.getenv("OPENAI_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            props.getLlm().setBaseUrl(baseUrl);
        }
        String model = System.getenv("LLM_MODEL");
        if (model != null && !model.isBlank()) {
            props.getLlm().setModel(model);
        }
        return new OpenAiCompatibleLlmProvider(props, mapper);
    }

    /**
     * Optionally trims the set to {@code EVAL_SAMPLE} emails, keeping each
     * category's share of the whole.
     *
     * <p>This exists because free tiers meter tokens per day: the full set
     * costs more tokens than a day's allowance, so without a way to measure a
     * representative slice there would be no measurement at all. A random cut
     * would skew the rare categories, so the sample is stratified.
     */
    private List<EvalEmail> sample(List<EvalEmail> all) {
        String requested = System.getenv("EVAL_SAMPLE");
        if (requested == null || requested.isBlank()) {
            return all;
        }
        int target = Integer.parseInt(requested.trim());
        if (target >= all.size()) {
            return all;
        }

        var byCategory = new java.util.LinkedHashMap<EmailCategory, List<EvalEmail>>();
        all.forEach(email -> byCategory
                .computeIfAbsent(email.category(), key -> new ArrayList<>()).add(email));

        List<EvalEmail> chosen = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            int share = Math.max(1, Math.round((float) entry.getValue().size() * target / all.size()));
            chosen.addAll(entry.getValue().subList(0, Math.min(share, entry.getValue().size())));
        }
        System.out.printf("EVAL_SAMPLE=%d -> %d emails, stratified across %d categories%n",
                target, chosen.size(), byCategory.size());
        return chosen;
    }

    private List<EvalEmail> loadDataset() throws IOException {
        assertThat(Files.exists(DATASET))
                .as("eval/emails.jsonl - regenerate with python3 scripts/generate_eval_set.py")
                .isTrue();
        List<EvalEmail> emails = new ArrayList<>();
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                emails.add(mapper.readValue(line, EvalEmail.class));
            }
        }
        return emails;
    }

    /**
     * Wraps the real provider to record why calls fail.
     *
     * <p>A fallback count on its own is not actionable: "30 of 200 fell back"
     * could be rate limiting, a model that cannot hold a JSON contract, or a
     * network blip, and the fix differs for each. The service deliberately
     * swallows these exceptions - that is its job - so the evaluation
     * observes them here instead.
     */
    private static final class CountingProvider implements LlmProvider {

        private final LlmProvider delegate;
        final Map<String, Integer> failures = new LinkedHashMap<>();
        int calls;

        CountingProvider(LlmProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isConfigured() {
            return delegate.isConfigured();
        }

        @Override
        public String model() {
            return delegate.model();
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            try {
                return delegate.complete(request);
            } catch (LlmUnavailableException ex) {
                failures.merge(summarise(ex.getMessage()), 1, Integer::sum);
                throw ex;
            }
        }

        /** Groups messages so the report shows causes, not 30 unique strings. */
        private String summarise(String message) {
            if (message == null) {
                return "unknown";
            }
            var matcher = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(message);
            if (matcher.find()) {
                String code = matcher.group(1);
                String meaning = switch (code) {
                    case "400" -> "400 (provider rejected the request - often strict JSON mode)";
                    case "401", "403" -> code + " (auth)";
                    case "429" -> "429 (rate limited)";
                    default -> code.startsWith("5") ? code + " (provider error)" : code;
                };
                return "HTTP " + meaning;
            }
            return message.length() > 80 ? message.substring(0, 80) : message;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sub-millisecond values are reported honestly rather than as "0 ms". */
    private String ms(long value) {
        return value == 0 ? "<1 ms" : value + " ms";
    }

    private String buildReport(String method, boolean usingLlm, LlmProvider provider,
                               int total, int kept, int dropped, double dropRate,
                               double prefilterAccuracy, int droppedWrongly,
                               List<String> prefilterMistakes,
                               ClassificationMetrics classifierOnly,
                               ClassificationMetrics pipeline,
                               ClassificationMetrics tricky,
                               int fellBackToRules,
                               List<String> misclassified,
                               int flaggedForReview,
                               int errorsFlagged,
                               int errorsUnflagged,
                               double meanConfidence,
                               CountingProvider counted,
                               ClassificationMetrics modelOnly) {

        StringBuilder out = new StringBuilder();
        out.append("# Classifier evaluation - ").append(usingLlm ? "LLM path" : "rule-based path").append("\n\n");
        out.append("Generated ").append(DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().atOffset(ZoneOffset.UTC).toInstant())).append("\n\n");
        out.append("| Setting | Value |\n|---|---|\n");
        out.append("| Dataset | `eval/emails.jsonl`, ").append(total)
           .append(System.getenv("EVAL_SAMPLE") == null
                   ? " labeled synthetic emails |\n"
                   : " labeled synthetic emails (stratified sample) |\n");
        out.append("| Method | ").append(usingLlm ? "LLM with rule-based fallback" : "rule-based only (no API key set)").append(" |\n");
        if (usingLlm) {
            out.append("| Model | `").append(provider.model()).append("` |\n");
            out.append("| Prompt | `prompts/classify_v1.txt` (version ")
               .append(PromptLibrary.CLASSIFY_VERSION).append(") |\n");
        }
        out.append("| Cache | disabled for this run |\n");
        out.append("| Command | `mvn -Peval test -Dtest=ClassifierEvaluationTest` |\n\n");

        out.append("## Prefilter\n\n");
        out.append("| Measure | Value |\n|---|---:|\n");
        out.append(String.format("| Emails in set | %d |%n", total));
        out.append(String.format("| Kept for classification | %d |%n", kept));
        out.append(String.format("| Removed before any LLM call | %d (%.1f%%) |%n", dropped, dropRate * 100));
        out.append(String.format("| Prefilter agreement with labels | %.1f%% |%n", prefilterAccuracy * 100));
        out.append(String.format("| Job-related mail dropped by mistake | %d |%n%n", droppedWrongly));
        if (!prefilterMistakes.isEmpty()) {
            out.append("Disagreements:\n\n");
            prefilterMistakes.forEach(mistake -> out.append("- ").append(mistake).append('\n'));
            out.append('\n');
        }

        out.append("## End-to-end accuracy (prefilter + classifier)\n\n");
        out.append("This is what production does: a dropped email is never classified.\n\n");
        out.append(String.format("- Overall accuracy: **%.1f%%** (%d/%d)%n",
                pipeline.accuracy() * 100, Math.round(pipeline.accuracy() * pipeline.total()), pipeline.total()));
        out.append(String.format("- Macro F1: **%.3f**%n%n", pipeline.macroF1()));
        out.append(pipeline.perCategoryTable()).append('\n');
        out.append("### Confusion matrix\n\n").append(pipeline.confusionMatrix()).append('\n');

        out.append("## Classifier alone (every email classified)\n\n");
        out.append(String.format("- Overall accuracy: **%.1f%%**%n", classifierOnly.accuracy() * 100));
        out.append(String.format("- Macro F1: **%.3f**%n", classifierOnly.macroF1()));
        out.append(String.format("- Accuracy on the %d emails marked tricky: **%.1f%%**%n",
                tricky.total(), tricky.accuracy() * 100));
        if (usingLlm) {
            out.append(String.format("- Fell back to rules (invalid or unavailable): **%d of %d**%n",
                    fellBackToRules, classifierOnly.total()));
        }
        out.append('\n');

        if (usingLlm && fellBackToRules > 0) {
            out.append("## Why calls fell back to the rule classifier\n\n");
            out.append(String.format(
                    "**%d of %d classifications did not come from the model.** The figures above "
                    + "are therefore a blend: %d from `%s` and %d from the deterministic "
                    + "classifier. Treat the headline accuracy as the pipeline's, not the "
                    + "model's.%n%n",
                    fellBackToRules, total, total - fellBackToRules, counted.model(), fellBackToRules));
            out.append("| Cause | Calls |\n|---|---:|\n");
            counted.failures.forEach((cause, count) ->
                    out.append(String.format("| %s | %d |%n", cause, count)));
            out.append(String.format("%n%d HTTP calls were made in total (retries included).%n%n",
                    counted.calls));

            if (modelOnly.total() > 0) {
                out.append("### The model's own score, on the calls it served\n\n");
                out.append(String.format(
                        "Over the %d emails `%s` actually answered - the number to quote for the "
                        + "model, rather than the blended figure above.%n%n",
                        modelOnly.total(), counted.model()));
                out.append("| Measure | Value |\n|---|---:|\n");
                out.append(String.format("| Accuracy | **%.1f%%** (%d/%d) |%n",
                        modelOnly.accuracy() * 100,
                        Math.round(modelOnly.accuracy() * modelOnly.total()), modelOnly.total()));
                out.append(String.format("| Macro F1 | **%.3f** |%n%n", modelOnly.macroF1()));
            }
        }

        out.append("## Confidence and the review queue\n\n");
        out.append("A result below ").append(ClassificationResult.REVIEW_THRESHOLD)
           .append(" confidence is not acted on silently - it goes to the Needs review queue.\n\n");
        out.append("| Measure | Value |\n|---|---:|\n");
        out.append(String.format("| Mean confidence | %.3f |%n", meanConfidence));
        out.append(String.format("| Sent to review | %d of %d (%.1f%%) |%n",
                flaggedForReview, total, 100.0 * flaggedForReview / total));
        int errors = errorsFlagged + errorsUnflagged;
        out.append(String.format("| Errors caught by the review queue | %d of %d |%n", errorsFlagged, errors));
        out.append(String.format("| Errors that passed as confident | %d of %d |%n%n", errorsUnflagged, errors));

        out.append("## Latency\n\n");
        out.append("Measured client-side, per email, with the cache disabled.\n\n");
        out.append("| Measure | Value |\n|---|---:|\n");
        out.append(String.format("| Mean | %s |%n", ms(classifierOnly.averageLatencyMs())));
        out.append(String.format("| p50 | %s |%n", ms(classifierOnly.percentileLatencyMs(50))));
        out.append(String.format("| p95 | %s |%n", ms(classifierOnly.percentileLatencyMs(95))));
        out.append(String.format("| p99 | %s |%n%n", ms(classifierOnly.percentileLatencyMs(99))));
        if (!usingLlm) {
            out.append("The rule-based path does string matching only, so every "
                    + "measurement rounds to under a millisecond.\n\n");
        }

        out.append("## Every disagreement\n\n");
        if (misclassified.isEmpty()) {
            out.append("None.\n\n");
        } else {
            out.append("Each line is one email the pipeline got wrong, with the label, the "
                    + "prediction and why the case is in the set.\n\n```\n");
            misclassified.forEach(line -> out.append(line).append('\n'));
            out.append("```\n\n");
        }

        out.append("## How to read this\n\n");
        out.append("The emails are synthetic and written by the generator in "
                + "`scripts/generate_eval_set.py`; ");
        out.append("they imitate the templates real ATS platforms send, including the "
                + "cases that are genuinely hard (polite rejections, confirmations that "
                + "mention interviews, job alerts that name a company you applied to). ");
        out.append("Because the text is templated, these numbers are an upper bound on "
                + "real-world performance for the rule-based path, which keys on exactly "
                + "those phrases. The live-accuracy figure computed from user corrections "
                + "is the honest counterpart.\n");

        return out.toString();
    }
}
