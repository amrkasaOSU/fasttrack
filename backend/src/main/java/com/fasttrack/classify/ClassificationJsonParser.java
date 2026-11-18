package com.fasttrack.classify;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Turns a model's reply into a validated {@link ClassificationResult}, or
 * refuses it.
 *
 * <p>Strictness is deliberate and uneven, because the failure modes differ:
 * <ul>
 *   <li><b>category</b> and <b>confidence</b> are the fields that drive state
 *       changes, so an unknown category or an out-of-range confidence is
 *       rejected outright - the caller retries once and then falls back to
 *       rules.</li>
 *   <li><b>dates</b> are extra information. A date the model wrote in a format
 *       nobody can parse becomes null rather than discarding an otherwise good
 *       classification.</li>
 * </ul>
 *
 * <p>Code fences and surrounding prose are tolerated: some models wrap JSON in
 * ```json despite being told not to, and failing on that would waste a retry
 * for no benefit. The first {@code { ... }} block in the reply is used, so a
 * lone object wrapped in an array is recovered too.
 */
@Component
public class ClassificationJsonParser {

    private final ObjectMapper mapper;

    public ClassificationJsonParser(ObjectMapper llmObjectMapper) {
        this.mapper = llmObjectMapper;
    }

    public ClassificationResult parse(String raw, String model, String promptVersion, long latencyMs) {
        String json = unwrap(raw);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException ex) {
            throw new InvalidClassificationException("Reply was not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidClassificationException("Reply was not a JSON object");
        }

        String rawCategory = text(root, "category");
        EmailCategory category = EmailCategory.parse(rawCategory)
                .orElseThrow(() -> new InvalidClassificationException(
                        "Unknown category: " + (rawCategory == null ? "(missing)" : rawCategory)));

        JsonNode confidenceNode = root.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) {
            throw new InvalidClassificationException("confidence is missing or not a number");
        }
        double confidence = confidenceNode.asDouble();
        if (confidence < 0 || confidence > 1) {
            throw new InvalidClassificationException("confidence must be between 0 and 1, was " + confidence);
        }

        return new ClassificationResult(
                category,
                text(root, "company"),
                text(root, "position_title"),
                text(root, "requisition_id"),
                instant(root, "interview_datetime"),
                instant(root, "assessment_deadline"),
                text(root, "platform"),
                confidence,
                text(root, "one_sentence_reason"),
                ClassificationMethod.LLM,
                model,
                promptVersion,
                false,
                latencyMs);
    }

    /** Strips markdown fences and any prose around the JSON object. */
    private String unwrap(String raw) {
        if (raw == null) {
            throw new InvalidClassificationException("Reply was empty");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closing = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && closing > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, closing).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        if (trimmed.isBlank()) {
            throw new InvalidClassificationException("Reply was empty");
        }
        return trimmed;
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty() || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("n/a")
                || value.equalsIgnoreCase("unknown")) {
            return null;
        }
        return value;
    }

    /** Accepts an instant, an offset date-time, or a local date-time (treated as UTC). */
    private Instant instant(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            return null; // a date we cannot read is dropped, not fatal
        }
    }
}
