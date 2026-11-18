package com.fasttrack.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationSummary;
import com.fasttrack.auth.AppUserPrincipal;
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
 * Proof that one user can never reach another user's data.
 *
 * <p>Alice owns an application; Bob is signed in and tries every verb on it
 * with the real id. Every attempt must come back 404 - not 403, which would
 * confirm the id exists - and nothing may change.
 */
class ApplicationOwnershipIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationService service;
    @Autowired ApplicationRepository applications;
    @Autowired UserFixtures fixtures;

    private AppUserPrincipal alice;
    private AppUserPrincipal bob;
    private UUID aliceApplicationId;

    @BeforeEach
    void setUp() {
        alice = fixtures.newPrincipal("alice");
        bob = fixtures.newPrincipal("bob");

        ApplicationSummary created = service.create(alice.userId(),
                new UpsertApplication("Stripe", "Software Engineer Intern", "JR1", "NYC",
                        "Greenhouse", null, null, "alice's private note"),
                ApplicationSource.MANUAL, EventActor.USER);
        aliceApplicationId = created.id();
    }

    private TestBrowser as(AppUserPrincipal principal) {
        return new TestBrowser(mockMvc).as(principal);
    }

    @Test
    @DisplayName("the owner can read their own application")
    void ownerCanRead() throws Exception {
        as(alice).get("/api/applications/" + aliceApplicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.companyName").value("Stripe"));
    }

    @Test
    @DisplayName("another user reading it gets 404, and the note never appears in the response")
    void strangerCannotRead() throws Exception {
        as(bob).get("/api/applications/" + aliceApplicationId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("alice's private note"))));
    }

    @Test
    @DisplayName("another user cannot edit it, and the record is untouched afterwards")
    void strangerCannotEdit() throws Exception {
        as(bob).put("/api/applications/" + aliceApplicationId, """
                {"companyName":"Bob Corp","positionTitle":"Hijacked"}
                """)
                .andExpect(status().isNotFound());

        assertThat(applications.findById(aliceApplicationId).orElseThrow().getCompanyName())
                .isEqualTo("Stripe");
    }

    @Test
    @DisplayName("another user cannot change its status")
    void strangerCannotChangeStatus() throws Exception {
        as(bob).post("/api/applications/" + aliceApplicationId + "/status", """
                {"status":"REJECTED"}
                """)
                .andExpect(status().isNotFound());

        assertThat(applications.findById(aliceApplicationId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    @DisplayName("another user cannot delete it")
    void strangerCannotDelete() throws Exception {
        as(bob).delete("/api/applications/" + aliceApplicationId)
                .andExpect(status().isNotFound());

        assertThat(applications.findById(aliceApplicationId)).isPresent();
    }

    @Test
    @DisplayName("another user cannot archive, note, or resolve review on it")
    void strangerCannotMutateAnything() throws Exception {
        as(bob).post("/api/applications/" + aliceApplicationId + "/archive", null)
                .andExpect(status().isNotFound());
        as(bob).post("/api/applications/" + aliceApplicationId + "/notes", """
                {"note":"not mine"}
                """).andExpect(status().isNotFound());
        as(bob).post("/api/applications/" + aliceApplicationId + "/resolve-review", "{}")
                .andExpect(status().isNotFound());

        JobApplication stored = applications.findById(aliceApplicationId).orElseThrow();
        assertThat(stored.isArchived()).isFalse();
        assertThat(stored.getNotes()).isEqualTo("alice's private note");
    }

    @Test
    @DisplayName("another user cannot merge someone else's application into their own")
    void strangerCannotMergeAcrossAccounts() throws Exception {
        ApplicationSummary bobsOwn = service.create(bob.userId(),
                new UpsertApplication("Datadog", "SWE Intern", null, null, null, null, null, null),
                ApplicationSource.MANUAL, EventActor.USER);

        as(bob).post("/api/applications/" + bobsOwn.id() + "/merge",
                        "{\"sourceId\":\"" + aliceApplicationId + "\"}")
                .andExpect(status().isNotFound());

        assertThat(applications.findById(aliceApplicationId)).isPresent();
    }

    @Test
    @DisplayName("listing only ever returns your own applications")
    void listIsScopedToTheSignedInUser() throws Exception {
        service.create(bob.userId(), new UpsertApplication("Figma", "Design Engineer Intern",
                null, null, null, null, null, null), ApplicationSource.MANUAL, EventActor.USER);

        as(bob).get("/api/applications?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].companyName").value("Figma"));

        as(alice).get("/api/applications?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].companyName").value("Stripe"));
    }

    @Test
    @DisplayName("a mutating request without the CSRF header is refused even for the owner")
    void csrfProtectsMutations() throws Exception {
        as(alice).postWithoutCsrf("/api/applications/" + aliceApplicationId + "/archive", "{}")
                .andExpect(status().isForbidden());

        assertThat(applications.findById(aliceApplicationId).orElseThrow().isArchived()).isFalse();
    }

    @Test
    @DisplayName("an anonymous request is unauthenticated, not merely empty")
    void anonymousIsRejected() throws Exception {
        new TestBrowser(mockMvc).get("/api/applications/" + aliceApplicationId)
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a validation error does not create anything")
    void invalidCreateIsRejected() throws Exception {
        as(bob).post("/api/applications", """
                {"companyName":"","positionTitle":""}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));

        assertThat(applications.countByUserId(bob.userId())).isZero();
    }

    @Test
    @DisplayName("creating through the API stores it under the session user, not any id in the body")
    void userIdIsNeverTakenFromTheRequest() throws Exception {
        as(bob).post("/api/applications", """
                {"companyName":"Reddit","positionTitle":"SWE Intern","userId":"%s"}
                """.formatted(alice.userId()))
                .andExpect(status().isCreated());

        assertThat(applications.countByUserId(bob.userId())).isEqualTo(1);
        assertThat(applications.countByUserId(alice.userId())).isEqualTo(1); // unchanged
    }
}
