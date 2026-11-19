package com.fasttrack.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.config.JsonMapper;
import com.fasttrack.matching.ApplicationMatcher;
import com.fasttrack.matching.TextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Duplicate-detection quality on 100 labeled pairs, and the sweep that chooses
 * the similarity threshold.
 *
 * <pre>
 *   cd backend &amp;&amp; mvn -Peval test
 * </pre>
 *
 * <p>The threshold is not picked by taste: this test scores every candidate
 * value and writes the table into the report, so the value in
 * {@link ApplicationMatcher#SIMILARITY_THRESHOLD} can be justified from data.
 *
 * <p>Precision and recall are reported for the "these are the same
 * application" decision, because that is the error that costs most: a false
 * positive silently merges two real applications and loses one of them from
 * the tracker, while a false negative only shows a duplicate the user can
 * merge in one click.
 */
@Tag("eval")
class MatcherEvaluationTest {

    private static final Path EVAL_DIR = Path.of("..", "eval");
    private static final Path DATASET = EVAL_DIR.resolve("matcher_pairs.jsonl");

    private final ObjectMapper mapper = JsonMapper.build();
    private final TextNormalizer normalizer = new TextNormalizer();
    private final ApplicationMatcher matcher = new ApplicationMatcher(null, normalizer);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Side(String company, String position, @JsonProperty("requisition_id") String requisitionId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pair(String id, Side a, Side b, boolean same, String note) {}

    /** Counts for one threshold setting. */
    private record Scores(int truePositives, int falsePositives, int trueNegatives, int falseNegatives) {

        double precision() {
            int predicted = truePositives + falsePositives;
            return predicted == 0 ? 0 : (double) truePositives / predicted;
        }

        double recall() {
            int actual = truePositives + falseNegatives;
            return actual == 0 ? 0 : (double) truePositives / actual;
        }

        double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0 : 2 * p * r / (p + r);
        }

        double accuracy() {
            int total = truePositives + falsePositives + trueNegatives + falseNegatives;
            return total == 0 ? 0 : (double) (truePositives + trueNegatives) / total;
        }
    }

    @Test
    @DisplayName("evaluate duplicate detection on 100 labeled pairs")
    void evaluate() throws IOException {
        List<Pair> pairs = load();
        assertThat(pairs).hasSize(100);

        Scores atProduction = score(pairs, ApplicationMatcher.SIMILARITY_THRESHOLD);
        List<String> mistakes = mistakes(pairs, ApplicationMatcher.SIMILARITY_THRESHOLD);

        StringBuilder report = new StringBuilder();
        report.append("# Matcher evaluation - duplicate detection\n\n");
        report.append("Generated ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append("\n\n");
        report.append("| Setting | Value |\n|---|---|\n");
        report.append("| Dataset | `eval/matcher_pairs.jsonl`, 100 labeled pairs |\n");
        report.append("| Positive class | \"these two are the same application\" |\n");
        report.append("| Threshold in production | ")
              .append(ApplicationMatcher.SIMILARITY_THRESHOLD).append(" |\n");
        report.append("| Command | `mvn -Peval test` |\n\n");

        report.append("## At the configured threshold\n\n");
        report.append("| Measure | Value |\n|---|---:|\n");
        report.append(String.format("| Precision | **%.3f** |%n", atProduction.precision()));
        report.append(String.format("| Recall | **%.3f** |%n", atProduction.recall()));
        report.append(String.format("| F1 | **%.3f** |%n", atProduction.f1()));
        report.append(String.format("| Accuracy | **%.1f%%** |%n", atProduction.accuracy() * 100));
        report.append(String.format("| False merges (worst error) | **%d** |%n", atProduction.falsePositives()));
        report.append(String.format("| Missed duplicates | %d |%n%n", atProduction.falseNegatives()));

        report.append("## Threshold sweep\n\n");
        report.append("Every candidate value scored against the same 100 pairs. "
                + "A false positive merges two real applications and loses one; "
                + "a false negative shows a duplicate the user can merge in one click. "
                + "The chosen value is the one that removes false merges while keeping recall high.\n\n");
        report.append("| Threshold | Precision | Recall | F1 | False merges | Missed duplicates |\n");
        report.append("|---:|---:|---:|---:|---:|---:|\n");

        double best = ApplicationMatcher.SIMILARITY_THRESHOLD;
        double bestF1 = -1;
        for (int step = 30; step <= 90; step += 5) {
            double threshold = step / 100.0;
            Scores scores = score(pairs, threshold);
            if (scores.f1() > bestF1) {
                bestF1 = scores.f1();
                best = threshold;
            }
            report.append(String.format("| %.2f%s | %.3f | %.3f | %.3f | %d | %d |%n",
                    threshold,
                    Math.abs(threshold - ApplicationMatcher.SIMILARITY_THRESHOLD) < 0.001 ? " **(in use)**" : "",
                    scores.precision(), scores.recall(), scores.f1(),
                    scores.falsePositives(), scores.falseNegatives()));
        }
        report.append(String.format("%nBest F1 in this sweep: %.3f at threshold %.2f.%n%n", bestF1, best));

        report.append("## Every disagreement at the configured threshold\n\n");
        if (mistakes.isEmpty()) {
            report.append("None.\n");
        } else {
            report.append("```\n");
            mistakes.forEach(line -> report.append(line).append('\n'));
            report.append("```\n");
        }

        Path output = EVAL_DIR.resolve("results").resolve("matcher.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Report written to " + output.toAbsolutePath().normalize());

        // Regression guards. A false merge is the expensive error, so
        // precision is held to a higher bar than recall.
        assertThat(atProduction.precision()).as("precision - false merges lose data").isGreaterThanOrEqualTo(0.90);
        assertThat(atProduction.recall()).as("recall").isGreaterThanOrEqualTo(0.75);
        assertThat(atProduction.f1()).as("F1").isGreaterThanOrEqualTo(0.85);
    }

    private Scores score(List<Pair> pairs, double threshold) {
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        for (Pair pair : pairs) {
            boolean predicted = predictSame(pair, threshold);
            if (pair.same() && predicted) {
                tp++;
            } else if (!pair.same() && predicted) {
                fp++;
            } else if (!pair.same()) {
                tn++;
            } else {
                fn++;
            }
        }
        return new Scores(tp, fp, tn, fn);
    }

    private List<String> mistakes(List<Pair> pairs, double threshold) {
        List<String> lines = new ArrayList<>();
        for (Pair pair : pairs) {
            boolean predicted = predictSame(pair, threshold);
            if (predicted != pair.same()) {
                var comparison = matcher.compare(
                        pair.a().company(), pair.a().position(), pair.a().requisitionId(),
                        pair.b().company(), pair.b().position(), pair.b().requisitionId());
                lines.add(String.format("%s | labeled %s, predicted %s (%s, score %.2f) | \"%s\" vs \"%s\" | %s",
                        pair.id(),
                        pair.same() ? "SAME" : "DIFFERENT",
                        predicted ? "SAME" : "DIFFERENT",
                        comparison.rule(), comparison.score(),
                        pair.a().position(), pair.b().position(), pair.note()));
            }
        }
        return lines;
    }

    /**
     * Applies production's rules at an arbitrary threshold. The rule order is
     * the matcher's own; only the numeric cut-off is varied.
     */
    private boolean predictSame(Pair pair, double threshold) {
        var comparison = matcher.compare(
                pair.a().company(), pair.a().position(), pair.a().requisitionId(),
                pair.b().company(), pair.b().position(), pair.b().requisitionId());

        if (comparison.rule().equals("requisition_id")) {
            return true;
        }
        if (comparison.rule().equals("different_requisition_id")
                || comparison.rule().equals("different_company")
                || comparison.rule().equals("no_company")) {
            return false;
        }
        if (comparison.rule().equals("exact_title")) {
            return true;
        }
        return comparison.score() >= threshold;
    }

    private List<Pair> load() throws IOException {
        assertThat(Files.exists(DATASET))
                .as("eval/matcher_pairs.jsonl - regenerate with python3 scripts/generate_matcher_pairs.py")
                .isTrue();
        List<Pair> pairs = new ArrayList<>();
        for (String line : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                pairs.add(mapper.readValue(line, Pair.class));
            }
        }
        return pairs;
    }
}
