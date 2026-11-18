package com.fasttrack.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.JobApplication;
import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.TestBrowser;
import com.fasttrack.support.UserFixtures;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Importing an existing tracker spreadsheet, and exporting it back out. */
class CsvImportExportIntegrationTest extends AbstractIntegrationTest {

    private static final String SAMPLE = """
            Company,Position,Date Applied,Status,Location,Notes
            Stripe,Software Engineer Intern - Summer 2027,2026-09-02,Applied,"New York, NY",Referred by a friend
            "Datadog, Inc.",Data Engineer Intern,9/14/2026,Rejected,Boston MA,
            Capital One,Software Engineer Intern,"Sep 20, 2026",OA,"McLean, VA",CodeSignal sent
            Plaid,Backend Engineer Intern,2026-10-01,ghosted,Remote,No reply yet
            Figma,Product Engineer Intern,2026-10-05,vibes,Remote,
            ,Missing Company Row,2026-10-06,Applied,,
            Notion,,2026-10-07,Applied,,
            """;

    @Autowired MockMvc mockMvc;
    @Autowired CsvApplicationService csv;
    @Autowired ApplicationRepository applications;
    @Autowired UserFixtures fixtures;

    private AppUserPrincipal user;

    @BeforeEach
    void setUp() {
        user = fixtures.newPrincipal("csv-user");
    }

    private CsvApplicationService.ImportResult importSample() {
        return csv.importCsv(user.userId(),
                new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a realistic tracker file imports, with per-row problems reported by line number")
    void importsTheGoodRowsAndReportsTheRest() {
        var result = importSample();

        assertThat(result.created()).isEqualTo(5);
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).extracting(CsvApplicationService.RowIssue::line)
                .containsExactly(7, 8);
        assertThat(result.errors().get(0).message()).contains("No company name");
        assertThat(result.errors().get(1).message()).contains("No position title");
        assertThat(applications.countByUserId(user.userId())).isEqualTo(5);
    }

    @Test
    @DisplayName("dates in several formats all land on the right day")
    void datesAreParsed() {
        importSample();

        JobApplication stripe = find("Stripe");
        JobApplication capitalOne = find("Capital One");

        assertThat(stripe.getAppliedAt().atZone(ZoneOffset.UTC).toLocalDate().toString())
                .isEqualTo("2026-09-02");
        assertThat(capitalOne.getAppliedAt()).isNotNull();
    }

    @Test
    @DisplayName("statuses map to the funnel, and \"ghosted\" stays Applied")
    void statusesMap() {
        importSample();

        assertThat(find("Stripe").getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(find("Datadog, Inc.").getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(find("Capital One").getStatus()).isEqualTo(ApplicationStatus.ASSESSMENT_REQUESTED);
        assertThat(find("Plaid").getStatus()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    @DisplayName("an unrecognised status becomes Applied, is warned about, and is kept in the notes")
    void unknownStatusIsWarnedNotGuessed() {
        var result = importSample();

        assertThat(result.warnings()).anySatisfy(warning -> {
            assertThat(warning.company()).isEqualTo("Figma");
            assertThat(warning.message()).contains("vibes").contains("Applied");
        });
        assertThat(find("Figma").getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(find("Figma").getNotes()).contains("Imported status: vibes");
    }

    @Test
    @DisplayName("imported rows are marked as coming from CSV and carry a CREATED event")
    void importedRowsAreAttributed() {
        importSample();

        assertThat(find("Stripe").getSource()).isEqualTo(ApplicationSource.CSV_IMPORT);
    }

    @Test
    @DisplayName("re-importing the same file adds nothing and counts the duplicates")
    void reimportIsIdempotent() {
        importSample();
        var second = importSample();

        assertThat(second.created()).isZero();
        assertThat(second.duplicatesSkipped()).isEqualTo(5);
        assertThat(applications.countByUserId(user.userId())).isEqualTo(5);
    }

    @Test
    @DisplayName("the same role written differently is recognised as a duplicate")
    void normalizationCatchesSpellingDifferences() {
        importSample();

        var again = csv.importCsv(user.userId(), new ByteArrayInputStream("""
                Company,Position,Date Applied,Status
                "Stripe, Inc.",Software Engineering Intern (Summer 2027),2026-09-02,Applied
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(again.created()).isZero();
        assertThat(again.duplicatesSkipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("export produces every application and re-imports to exactly the same statuses")
    void exportRoundTrips() {
        importSample();
        String exported = csv.exportCsv(user.userId());

        AppUserPrincipal other = fixtures.newPrincipal("csv-roundtrip");
        var result = csv.importCsv(other.userId(),
                new ByteArrayInputStream(exported.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.created()).isEqualTo(5);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();

        List<ApplicationStatus> original = applications.findAllByUserIdOrderByCreatedAtDesc(user.userId())
                .stream().map(JobApplication::getStatus).sorted().toList();
        List<ApplicationStatus> copy = applications.findAllByUserIdOrderByCreatedAtDesc(other.userId())
                .stream().map(JobApplication::getStatus).sorted().toList();
        assertThat(copy).isEqualTo(original);
    }

    @Test
    @DisplayName("the export endpoint returns a downloadable CSV scoped to the caller")
    void exportEndpoint() throws Exception {
        importSample();

        new TestBrowser(mockMvc).as(user).get("/api/applications/export")
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("fasttrack-applications-")))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains("Stripe")
                        .contains("Company,Position,Date Applied"));
    }

    @Test
    @DisplayName("the import endpoint accepts a .csv upload and reports what it did")
    void importEndpoint() throws Exception {
        new TestBrowser(mockMvc).as(user).upload("/api/applications/import", "tracker.csv", SAMPLE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(5))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    @DisplayName("a non-CSV upload is refused before anything is parsed")
    void nonCsvUploadRefused() throws Exception {
        new TestBrowser(mockMvc).as(user).upload("/api/applications/import", "resume.pdf", "%PDF-1.4")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));

        assertThat(applications.countByUserId(user.userId())).isZero();
    }

    @Test
    @DisplayName("an empty file is reported as a bad request, not a server error")
    void emptyUploadRefused() throws Exception {
        new TestBrowser(mockMvc).as(user).upload("/api/applications/import", "empty.csv", "")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("import and export are scoped to the signed-in user")
    void importIsScopedToTheUser() {
        importSample();
        AppUserPrincipal stranger = fixtures.newPrincipal("csv-stranger");

        assertThat(csv.exportCsv(stranger.userId()).trim())
                .isEqualTo("Company,Position,Date Applied,Status,Location,Notes,"
                        + "Requisition ID,ATS Platform,First Response,Last Activity");
    }

    private JobApplication find(String company) {
        return applications.findAllByUserIdOrderByCreatedAtDesc(user.userId()).stream()
                .filter(a -> a.getCompanyName().equals(company))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No application for " + company));
    }
}
