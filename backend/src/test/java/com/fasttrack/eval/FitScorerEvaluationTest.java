package com.fasttrack.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.config.JsonMapper;
import com.fasttrack.fit.FitBand;
import com.fasttrack.fit.FitScore;
import com.fasttrack.fit.ExperienceRater;
import com.fasttrack.fit.FitScoreCalculator;
import com.fasttrack.fit.SkillDictionary;
import com.fasttrack.posting.HeuristicPostingParser;
import com.fasttrack.posting.PostingRequirements;
import com.fasttrack.resume.ResumeProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Does the scorer put a resume/posting pair in the band a human would?
 *
 * <pre>
 *   cd backend &amp;&amp; mvn -Peval test
 * </pre>
 *
 * <p>Two things are measured, and the second matters more than the first:
 * <ol>
 *   <li><b>Band agreement</b> with 20 hand-labeled pairs, plus how far off the
 *       misses are (one band away is a judgement call; two is a bug).</li>
 *   <li><b>Honesty.</b> No suggestion may ever tell the user to add a skill,
 *       a technology or a number their resume does not already evidence. This
 *       is asserted across every pair, against every suggestion.</li>
 *   </ol>
 *
 * <p>The experience component is supplied by the deterministic fallback
 * rather than a model, so the run is reproducible without an API key.
 */
@Tag("eval")
class FitScorerEvaluationTest {

    private static final Path EVAL_DIR = Path.of("..", "eval");
    private static final Path DATASET = EVAL_DIR.resolve("fit_pairs.jsonl");

    private final ObjectMapper mapper = JsonMapper.build();
    private final SkillDictionary dictionary = new SkillDictionary();
    private final HeuristicPostingParser postingParser = new HeuristicPostingParser(dictionary);
    private final FitScoreCalculator calculator = new FitScoreCalculator(dictionary);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pair(String id, ResumeProfile resume, @JsonProperty("posting_text") String postingText,
                @JsonProperty("expected_band") FitBand expectedBand, String note) {}

