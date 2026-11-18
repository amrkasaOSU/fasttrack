package com.fasttrack.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.config.JsonMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validation of model output. Fields that drive state changes are strict;
 * decorative fields degrade instead of failing the whole classification.
 */
class ClassificationJsonParserTest {

    private final ClassificationJsonParser parser = new ClassificationJsonParser(JsonMapper.build());

    private ClassificationResult parse(String json) {
        return parser.parse(json, "test-model", "v1", 42);
    }

    @Test
    @DisplayName("a complete, well-formed reply is parsed into every field")
    void fullReply() {
        var result = parse("""
                {
                  "category": "INTERVIEW_SCHEDULED",
                  "company": "Datadog",
                  "position_title": "Software Engineer Intern",
                  "requisition_id": "JR12345",
                  "interview_datetime": "2027-03-04T15:00:00-05:00",
                  "assessment_deadline": null,
                  "platform": null,
                  "confidence": 0.93,
                  "one_sentence_reason": "The email confirms a date and time."
                }
                """);

        assertThat(result.category()).isEqualTo(EmailCategory.INTERVIEW_SCHEDULED);
        assertThat(result.company()).isEqualTo("Datadog");
        assertThat(result.positionTitle()).isEqualTo("Software Engineer Intern");
        assertThat(result.requisitionId()).isEqualTo("JR12345");
        assertThat(result.interviewDatetime()).isEqualTo(Instant.parse("2027-03-04T20:00:00Z"));
        assertThat(result.assessmentDeadline()).isNull();
        assertThat(result.confidence()).isEqualTo(0.93);
        assertThat(result.method()).isEqualTo(ClassificationMethod.LLM);
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(result.promptVersion()).isEqualTo("v1");
        assertThat(result.latencyMs()).isEqualTo(42);
        assertThat(result.needsReview()).isFalse();
    }

    @Test
    @DisplayName("a reply wrapped in markdown fences is still accepted")
    void codeFences() {
        var result = parse("""
                Here you go:
                ```json
                {"category": "REJECTION", "confidence": 0.9}
                ```
                """);

        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
    }

    @Test
    @DisplayName("category is matched case-insensitively and with separators normalised")
    void lenientCategoryParsing() {
        assertThat(parse("{\"category\":\"interview-request\",\"confidence\":0.8}").category())
                .isEqualTo(EmailCategory.INTERVIEW_REQUEST);
        assertThat(parse("{\"category\":\"offer\",\"confidence\":0.8}").category())
                .isEqualTo(EmailCategory.OFFER);
    }

    @Test
    @DisplayName("an unknown category is rejected rather than coerced to a default")
    void unknownCategoryRejected() {
        assertThatThrownBy(() -> parse("{\"category\":\"MAYBE_GOOD_NEWS\",\"confidence\":0.9}"))
                .isInstanceOf(InvalidClassificationException.class)
                .hasMessageContaining("Unknown category");
    }

    @Test
    @DisplayName("a missing or non-numeric confidence is rejected")
    void confidenceMustBePresentAndNumeric() {
        assertThatThrownBy(() -> parse("{\"category\":\"OFFER\"}"))
                .isInstanceOf(InvalidClassificationException.class);
        assertThatThrownBy(() -> parse("{\"category\":\"OFFER\",\"confidence\":\"high\"}"))
                .isInstanceOf(InvalidClassificationException.class);
    }

    @Test
    @DisplayName("a confidence outside 0..1 is rejected")
    void confidenceRange() {
        assertThatThrownBy(() -> parse("{\"category\":\"OFFER\",\"confidence\":1.4}"))
                .isInstanceOf(InvalidClassificationException.class)
                .hasMessageContaining("between 0 and 1");
        assertThatThrownBy(() -> parse("{\"category\":\"OFFER\",\"confidence\":-0.2}"))
                .isInstanceOf(InvalidClassificationException.class);
    }

    @Test
    @DisplayName("prose instead of JSON is rejected")
    void proseRejected() {
        assertThatThrownBy(() -> parse("I think this email is a rejection."))
                .isInstanceOf(InvalidClassificationException.class);
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(InvalidClassificationException.class);
        assertThatThrownBy(() -> parse(null))
                .isInstanceOf(InvalidClassificationException.class);
    }

    @Test
    @DisplayName("a single object wrapped in an array is recovered rather than thrown away")
    void singleObjectInArrayIsRecovered() {
        var result = parse("[{\"category\":\"OFFER\",\"confidence\":0.9}]");

        assertThat(result.category()).isEqualTo(EmailCategory.OFFER);
    }

    @Test
    @DisplayName("a reply with no JSON object at all is rejected")
    void noObjectRejected() {
        assertThatThrownBy(() -> parse("[1, 2, 3]"))
                .isInstanceOf(InvalidClassificationException.class);
    }

    @Test
    @DisplayName("placeholder strings are treated as null, not as data")
    void placeholdersBecomeNull() {
        var result = parse("""
                {"category":"REJECTION","confidence":0.9,"company":"null",
                 "position_title":"unknown","requisition_id":"N/A","platform":"  "}
                """);

        assertThat(result.company()).isNull();
        assertThat(result.positionTitle()).isNull();
        assertThat(result.requisitionId()).isNull();
        assertThat(result.platform()).isNull();
    }

    @Test
    @DisplayName("a date in a format nobody can parse is dropped, not fatal")
    void unparseableDateIsDropped() {
        var result = parse("""
                {"category":"INTERVIEW_SCHEDULED","confidence":0.9,
                 "interview_datetime":"next Tuesday afternoon"}
                """);

        assertThat(result.category()).isEqualTo(EmailCategory.INTERVIEW_SCHEDULED);
        assertThat(result.interviewDatetime()).isNull();
    }

    @Test
    @DisplayName("a local date-time with no offset is read as UTC")
    void localDateTimeIsUtc() {
        var result = parse("""
                {"category":"ASSESSMENT_INVITE","confidence":0.8,
                 "assessment_deadline":"2027-03-10T23:59:00"}
                """);

        assertThat(result.assessmentDeadline()).isEqualTo(Instant.parse("2027-03-10T23:59:00Z"));
    }

    @Test
    @DisplayName("low confidence marks the result for review")
    void lowConfidenceNeedsReview() {
        assertThat(parse("{\"category\":\"REJECTION\",\"confidence\":0.55}").needsReview()).isTrue();
        assertThat(parse("{\"category\":\"REJECTION\",\"confidence\":0.70}").needsReview()).isFalse();
    }
}
