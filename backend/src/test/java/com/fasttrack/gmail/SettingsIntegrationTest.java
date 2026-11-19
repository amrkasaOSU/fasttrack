package com.fasttrack.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.account.AccountDeletionService;
import com.fasttrack.account.UserRepository;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.ApplicationService;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.auth.AppUserPrincipal;
import com.fasttrack.demo.DemoAccountService;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.TestBrowser;
import com.fasttrack.support.UserFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The settings surface: what it reports when Gmail is not configured, and the
 * two destructive actions - which must work completely and must be refused to
 * the shared demo account.
 */
class SettingsIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserFixtures fixtures;
    @Autowired ApplicationService applicationService;
    @Autowired ApplicationRepository applications;
    @Autowired UserRepository users;
    @Autowired AccountDeletionService deletion;
    @Autowired DemoAccountService demoAccounts;

    private AppUserPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = fixtures.newPrincipal("settings");
    }

    private TestBrowser browser() {
        return new TestBrowser(mockMvc).as(principal);
    }

    private UUID seedApplication() {
        return applicationService.create(principal.userId(),
                new UpsertApplication("Stripe", "SWE Intern", null, null, null, null, null, null),
                ApplicationSource.MANUAL, EventActor.USER).id();
    }

    @Test
    @DisplayName("with no Google credentials, settings says Gmail is unavailable rather than failing")
    void reportsGmailUnavailable() throws Exception {
        browser().get("/api/settings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gmail.available").value(false))
                .andExpect(jsonPath("$.gmail.connected").value(false))
                .andExpect(jsonPath("$.data.applications").value(0));
    }

    @Test
    @DisplayName("settings reports what is actually stored for the user")
    void reportsStoredData() throws Exception {
        seedApplication();

        browser().get("/api/settings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applications").value(1))
                .andExpect(jsonPath("$.data.emailsStored").value(0));
    }

    @Test
    @DisplayName("sync now on an unconnected mailbox reports a skipped run, not an error")
    void syncWithoutGmailIsSkipped() throws Exception {
        browser().post("/api/settings/gmail/sync", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"));
    }

    @Test
    @DisplayName("deleting everything removes the user and every row that hangs off them")
    void deleteEverythingCascades() {
        UUID applicationId = seedApplication();
        UUID userId = principal.userId();

        var report = deletion.deleteEverything(userId);

        assertThat(report.userDeleted()).isTrue();
        assertThat(users.findById(userId)).isEmpty();
        assertThat(applications.findById(applicationId)).isEmpty();
        assertThat(applications.countByUserId(userId)).isZero();
    }

    @Test
    @DisplayName("deleting is reported honestly when there was nothing to delete")
    void deleteTwiceIsHonest() {
        UUID userId = principal.userId();
        deletion.deleteEverything(userId);

        var second = deletion.deleteEverything(userId);

        assertThat(second.userDeleted()).isFalse();
    }

    @Test
    @DisplayName("the demo account cannot delete the shared demo data")
    void demoCannotDeleteData() throws Exception {
        var demo = demoAccounts.ensureDemoUser();
        var demoPrincipal = AppUserPrincipal.from(demo);

        new TestBrowser(mockMvc).as(demoPrincipal).delete("/api/settings/data")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        assertThat(users.findFirstByDemoIsTrue()).isPresent();
    }

    @Test
    @DisplayName("the demo account cannot trigger a sync or disconnect")
    void demoCannotTouchGmail() throws Exception {
        var demo = demoAccounts.ensureDemoUser();
        var demoPrincipal = AppUserPrincipal.from(demo);

        new TestBrowser(mockMvc).as(demoPrincipal)
                .post("/api/settings/gmail/sync", null)
                .andExpect(status().isForbidden());
        new TestBrowser(mockMvc).as(demoPrincipal)
                .post("/api/settings/gmail/disconnect", null)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("settings is per-user: one account never sees another's numbers")
    void settingsAreScoped() throws Exception {
        seedApplication();
        var stranger = fixtures.newPrincipal("settings-stranger");

        new TestBrowser(mockMvc).as(stranger).get("/api/settings")
                .andExpect(jsonPath("$.data.applications").value(0));
    }

    @Test
    @DisplayName("an anonymous caller cannot read settings or delete anything")
    void anonymousIsRefused() throws Exception {
        new TestBrowser(mockMvc).get("/api/settings").andExpect(status().isUnauthorized());
        new TestBrowser(mockMvc).delete("/api/settings/data").andExpect(status().isUnauthorized());
    }
}
