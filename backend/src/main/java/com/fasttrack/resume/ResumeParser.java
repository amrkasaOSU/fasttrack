package com.fasttrack.resume;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Structures a resume with the model, falling back to the heuristic parser.
 *
 * <p>Whatever produces it, the result is shown to the user for correction
 * before it is scored against anything - which is the real safeguard against
 * a parser that misreads a line.
 */
@Service
public class ResumeParser {

    private static final Logger log = LoggerFactory.getLogger(ResumeParser.class);

    /** Enough of a resume to parse; the tail is rarely skills or education. */
    private static final int MAX_CHARS = 12_000;

    private final LlmProvider llm;
    private final PromptLibrary prompts;
    private final ObjectMapper mapper;
    private final HeuristicResumeParser fallback;
    private final LlmInvocationRecorder invocations;

    public ResumeParser(LlmProvider llm,
                        PromptLibrary prompts,
                        ObjectMapper llmObjectMapper,
                        HeuristicResumeParser fallback,
                        LlmInvocationRecorder invocations) {
        this.llm = llm;
        this.prompts = prompts;
        this.mapper = llmObjectMapper;
        this.fallback = fallback;
        this.invocations = invocations;
    }

    /** How a profile was produced, so the UI can say so. */
    public record ParsedResume(ResumeProfile profile, ParseMethod method) {}

    public enum ParseMethod { LLM, HEURISTIC }

    public ParsedResume parse(String resumeText, UUID userId) {
        if (!llm.isConfigured()) {
            return new ParsedResume(fallback.parse(resumeText), ParseMethod.HEURISTIC);
        }

        LlmInvocation invocation = new LlmInvocation(
                userId, LlmPurpose.PARSE_RESUME, llm.model(), PromptLibrary.RESUME_VERSION);
        long started = System.nanoTime();

        try {
            LlmResponse response = llm.complete(LlmRequest.json(
                    prompts.resumeSystemPrompt(),
                    prompts.resumeUserPrompt(truncate(resumeText)),
                    LlmPurpose.PARSE_RESUME));

            ResumeProfile profile = toProfile(response.content());
            invocations.record(invocation.succeeded(elapsedMs(started),
                    response.promptTokens(), response.completionTokens(), false));
            return new ParsedResume(profile, ParseMethod.LLM);

        } catch (LlmUnavailableException | IllegalArgumentException ex) {
            log.warn("Resume parsing fell back to rules: {}", ex.getMessage());
            invocations.record(invocation.failed(elapsedMs(started), false));
            return new ParsedResume(fallback.parse(resumeText), ParseMethod.HEURISTIC);
        }
    }

    /**
     * Reads the model's JSON. Anything missing becomes an empty list rather
     * than an error: a partially parsed resume is still useful once the user
     * fixes it, and the whole point of the review step is that it is editable.
     */
    ResumeProfile toProfile(String json) {
        try {
            JsonNode root = mapper.readTree(stripFences(json));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Resume reply was not a JSON object");
            }

            List<ResumeProfile.ExperienceEntry> experience = new ArrayList<>();
            for (JsonNode entry : root.path("experience")) {
                experience.add(new ResumeProfile.ExperienceEntry(
                        text(entry, "title"), text(entry, "organization"),
                        text(entry, "period"), strings(entry.path("highlights"))));
            }

            List<ResumeProfile.ProjectEntry> projects = new ArrayList<>();
            for (JsonNode entry : root.path("projects")) {
                projects.add(new ResumeProfile.ProjectEntry(
                        text(entry, "name"), text(entry, "description"),
                        strings(entry.path("technologies"))));
            }

            JsonNode educationNode = root.path("education");
            ResumeProfile.Education education = educationNode.isObject()
                    ? new ResumeProfile.Education(
                            text(educationNode, "school"), text(educationNode, "degree"),
                            text(educationNode, "field"), text(educationNode, "expected_graduation"))
                    : null;

            return new ResumeProfile(
                    strings(root.path("languages")),
                    strings(root.path("frameworks")),
                    strings(root.path("databases")),
                    strings(root.path("tools")),
                    strings(root.path("cloud")),
                    experience, projects, education,
                    text(root, "work_authorization"));

        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalArgumentException("Resume reply was not valid JSON");
        }
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(node -> {
                String value = node.asText("").trim();
                if (!value.isEmpty() && !value.equalsIgnoreCase("null")) {
                    values.add(value);
                }
            });
        }
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() || text.equalsIgnoreCase("null") ? null : text;
    }

    static String stripFences(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "\n[truncated]";
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
