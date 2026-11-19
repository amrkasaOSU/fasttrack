package com.fasttrack.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.application.ApplicationService;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.dto.ApplicationRequests.ChangeStatus;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.demo.DemoAccountService;
import com.fasttrack.demo.DemoDataSeeder;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.TestBrowser;
import com.fasttrack.support.UserFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The dashboard's arithmetic. The cases that matter are the ones where a
 * careless aggregation would flatter the numbers: an application rejected
 * after an interview still interviewed, and silence is never a rejection.
 */
class InsightsIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired InsightsService insights;
    @Autowired ApplicationService applications;
    @Autowired DemoAccountService demoAccounts;
    @Autowired DemoDataSeeder seeder;
    @Autowired UserFixtures fixtures;

    private AppUserPrincipal principal;
    private UUID userId;

    @BeforeEach
    void setUp() {
        principal = fixtures.newPrincipal("insights");
        userId = principal.userId();
    }

    private UUID add(String company, String position, Instant appliedAt, String location, String ats) {
        return applications.create(userId,
                new UpsertApplication(company, position, null, location, ats, null, appliedAt, null),
                ApplicationSource.MANUAL, EventActor.USER).id();
    }

    private void moveTo(UUID applicationId, ApplicationStatus... statuses) {
        for (ApplicationStatus status : statuses) {
            applications.changeStatus(userId, applicationId, new ChangeStatus(status, null));
        }
    }

    @Test
    @DisplayName("an empty account reports zeros and null rates, not a divide-by-zero")
    void emptyAccount() {
        var result = insights.forUser(userId);

        assertThat(result.totals().applications()).isZero();
        assertThat(result.totals().responseRate()).isNull();
        assertThat(result.applicationsPerWeek()).isEmpty();
        assertThat(result.actionItems()).isEmpty();
        assertThat(result.funnel()).isNotEmpty();
    }

    @Test
    @DisplayName("totals count companies, responses and silence separately")
    void totals() {
        Instant threeWeeksAgo = Instant.now().minus(Duration.ofDays(21));
        UUID answered = add("Stripe", "SWE Intern", threeWeeksAgo, "Remote", "Greenhouse");
        add("Stripe", "Data Intern", threeWeeksAgo, "Remote", "Greenhouse");
        add("Datadog", "SWE Intern", threeWeeksAgo, "Boston, MA", "Greenhouse");
        moveTo(answered, ApplicationStatus.APPLICATION_RECEIVED);

        var totals = insights.forUser(userId).totals();

        assertThat(totals.applications()).isEqualTo(3);
        assertThat(totals.companies()).isEqualTo(2);
        assertThat(totals.responses()).isEqualTo(1);
        assertThat(totals.noResponse()).isEqualTo(2);
        assertThat(totals.responseRate()).isEqualTo(1.0 / 3);
    }

    @Test
    @DisplayName("an application rejected after an interview still counts as having interviewed")
    void rejectionAfterInterviewStillCountsInTheFunnel() {
        UUID application = add("Stripe", "SWE Intern", Instant.now().minus(Duration.ofDays(30)), null, null);
        moveTo(application, ApplicationStatus.APPLICATION_RECEIVED,
                ApplicationStatus.INTERVIEW_REQUESTED, ApplicationStatus.INTERVIEW_COMPLETED,
                ApplicationStatus.REJECTED);

        var result = insights.forUser(userId);

        assertThat(result.totals().interviews()).isEqualTo(1);
        assertThat(result.totals().rejections()).isEqualTo(1);
        assertThat(result.funnel()).anySatisfy(stage -> {
            assertThat(stage.stage()).isEqualTo("Interview");
            assertThat(stage.count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("silence is never counted as a rejection")
    void silenceIsNotRejection() {
        add("Stripe", "SWE Intern", Instant.now().minus(Duration.ofDays(90)), null, null);

        var totals = insights.forUser(userId).totals();

        assertThat(totals.rejections()).isZero();
        assertThat(totals.noResponse()).isEqualTo(1);
        assertThat(totals.active()).isEqualTo(1);
    }

    @Test
    @DisplayName("applications are bucketed by the week they were sent")
    void weeklyVolume() {
        add("A", "SWE Intern", Instant.now().minus(Duration.ofDays(7)), null, null);
        add("B", "SWE Intern", Instant.now().minus(Duration.ofDays(8)), null, null);
        add("C", "SWE Intern", Instant.now().minus(Duration.ofDays(30)), null, null);

        var weeks = insights.forUser(userId).applicationsPerWeek();

        assertThat(weeks).isNotEmpty();
        assertThat(weeks.stream().mapToLong(point -> point.applied()).sum()).isEqualTo(3);
        assertThat(weeks).isSortedAccordingTo(
                java.util.Comparator.comparing(InsightsService.WeeklyPoint::weekStart));
    }

    @Test
    @DisplayName("response rate is grouped by ATS platform and by location type")
    void groupedRates() {
        UUID workday = add("Capital One", "SWE Intern", Instant.now().minus(Duration.ofDays(20)),
                "Remote", "Workday");
        add("Target", "SWE Intern", Instant.now().minus(Duration.ofDays(20)), "Columbus, OH", "Workday");
        add("Stripe", "SWE Intern", Instant.now().minus(Duration.ofDays(20)), "Hybrid - NYC", "Greenhouse");
        moveTo(workday, ApplicationStatus.APPLICATION_RECEIVED);

        var result = insights.forUser(userId);

        assertThat(result.byPlatform()).anySatisfy(group -> {
            assertThat(group.group()).isEqualTo("Workday");
            assertThat(group.applications()).isEqualTo(2);
            assertThat(group.responseRate()).isEqualTo(0.5);
        });
        assertThat(result.byLocationType()).extracting(InsightsService.GroupedRate::group)
                .contains("Remote", "On site", "Hybrid");
    }

    @Test
    @DisplayName("time to first response is averaged only over applications that got one")
    void timing() {
        UUID answered = add("Stripe", "SWE Intern", Instant.now().minus(Duration.ofDays(10)), null, null);
        add("Datadog", "SWE Intern", Instant.now().minus(Duration.ofDays(60)), null, null);
        moveTo(answered, ApplicationStatus.APPLICATION_RECEIVED);

        var timing = insights.forUser(userId).timing();

        assertThat(timing.measuredFrom()).isEqualTo(1);
        assertThat(timing.averageDaysToFirstResponse()).isNotNull().isBetween(9.0, 11.0);
    }

    @Test
    @DisplayName("an application with no reply after three weeks becomes a follow-up item")
    void followUpItems() {
        add("Stripe", "SWE Intern", Instant.now().minus(Duration.ofDays(30)), null, null);
        add("Datadog", "SWE Intern", Instant.now().minus(Duration.ofDays(3)), null, null);

        var items = insights.forUser(userId).actionItems();

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("NO_RESPONSE");
            assertThat(item.company()).isEqualTo("Stripe");
            assertThat(item.detail()).contains("No reply after");
        });
    }

    @Test
    @DisplayName("an assessment with no deadline found is still surfaced")
    void assessmentWithoutDeadline() {
        UUID application = add("Capital One", "SWE Intern", Instant.now().minus(Duration.ofDays(5)), null, null);
        moveTo(application, ApplicationStatus.ASSESSMENT_REQUESTED);

        var items = insights.forUser(userId).actionItems();

        assertThat(items).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo("ASSESSMENT_PENDING");
            assertThat(item.detail()).contains("no deadline found");
        });
    }

    @Test
    @DisplayName("fit-versus-outcome is withheld until there is enough data to mean anything")
    void fitOutcomeNeedsASample() {
        add("Stripe", "SWE Intern", Instant.now().minus(Duration.ofDays(10)), null, null);

        var result = insights.forUser(userId);

        assertThat(result.fitVsOutcome()).isEmpty();
        assertThat(result.fitVsOutcomeNote()).contains("Link at least");
    }

    @Test
    @DisplayName("archived applications are excluded from the live numbers but counted")
    void archivedAreSeparate() {
        UUID archived = add("Stripe", "SWE Intern", Instant.now().minus(Duration.ofDays(10)), null, null);
        add("Datadog", "SWE Intern", Instant.now().minus(Duration.ofDays(10)), null, null);
        applications.setArchived(userId, archived, true);

        var totals = insights.forUser(userId).totals();

        assertThat(totals.applications()).isEqualTo(1);
        assertThat(totals.archived()).isEqualTo(1);
    }

    @Test
    @DisplayName("the demo account produces a dashboard with every section populated")
    void demoAccountHasAFullDashboard() {
        var demo = demoAccounts.ensureDemoUser();
        seeder.seedApplications(demo);

        var result = insights.forUser(demo.getId());

        assertThat(result.totals().applications()).isEqualTo(DemoDataSeeder.TARGET_APPLICATIONS);
        assertThat(result.totals().companies()).isGreaterThan(30);
        assertThat(result.totals().responseRate()).isNotNull().isBetween(0.0, 1.0);
        assertThat(result.applicationsPerWeek()).isNotEmpty();
        assertThat(result.byPlatform()).isNotEmpty();
        assertThat(result.timing().averageDaysToFirstResponse()).isNotNull();
        assertThat(result.funnel()).hasSize(5);
    }

    @Test
    @DisplayName("insights are scoped to the signed-in user")
    void scopedToTheUser() throws Exception {
        add("Stripe", "SWE Intern", Instant.now(), null, null);
        var stranger = fixtures.newPrincipal("insights-stranger");

        new TestBrowser(mockMvc).as(stranger).get("/api/insights")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.applications").value(0));

        new TestBrowser(mockMvc).as(principal).get("/api/insights")
                .andExpect(jsonPath("$.totals.applications").value(1));
    }

    @Test
    @DisplayName("an anonymous caller gets 401, not an empty dashboard")
    void anonymousIsRefused() throws Exception {
        new TestBrowser(mockMvc).get("/api/insights").andExpect(status().isUnauthorized());
    }
}
