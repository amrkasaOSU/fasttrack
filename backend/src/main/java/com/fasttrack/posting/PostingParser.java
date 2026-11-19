package com.fasttrack.posting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.classify.PromptLibrary;
import com.fasttrack.common.BadRequestException;
import com.fasttrack.llm.LlmInvocation;
import com.fasttrack.llm.LlmInvocationRecorder;
import com.fasttrack.llm.LlmProvider;
import com.fasttrack.llm.LlmPurpose;
import com.fasttrack.llm.LlmRequest;
import com.fasttrack.llm.LlmResponse;
import com.fasttrack.llm.LlmUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a pasted posting - or a fetched URL - into structured requirements.
 *
 * <p>Pasting is the primary path and the one that always works. Fetching is
 * offered as a convenience and fails honestly: most modern job boards render
 * their postings with JavaScript, and when the fetch comes back with nothing
 * useful the user is told to paste the text rather than being given an empty
 * score.
 */
@Service
public class PostingParser {

    private static final Logger log = LoggerFactory.getLogger(PostingParser.class);

    private static final int MAX_CHARS = 14_000;
    private static final int FETCH_TIMEOUT_MS = 10_000;
    /** Below this, a fetched page is a login wall or a JavaScript shell. */
    private static final int MIN_FETCHED_CHARACTERS = 400;

    private final LlmProvider llm;
    private final PromptLibrary prompts;
    private final ObjectMapper mapper;
    private final HeuristicPostingParser fallback;
    private final LlmInvocationRecorder invocations;

    public PostingParser(LlmProvider llm,
                         PromptLibrary prompts,
                         ObjectMapper llmObjectMapper,
                         HeuristicPostingParser fallback,
                         LlmInvocationRecorder invocations) {
        this.llm = llm;
        this.prompts = prompts;
        this.mapper = llmObjectMapper;
        this.fallback = fallback;
        this.invocations = invocations;
    }

    public record ParsedPosting(PostingRequirements requirements, ParseMethod method) {}

    public enum ParseMethod { LLM, HEURISTIC }

    public ParsedPosting parse(String postingText, UUID userId) {
        if (postingText == null || postingText.isBlank()) {
            throw new BadRequestException("Paste the job posting text first.");
        }
        if (!llm.isConfigured()) {
            return new ParsedPosting(fallback.parse(postingText), ParseMethod.HEURISTIC);
        }

        LlmInvocation invocation = new LlmInvocation(
                userId, LlmPurpose.PARSE_POSTING, llm.model(), PromptLibrary.POSTING_VERSION);
        long started = System.nanoTime();

        try {
            LlmResponse response = llm.complete(LlmRequest.json(
                    prompts.postingSystemPrompt(),
                    prompts.postingUserPrompt(truncate(postingText)),
                    LlmPurpose.PARSE_POSTING));

            PostingRequirements requirements = toRequirements(response.content());
            invocations.record(invocation.succeeded(elapsed(started),
                    response.promptTokens(), response.completionTokens(), false));
            return new ParsedPosting(requirements, ParseMethod.LLM);

        } catch (LlmUnavailableException | IllegalArgumentException ex) {
            log.warn("Posting parsing fell back to rules: {}", ex.getMessage());
            invocations.record(invocation.failed(elapsed(started), false));
            return new ParsedPosting(fallback.parse(postingText), ParseMethod.HEURISTIC);
        }
    }

    /**
     * Fetches a posting URL and returns its readable text.
     *
     * @throws BadRequestException when the page cannot be fetched or carries
     *         no readable posting - with instructions to paste instead
     */
    public String fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("That does not look like a URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BadRequestException("Only http and https links can be fetched.");
        }

        try {
            Document document = Jsoup.connect(uri.toString())
                    .timeout(FETCH_TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreHttpErrors(false)
                    .userAgent("Mozilla/5.0 (compatible; FastTrack/0.1; job posting reader)")
                    .get();

            document.select("script, style, nav, footer, header, noscript, svg, form").remove();

            // Sanitise before taking the text: the fetched page is untrusted
            // input that will be put into a prompt and shown to the user.
            String safeHtml = Jsoup.clean(document.body().html(), Safelist.basic());
            String text = Jsoup.parse(safeHtml).text().replaceAll("\\s{2,}", " ").trim();

            if (text.length() < MIN_FETCHED_CHARACTERS) {
                throw new BadRequestException(
                        "That page did not return a readable posting - most job boards render "
                        + "them with JavaScript. Copy the posting text and paste it instead.");
            }
            return text;

        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BadRequestException(
                    "That page could not be fetched. Copy the posting text and paste it instead.");
        }
    }

    PostingRequirements toRequirements(String json) {
        try {
            JsonNode root = mapper.readTree(strip(json));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Posting reply was not a JSON object");
            }
            return new PostingRequirements(
                    text(root, "title"),
                    text(root, "company"),
                    strings(root.path("required_skills")),
                    strings(root.path("preferred_skills")),
                    strings(root.path("responsibilities")),
                    text(root, "min_education"),
                    text(root, "graduation_window"),
                    text(root, "work_authorization_requirements"),
                    text(root, "location"),
                    text(root, "internship_season"));
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalArgumentException("Posting reply was not valid JSON");
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

    private String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private String truncate(String text) {
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "\n[truncated]";
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
