package com.fasttrack.csv;

import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.ApplicationService;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.JobApplication;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.matching.TextNormalizer;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Import an existing tracker spreadsheet, and export everything back out.
 *
 * <p>Import is conservative on purpose: a row that already matches an existing
 * application is skipped rather than merged, an unreadable row is reported with
 * its line number instead of failing the whole file, and a status that cannot
 * be understood becomes "Applied" with a warning (never "Rejected").
 */
@Service
public class CsvApplicationService {

    /** The column names accepted for each field, in priority order. */
    private static final List<String> COMPANY_COLUMNS = List.of("company", "company name", "employer");
    private static final List<String> POSITION_COLUMNS = List.of("position", "role", "title", "job title", "position title");
    private static final List<String> DATE_COLUMNS = List.of("date applied", "applied", "date", "applied on", "application date");
    private static final List<String> STATUS_COLUMNS = List.of("status", "stage", "result");
    private static final List<String> LOCATION_COLUMNS = List.of("location", "city", "office");
    private static final List<String> NOTES_COLUMNS = List.of("notes", "note", "comments");

    private static final DateTimeFormatter EXPORT_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final ApplicationService applicationService;
    private final ApplicationRepository applications;
    private final TextNormalizer normalizer;

    public CsvApplicationService(ApplicationService applicationService,
                                 ApplicationRepository applications,
                                 TextNormalizer normalizer) {
        this.applicationService = applicationService;
        this.applications = applications;
        this.normalizer = normalizer;
    }

    /** One problem with one row, reported back with the line number. */
    public record RowIssue(int line, String company, String message) {}

    public record ImportResult(
            int rowsRead,
            int created,
            int duplicatesSkipped,
            List<RowIssue> errors,
            List<RowIssue> warnings) {}

    @Transactional
    public ImportResult importCsv(UUID userId, InputStream input) {
        List<CsvReader.Row> rows = CsvReader.read(input);

        List<RowIssue> errors = new ArrayList<>();
        List<RowIssue> warnings = new ArrayList<>();
        int created = 0;
        int duplicates = 0;

        // Existing keys, so a re-import of the same file is a no-op.
        Set<String> seen = new HashSet<>();
        for (JobApplication existing : applications.findAllByUserIdOrderByCreatedAtDesc(userId)) {
            seen.add(key(existing.getCompanyNormalized(), existing.getPositionNormalized()));
        }

        for (CsvReader.Row row : rows) {
            if (row.isEmpty()) {
                continue;
            }
            String company = firstNonBlank(row, COMPANY_COLUMNS);
            String position = firstNonBlank(row, POSITION_COLUMNS);

            if (company.isBlank()) {
                errors.add(new RowIssue(row.lineNumber(), null, "No company name in this row."));
                continue;
            }
            if (position.isBlank()) {
                errors.add(new RowIssue(row.lineNumber(), company, "No position title in this row."));
                continue;
            }

            String companyKey = normalizer.normalizeCompany(company);
            String positionKey = normalizer.normalizePosition(position);
            if (companyKey.isBlank()) {
                errors.add(new RowIssue(row.lineNumber(), company,
                        "Company name has no letters or numbers to match on."));
                continue;
            }
            if (!seen.add(key(companyKey, positionKey))) {
                duplicates++;
                continue;
            }

            String rawDate = firstNonBlank(row, DATE_COLUMNS);
            Instant appliedAt = CsvFieldParser.parseDate(rawDate).orElse(null);
            if (appliedAt == null && !rawDate.isBlank()) {
                warnings.add(new RowIssue(row.lineNumber(), company,
                        "Could not read the date \"" + rawDate + "\"; left blank."));
            }

            String rawStatus = firstNonBlank(row, STATUS_COLUMNS);
            CsvFieldParser.StatusParse status = CsvFieldParser.parseStatus(rawStatus);
            if (!status.recognised()) {
                warnings.add(new RowIssue(row.lineNumber(), company,
                        "Status \"" + rawStatus + "\" was not recognised; imported as Applied."));
            }

            String notes = firstNonBlank(row, NOTES_COLUMNS);
            if (!status.recognised() && !rawStatus.isBlank()) {
                notes = (notes.isBlank() ? "" : notes + "\n") + "Imported status: " + rawStatus;
            }

            try {
                applicationService.create(userId, new UpsertApplication(
                        company,
                        position,
                        null,
                        firstNonBlank(row, LOCATION_COLUMNS),
                        null,
                        status.status(),
                        appliedAt,
                        notes), ApplicationSource.CSV_IMPORT, EventActor.IMPORT);
                created++;
            } catch (RuntimeException ex) {
                errors.add(new RowIssue(row.lineNumber(), company, ex.getMessage()));
            }
        }

        return new ImportResult(rows.size(), created, duplicates, errors, warnings);
    }

    /** Everything the user has, in the same column layout the importer accepts. */
    @Transactional(readOnly = true)
    public String exportCsv(UUID userId) {
        StringBuilder out = new StringBuilder(
                "Company,Position,Date Applied,Status,Location,Notes,Requisition ID,ATS Platform,First Response,Last Activity\n");

        for (JobApplication a : applications.findAllByUserIdOrderByCreatedAtDesc(userId)) {
            out.append(CsvReader.escape(a.getCompanyName())).append(',')
               .append(CsvReader.escape(a.getPositionTitle())).append(',')
               .append(CsvReader.escape(formatDate(a.getAppliedAt()))).append(',')
               .append(CsvReader.escape(humanStatus(a.getStatus()))).append(',')
               .append(CsvReader.escape(a.getLocation())).append(',')
               .append(CsvReader.escape(a.getNotes())).append(',')
               .append(CsvReader.escape(a.getRequisitionId())).append(',')
               .append(CsvReader.escape(a.getAtsPlatform())).append(',')
               .append(CsvReader.escape(formatDate(a.getFirstResponseAt()))).append(',')
               .append(CsvReader.escape(formatDate(a.getLastEventAt())))
               .append('\n');
        }
        return out.toString();
    }

    private String formatDate(Instant instant) {
        return instant == null ? "" : EXPORT_DATE.format(instant);
    }

    /** Exported statuses stay machine-readable so an export re-imports exactly. */
    private String humanStatus(ApplicationStatus status) {
        return status.name();
    }

    private String key(String companyNormalized, String positionNormalized) {
        return companyNormalized + "||" + positionNormalized;
    }

    private String firstNonBlank(CsvReader.Row row, List<String> columns) {
        for (String column : columns) {
            String value = row.get(column);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
