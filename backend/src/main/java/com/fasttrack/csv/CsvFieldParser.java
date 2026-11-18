package com.fasttrack.csv;

import com.fasttrack.application.ApplicationStatus;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns what people actually type in a tracker spreadsheet into the values the
 * domain uses.
 *
 * <p>Deliberate rule: a status this parser does not recognise becomes
 * {@link ApplicationStatus#APPLIED} with a warning, never
 * {@link ApplicationStatus#REJECTED}. Guessing a rejection from an ambiguous
 * word would be the one mistake that silently loses a live application.
 */
public final class CsvFieldParser {

    private CsvFieldParser() {
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US),
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("M-d-yyyy", Locale.US),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US),
            DateTimeFormatter.ofPattern("M/d/yy", Locale.US));

    /** Parses a date written in any of the common spreadsheet formats. */
    public static Optional<Instant> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(value, format);
                return Optional.of(date.atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (DateTimeException ignored) {
                // try the next format
            }
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeException ignored) {
            return Optional.empty();
        }
    }

    /** The result of reading a status cell: the status, and whether it was understood. */
    public record StatusParse(ApplicationStatus status, boolean recognised) {}

    public static StatusParse parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return new StatusParse(ApplicationStatus.APPLIED, true);
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim();

        // Exact enum name first, so an export can always be re-imported.
        for (ApplicationStatus status : ApplicationStatus.values()) {
            if (status.name().equalsIgnoreCase(raw.trim().replace(' ', '_'))) {
                return new StatusParse(status, true);
            }
        }

        ApplicationStatus mapped = switch (value) {
            case "applied", "submitted", "application submitted", "sent", "pending", "waiting",
                 "no response", "no reply", "ghosted", "open" -> ApplicationStatus.APPLIED;
            case "received", "application received", "confirmed", "acknowledged" ->
                    ApplicationStatus.APPLICATION_RECEIVED;
            case "in review", "under review", "reviewing", "in progress", "under consideration" ->
                    ApplicationStatus.UNDER_REVIEW;
            case "oa", "assessment", "online assessment", "coding challenge", "codesignal",
                 "hackerrank", "take home", "assessment requested", "oa received" ->
                    ApplicationStatus.ASSESSMENT_REQUESTED;
            case "oa completed", "assessment completed", "oa done", "assessment submitted" ->
                    ApplicationStatus.ASSESSMENT_COMPLETED;
            case "recruiter", "recruiter call", "recruiter contact", "phone screen", "screen",
                 "recruiter screen" -> ApplicationStatus.RECRUITER_CONTACT;
            case "interview", "interview requested", "interview invite", "invited to interview" ->
                    ApplicationStatus.INTERVIEW_REQUESTED;
            case "interview scheduled", "scheduled" -> ApplicationStatus.INTERVIEW_SCHEDULED;
            case "interviewed", "interview completed", "interview done" ->
                    ApplicationStatus.INTERVIEW_COMPLETED;
            case "final", "final round", "onsite", "on site", "superday", "final interview" ->
                    ApplicationStatus.FINAL_ROUND;
            case "offer", "offer received", "got offer" -> ApplicationStatus.OFFER_RECEIVED;
            case "accepted", "offer accepted", "signed" -> ApplicationStatus.OFFER_ACCEPTED;
            case "declined", "offer declined", "turned down" -> ApplicationStatus.OFFER_DECLINED;
            case "rejected", "reject", "rejection", "denied", "not selected", "unsuccessful" ->
                    ApplicationStatus.REJECTED;
            case "cancelled", "canceled", "position cancelled", "role closed", "closed" ->
                    ApplicationStatus.POSITION_CANCELLED;
            case "withdrawn", "withdrew", "i withdrew" -> ApplicationStatus.WITHDRAWN;
            default -> null;
        };

        return mapped == null
                ? new StatusParse(ApplicationStatus.APPLIED, false)
                : new StatusParse(mapped, true);
    }
}