    @Test
    @DisplayName("score 20 labeled resume/posting pairs and check every suggestion for honesty")
    void evaluate() throws IOException {
        List<Pair> pairs = load();
        assertThat(pairs).hasSize(20);

        int exact = 0;
        int withinOne = 0;
        List<String> rows = new ArrayList<>();
        List<String> dishonest = new ArrayList<>();
        List<String> misses = new ArrayList<>();

        for (Pair pair : pairs) {
            PostingRequirements posting = postingParser.parse(pair.postingText());
            var rating = heuristicRating(pair.resume(), posting);
            FitScore score = calculator.score(pair.resume(), posting, rating, resumeText(pair.resume()));

            int distance = Math.abs(score.band().ordinal() - pair.expectedBand().ordinal());
            if (distance == 0) {
                exact++;
            }
            if (distance <= 1) {
                withinOne++;
            } else {
                misses.add(String.format("%s | expected %s, scored %d (%s) | %s",
                        pair.id(), pair.expectedBand(), score.overall(), score.band(), pair.note()));
            }

            rows.add(String.format("| %s | %s | %d | %s | %.0f%% | %.0f%% | %.2f | %s |",
                    pair.id(), pair.expectedBand(), score.overall(), score.band(),
                    score.requiredCoverage() * 100, score.preferredCoverage() * 100,
                    score.experienceScore(),
                    distance == 0 ? "match" : distance == 1 ? "one band" : "**two bands**"));

            dishonest.addAll(checkHonesty(pair, score));
        }

        double exactRate = (double) exact / pairs.size();
        double withinOneRate = (double) withinOne / pairs.size();

        String report = buildReport(pairs.size(), exact, exactRate, withinOne, withinOneRate,
                rows, misses, dishonest);

        Path output = EVAL_DIR.resolve("results").resolve("fit-scorer.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(dishonest)
                .as("suggestions that would have the user claim something their resume does not show")
                .isEmpty();
        assertThat(exactRate).as("exact band agreement").isGreaterThanOrEqualTo(0.60);
        assertThat(withinOneRate).as("within one band").isGreaterThanOrEqualTo(0.90);
    }

    /**
     * The honesty check. A suggestion fails if it tells the user to put
     * something on their resume that the resume does not already evidence.
     */
    private List<String> checkHonesty(Pair pair, FitScore score) {
        List<String> failures = new ArrayList<>();
        String resumeEvidence = (String.join(" ", pair.resume().allSkills()) + " "
                + pair.resume().narrative()).toLowerCase(Locale.ROOT);

        for (FitScore.Suggestion suggestion : score.suggestions()) {
            String text = suggestion.text().toLowerCase(Locale.ROOT);

            boolean tellsUserToAdd = text.contains("add ") || text.contains("include ")
                    || text.contains("put ") || text.contains("list ") || text.contains("mention ");
            boolean aboutQuantifying = text.contains("quantif") || text.contains("metric")
                    || text.contains("percentage") || text.contains("number of");

            if (suggestion.kind() == FitScore.Suggestion.Kind.LEARN) {
                // A learning suggestion may name a skill the resume lacks -
                // that is the point - but it must not ask for it to be claimed.
                if (tellsUserToAdd && (text.contains("resume") || text.contains("cv"))) {
                    failures.add(pair.id() + " | LEARN suggestion asks for a missing skill to be "
                            + "added to the resume: " + suggestion.text());
                }
                continue;
            }

            if (aboutQuantifying && !resumeEvidence.matches(".*\\d.*")) {
                failures.add(pair.id() + " | suggests adding numbers to a resume with none: "
                        + suggestion.text());
            }

            // A SURFACE or REWORD suggestion must only name skills the resume has.
            for (String missing : score.missingRequired()) {
                if (text.contains(missing.toLowerCase(Locale.ROOT)) && tellsUserToAdd) {
                    failures.add(pair.id() + " | suggests adding \"" + missing
                            + "\", which the resume does not show: " + suggestion.text());
                }
            }
        }
        return failures;
    }

    /**
     * The production fallback itself, not a copy of it - so the evaluation
     * cannot drift away from what the app actually does.
     */
    private final ExperienceRater rater = new ExperienceRater(null, null, null, null, dictionary);

    private FitScoreCalculator.ExperienceRating heuristicRating(ResumeProfile resume,
                                                                PostingRequirements posting) {
        String narrative = resume.narrative();
        if (narrative.isBlank()) {
            return FitScoreCalculator.ExperienceRating.of(0, "No experience or projects to judge.");
        }
        return rater.heuristic(narrative, posting);
    }

    private String resumeText(ResumeProfile resume) {
        return String.join("\n", resume.allSkills()) + "\n" + resume.narrative();
    }

    private String buildReport(int total, int exact, double exactRate, int withinOne,
                               double withinOneRate, List<String> rows, List<String> misses,
                               List<String> dishonest) {
        StringBuilder out = new StringBuilder();
        out.append("# Fit scorer evaluation\n\n");
        out.append("Generated ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append("\n\n");
        out.append("| Setting | Value |\n|---|---|\n");
        out.append("| Dataset | `eval/fit_pairs.jsonl`, ").append(total)
           .append(" hand-labeled resume/posting pairs |\n");
        out.append("| Posting parsing | heuristic (no API key needed) |\n");
        out.append("| Experience component | deterministic word-overlap fallback |\n");
        out.append("| Weights | ").append(FitScoreCalculator.weightsDescription()).append(" |\n");
        out.append("| Command | `mvn -Peval test` |\n\n");

        out.append("## Band agreement\n\n");
        out.append(String.format("- Exact band: **%d/%d (%.0f%%)**%n", exact, total, exactRate * 100));
        out.append(String.format("- Within one band: **%d/%d (%.0f%%)**%n%n", withinOne, total, withinOneRate * 100));

        out.append("| Pair | Expected | Score | Band | Required | Preferred | Experience | Agreement |\n");
        out.append("|---|---|---:|---|---:|---:|---:|---|\n");
        rows.forEach(row -> out.append(row).append('\n'));
        out.append('\n');

        out.append("## Pairs more than one band out\n\n");
        if (misses.isEmpty()) {
            out.append("None.\n\n");
        } else {
            out.append("```\n");
            misses.forEach(miss -> out.append(miss).append('\n'));
            out.append("```\n\n");
        }

        out.append("## Honesty check\n\n");
        out.append("Every suggestion across all ").append(total)
           .append(" pairs was checked against one rule: a suggestion may never tell the user to "
                 + "claim a skill, technology or number their resume does not already evidence. "
                 + "Suggestions about genuine gaps must propose learning, not listing.\n\n");
        if (dishonest.isEmpty()) {
            out.append("**No violations.**\n");
        } else {
            out.append("**Violations found:**\n\n```\n");
            dishonest.forEach(line -> out.append(line).append('\n'));
            out.append("```\n");
        }
        return out.toString();
    }

    private List<Pair> load() throws IOException {
        assertThat(Files.exists(DATASET))
                .as("eval/fit_pairs.jsonl - regenerate with python3 scripts/generate_fit_pairs.py")
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
