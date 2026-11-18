package com.fasttrack.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.classify.EmailContent;
import java.time.Instant;

/** One labeled record from {@code eval/emails.jsonl}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalEmail(
        String id,
        EmailCategory category,
        String company,
        @JsonProperty("position_title") String positionTitle,
        String sender,
        @JsonProperty("sender_name") String senderName,
        String subject,
        String body,
        String ats,
        @JsonProperty("expect_prefilter") String expectPrefilter,
        String note) {

    public boolean expectsPrefilterDrop() {
        return "DROP".equalsIgnoreCase(expectPrefilter);
    }

    public boolean isTricky() {
        return note != null && note.startsWith("tricky");
    }

    public EmailContent toContent() {
        return new EmailContent(sender, senderName, subject, body, Instant.parse("2027-02-16T15:04:05Z"));
    }
}
