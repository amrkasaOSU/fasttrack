package com.fasttrack.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.application.ApplicationStatus;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What people actually type in a tracker spreadsheet. */
class CsvFieldParserTest {

    @ParameterizedTest(name = "date \"{0}\" is understood")
    @ValueSource(strings = {
            "2026-09-14", "2026/09/14", "9/14/2026", "9-14-2026",
            "14 Sep 2026", "Sep 14, 2026", "September 14, 2026", "9/14/26"})
    void datesInEveryCommonFormat(String raw) {
        var parsed = CsvFieldParser.parseDate(raw);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().atZone(ZoneOffset.UTC).getYear()).isEqualTo(2026);
        assertThat(parsed.get().atZone(ZoneOffset.UTC).getMonthValue()).isEqualTo(9);
        assertThat(parsed.get().atZone(ZoneOffset.UTC).getDayOfMonth()).isEqualTo(14);
    }

    @Test
    @DisplayName("an unreadable or empty date is reported as absent, not guessed")
    void unreadableDate() {
        assertThat(CsvFieldParser.parseDate("last tuesday")).isEmpty();
        assertThat(CsvFieldParser.parseDate("")).isEmpty();
        assertThat(CsvFieldParser.parseDate(null)).isEmpty();
    }

    @ParameterizedTest(name = "status \"{0}\" maps to {1}")
    @CsvSource({
            "Applied,              APPLIED",
            "submitted,            APPLIED",
            "In Review,            UNDER_REVIEW",
            "OA,                   ASSESSMENT_REQUESTED",
            "Online Assessment,    ASSESSMENT_REQUESTED",
            "HackerRank,           ASSESSMENT_REQUESTED",
            "OA completed,         ASSESSMENT_COMPLETED",
            "phone screen,         RECRUITER_CONTACT",
            "Interview,            INTERVIEW_REQUESTED",
            "Interview Scheduled,  INTERVIEW_SCHEDULED",
            "Interviewed,          INTERVIEW_COMPLETED",
            "Superday,             FINAL_ROUND",
            "Offer,                OFFER_RECEIVED",
            "Accepted,             OFFER_ACCEPTED",
            "Declined,             OFFER_DECLINED",
            "Rejected,             REJECTED",
            "not selected,         REJECTED",
            "role closed,          POSITION_CANCELLED",
            "Withdrawn,            WITHDRAWN"
    })
    void statusAliases(String raw, ApplicationStatus expected) {
        var parsed = CsvFieldParser.parseStatus(raw);

        assertThat(parsed.status()).isEqualTo(expected);
        assertThat(parsed.recognised()).isTrue();
    }

    @Test
    @DisplayName("\"ghosted\" and \"no response\" stay Applied - silence is not a rejection")
    void silenceIsNotRejection() {
        assertThat(CsvFieldParser.parseStatus("ghosted").status()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(CsvFieldParser.parseStatus("no response").status()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(CsvFieldParser.parseStatus("waiting").status()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    @DisplayName("an unknown status becomes Applied and is flagged as unrecognised, never Rejected")
    void unknownStatusIsConservative() {
        var parsed = CsvFieldParser.parseStatus("vibes were off");

        assertThat(parsed.status()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(parsed.recognised()).isFalse();
    }

    @Test
    @DisplayName("an empty status cell is Applied and counts as understood")
    void emptyStatus() {
        assertThat(CsvFieldParser.parseStatus("").recognised()).isTrue();
        assertThat(CsvFieldParser.parseStatus(null).status()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    @DisplayName("an exported file re-imports exactly: every enum name round-trips")
    void everyStatusRoundTrips() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            var parsed = CsvFieldParser.parseStatus(status.name());

            assertThat(parsed.status()).as("round trip for %s", status).isEqualTo(status);
            assertThat(parsed.recognised()).isTrue();
        }
    }
}
