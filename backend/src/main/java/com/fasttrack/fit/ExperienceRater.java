package com.fasttrack.fit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.classify.PromptLibrary;
import com.fasttrack.llm.LlmInvocation;
import com.fasttrack.llm.LlmInvocationRecorder;
import com.fasttrack.llm.LlmProvider;
import com.fasttrack.llm.LlmPurpose;
import com.fasttrack.llm.LlmRequest;
import com.fasttrack.llm.LlmResponse;
import com.fasttrack.llm.LlmUnavailableException;
import com.fasttrack.posting.PostingRequirements;
import com.fasttrack.resume.ResumeProfile;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rates how relevant someone's actual work is to a posting - the one part of
 * the fit score a model decides, worth 15%.
 *
 * <p>Without a model, a deterministic fallback measures how much of the
 * posting's vocabulary appears in the candidate's own experience and project
 * text. That is cruder, and the reason string says so, so a user never
 * mistakes the fallback for a judgement.
 */
@Service
public class ExperienceRater {

    private static final Logger log = LoggerFactory.getLogger(ExperienceRater.class);
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9+#.-]{2,}");

    /** Bounds for the no-model estimate: weak evidence, bounded influence. */
    private static final double FLOOR = 0.30;
    private static final double CEILING = 0.80;
    private static final double NEUTRAL = 0.50;
    private static final double SATURATION = 0.40;

    private final LlmProvider llm;
    private final PromptLibrary prompts;
    private final ObjectMapper mapper;
    private final LlmInvocationRecorder invocations;
    private final SkillDictionary dictionary;

    public ExperienceRater(LlmProvider llm,
                           PromptLibrary prompts,
                           ObjectMapper llmObjectMapper,
                           LlmInvocationRecorder invocations,
                           SkillDictionary dictionary) {
        this.llm = llm;
        this.prompts = prompts;
        this.mapper = llmObjectMapper;
        this.invocations = invocations;
        this.dictionary = dictionary;
    }

    public FitScoreCalculator.ExperienceRating rate(ResumeProfile resume,
                                                    PostingRequirements posting,
                                                    UUID userId) {
        String narrative = resume.narrative();
        if (narrative.isBlank()) {
            return FitScoreCalculator.ExperienceRating.of(0,
                    "Your resume's experience and projects could not be read, so relevance "
                    + "could not be judged - add them in the review step to score this part.");
        }
        if (!llm.isConfigured()) {
            return heuristic(narrative, posting);
        }

        LlmInvocation invocation = new LlmInvocation(
                userId, LlmPurpose.RATE_EXPERIENCE, llm.model(), PromptLibrary.EXPERIENCE_VERSION);
        long started = System.nanoTime();

        try {
            LlmResponse response = llm.complete(LlmRequest.json(
                    prompts.experienceSystemPrompt(),
                    prompts.experienceUserPrompt(
                            posting.title() == null ? "(not stated)" : posting.title(),
                            String.join("\n", posting.responsibilitiesOrEmpty()),
                            narrative),
                    LlmPurpose.RATE_EXPERIENCE));

            JsonNode root = mapper.readTree(strip(response.content()));
            JsonNode score = root.get("score");
            if (score == null || !score.isNumber()) {
                throw new IllegalArgumentException("No numeric score in the reply");
            }
            String reason = root.path("reason").asText("").trim();
            invocations.record(invocation.succeeded(elapsed(started),
                    response.promptTokens(), response.completionTokens(), false));

            return FitScoreCalculator.ExperienceRating.of(score.asDouble(),
                    reason.isEmpty() ? "Rated by the model." : reason);

        } catch (LlmUnavailableException | IllegalArgumentException
                 | com.fasterxml.jackson.core.JacksonException ex) {
            log.warn("Experience rating fell back to the keyword heuristic: {}", ex.getMessage());
            invocations.record(invocation.failed(elapsed(started), false));
            return heuristic(narrative, posting);
        }
    }

    /**
     * Deterministic fallback, used when no model is configured.
     *
     * <p>It counts how much of the posting's vocabulary appears in the
     * candidate's own experience text - and then maps that count into
     * {@code [0.3, 0.8]} rather than using it directly. The reason is
     * calibration: a word-overlap count is weak evidence, and reading 6%
     * overlap as "your experience is 6% relevant" pushed whole scores a band
     * too low in the evaluation. Bounding it keeps the signal (more overlap
     * still scores higher) while stopping a crude proxy from dominating a
     * score it only contributes 15% to.
     */
    public FitScoreCalculator.ExperienceRating heuristic(String narrative, PostingRequirements posting) {
        Set<String> wanted = new java.util.LinkedHashSet<>();
        posting.requiredOrEmpty().forEach(skill -> wanted.add(dictionary.canonical(skill).toLowerCase(Locale.ROOT)));
        for (String responsibility : posting.responsibilitiesOrEmpty()) {
            Matcher matcher = WORD.matcher(responsibility.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                wanted.add(matcher.group());
            }
        }
        wanted.removeIf(word -> word.length() < 4);

        if (wanted.isEmpty()) {
            return FitScoreCalculator.ExperienceRating.of(NEUTRAL,
                    "No model is configured and the posting listed nothing specific to compare "
                    + "against, so relevance is scored neutrally.");
        }

        String haystack = narrative.toLowerCase(Locale.ROOT);
        long hits = wanted.stream().filter(haystack::contains).count();
        double raw = (double) hits / wanted.size();

        // 40% overlap or better is as much as a word count can tell us.
        double score = FLOOR + (CEILING - FLOOR) * Math.min(1.0, raw / SATURATION);

        return FitScoreCalculator.ExperienceRating.of(score, String.format(
                "No model is configured, so this is a word-overlap estimate rather than a "
                + "judgement: your experience mentions %d of the %d things this posting talks "
                + "about.", hits, wanted.size()));
    }

    private String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
