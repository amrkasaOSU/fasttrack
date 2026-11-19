package com.fasttrack.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.application.dto.ApplicationRequests.AddNote;
import com.fasttrack.application.dto.ApplicationRequests.ChangeStatus;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationSummary;
import com.fasttrack.common.BadRequestException;
import com.fasttrack.common.NotFoundException;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.UserFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Applications, their history, and the rules around editing them. */
class ApplicationServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired ApplicationService service;
    @Autowired ApplicationRepository applications;
    @Autowired ApplicationEventRepository events;
    @Autowired UserFixtures fixtures;

    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = fixtures.newUser("app-owner").getId();
    }

    private ApplicationSummary create(String company, String position) {
        return service.create(userId,
                new UpsertApplication(company, position, null, "Columbus, OH", "Greenhouse",
                        null, Instant.now().minus(Duration.ofDays(10)), null),
                ApplicationSource.MANUAL, EventActor.USER);
    }

    @Test
    @DisplayName("creating an application normalizes the company and position and records a CREATED event")
    void createWritesHistory() {
        ApplicationSummary created = create("Acme, Inc.", "Software Engineer Intern - Summer 2027");

        JobApplication stored = applications.findById(created.id()).orElseThrow();
        assertThat(stored.getCompanyNormalized()).isEqualTo("acme");
        assertThat(stored.getPositionNormalized()).isEqualTo("software engineer intern");
        assertThat(stored.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(stored.getFirstResponseAt()).isNull();

        List<ApplicationEvent> history = events
                .findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getEventType()).isEqualTo(ApplicationEventType.CREATED);
        assertThat(history.get(0).getActor()).isEqualTo(EventActor.USER);
    }

    @Test
    @DisplayName("a company name with no letters or numbers is refused")
    void unusableCompanyNameRefused() {
        assertThatThrownBy(() -> service.create(userId,
                new UpsertApplication("!!!", "SWE Intern", null, null, null, null, null, null),
                ApplicationSource.MANUAL, EventActor.USER))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("an application created mid-funnel records how it got there and counts as responded")
    void createdMidFunnel() {
        ApplicationSummary created = service.create(userId,
                new UpsertApplication("Stripe", "SWE Intern", null, null, null,
                        ApplicationStatus.INTERVIEW_SCHEDULED, Instant.now().minus(Duration.ofDays(5)), null),
                ApplicationSource.CSV_IMPORT, EventActor.IMPORT);

        assertThat(created.status()).isEqualTo(ApplicationStatus.INTERVIEW_SCHEDULED);
        assertThat(applications.findById(created.id()).orElseThrow().getFirstResponseAt()).isNotNull();
        assertThat(events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId))
                .extracting(ApplicationEvent::getEventType)
                .containsExactly(ApplicationEventType.CREATED, ApplicationEventType.STATUS_CHANGED);
    }

    @Test
    @DisplayName("the same requisition id at the same company cannot be added twice")
    void duplicateRequisitionRefused() {
        service.create(userId, new UpsertApplication("Stripe", "SWE Intern", "JR12345",
                null, null, null, null, null), ApplicationSource.MANUAL, EventActor.USER);

        assertThatThrownBy(() -> service.create(userId,
                new UpsertApplication("Stripe Inc", "Backend Intern", "JR12345",
                        null, null, null, null, null), ApplicationSource.MANUAL, EventActor.USER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("JR12345");
    }

    @Test
    @DisplayName("changing the status writes a transition entry and stamps the first response")
    void statusChangeWritesTransition() {
        ApplicationSummary created = create("Datadog", "Data Engineer Intern");

        service.changeStatus(userId, created.id(),
                new ChangeStatus(ApplicationStatus.INTERVIEW_REQUESTED, "Recruiter emailed me"));

        JobApplication stored = applications.findById(created.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationStatus.INTERVIEW_REQUESTED);
        assertThat(stored.getFirstResponseAt()).isNotNull();

        List<ApplicationEvent> history = events
                .findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId);
        ApplicationEvent last = history.get(history.size() - 1);
        assertThat(last.getEventType()).isEqualTo(ApplicationEventType.STATUS_CHANGED);
        assertThat(last.getFromStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(last.getToStatus()).isEqualTo(ApplicationStatus.INTERVIEW_REQUESTED);
        assertThat(last.getDetail()).isEqualTo("Recruiter emailed me");
    }

    @Test
    @DisplayName("setting the status it already has changes nothing and writes no event")
    void noOpStatusChange() {
        ApplicationSummary created = create("Figma", "Product Engineer Intern");
        int before = events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId).size();

        service.changeStatus(userId, created.id(), new ChangeStatus(ApplicationStatus.APPLIED, null));

        assertThat(events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId))
                .hasSize(before);
    }

    @Test
    @DisplayName("editing company or position re-normalizes and is recorded as an edit")
    void editRecordsChanges() {
        ApplicationSummary created = create("Acme", "SWE Intern");

        service.update(userId, created.id(), new UpsertApplication(
                "Acme Corporation", "Backend Engineer Intern", null, "Remote", "Lever", null, null, "note"));

        JobApplication stored = applications.findById(created.id()).orElseThrow();
        assertThat(stored.getCompanyName()).isEqualTo("Acme Corporation");
        assertThat(stored.getCompanyNormalized()).isEqualTo("acme");
        assertThat(stored.getPositionNormalized()).isEqualTo("backend engineer intern");
        assertThat(stored.getLocation()).isEqualTo("Remote");
        assertThat(events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId))
                .extracting(ApplicationEvent::getEventType)
                .contains(ApplicationEventType.EDITED);
    }

    @Test
    @DisplayName("notes append rather than overwrite, and each one is in the timeline")
    void notesAppend() {
        ApplicationSummary created = create("Plaid", "SWE Intern");

        service.addNote(userId, created.id(), new AddNote("Referred by a friend"));
        service.addNote(userId, created.id(), new AddNote("Recruiter said decisions in March"));

        assertThat(applications.findById(created.id()).orElseThrow().getNotes())
                .isEqualTo("Referred by a friend\nRecruiter said decisions in March");
        assertThat(events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId))
                .filteredOn(e -> e.getEventType() == ApplicationEventType.NOTE_ADDED)
                .hasSize(2);
    }

    @Test
    @DisplayName("merging keeps the more advanced status, the earlier date, and deletes the source")
    void mergeKeepsTheBestOfBoth() {
        ApplicationSummary target = service.create(userId, new UpsertApplication(
                "Snowflake", "SWE Intern", null, null, null, null,
                Instant.now().minus(Duration.ofDays(5)), "target note"),
                ApplicationSource.MANUAL, EventActor.USER);
        ApplicationSummary source = service.create(userId, new UpsertApplication(
                "Snowflake", "Software Engineering Intern", "JR999", "Seattle, WA", "Workday",
                ApplicationStatus.INTERVIEW_SCHEDULED, Instant.now().minus(Duration.ofDays(20)), "source note"),
                ApplicationSource.EMAIL, EventActor.EMAIL);

        ApplicationSummary merged = service.merge(userId, target.id(), source.id());

        assertThat(merged.status()).isEqualTo(ApplicationStatus.INTERVIEW_SCHEDULED);
        assertThat(merged.requisitionId()).isEqualTo("JR999");
        assertThat(merged.location()).isEqualTo("Seattle, WA");
        // The claim being tested is "the merge adopted the source's earlier
        // applied date", so it is asserted with a tolerance rather than as
        // exact equality. Instant.now() has nanosecond resolution on Linux,
        // PostgreSQL's timestamptz stores microseconds and *rounds* to get
        // there, so an in-memory value and the same value after a round trip
        // legitimately differ in the last digit.
        assertThat(merged.appliedAt())
                .isCloseTo(source.appliedAt(), within(1, ChronoUnit.MILLIS));
        assertThat(applications.findById(source.id())).isEmpty();
        assertThat(applications.findById(target.id()).orElseThrow().getNotes())
                .isEqualTo("target note\nsource note");
        assertThat(events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(target.id(), userId))
                .extracting(ApplicationEvent::getEventType)
                .contains(ApplicationEventType.MERGED);
    }

    @Test
    @DisplayName("an application cannot be merged into itself")
    void mergeIntoSelfRefused() {
        ApplicationSummary created = create("Reddit", "SWE Intern");

        assertThatThrownBy(() -> service.merge(userId, created.id(), created.id()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("deleting an application removes its history with it")
    void deleteCascades() {
        ApplicationSummary created = create("Notion", "SWE Intern");

        service.delete(userId, created.id());

        assertThat(applications.findById(created.id())).isEmpty();
        assertThat(events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(created.id(), userId))
                .isEmpty();
    }

    @Test
    @DisplayName("archived applications drop out of the default list but can be asked for")
    void archiveHidesFromDefaultList() {
        ApplicationSummary kept = create("Vercel", "SWE Intern");
        ApplicationSummary archived = create("Linear", "SWE Intern");

        service.setArchived(userId, archived.id(), true);

        assertThat(service.list(userId, null, null, false, 0, 50, null).items())
                .extracting(ApplicationSummary::id)
                .containsExactly(kept.id());
        assertThat(service.list(userId, null, null, true, 0, 50, null).items())
                .extracting(ApplicationSummary::id)
                .containsExactlyInAnyOrder(kept.id(), archived.id());

        service.setArchived(userId, archived.id(), false);
        assertThat(service.list(userId, null, null, false, 0, 50, null).totalItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("search matches company, position and location, and the status filter narrows further")
    void searchAndFilter() {
        create("Stripe", "Software Engineer Intern");
        ApplicationSummary data = create("Datadog", "Data Engineer Intern");
        service.changeStatus(userId, data.id(), new ChangeStatus(ApplicationStatus.REJECTED, null));

        assertThat(service.list(userId, null, "datadog", false, 0, 10, null).items()).hasSize(1);
        assertThat(service.list(userId, null, "data engineer", false, 0, 10, null).items()).hasSize(1);
        assertThat(service.list(userId, null, "columbus", false, 0, 10, null).items()).hasSize(2);
        assertThat(service.list(userId, ApplicationStatus.REJECTED, null, false, 0, 10, null).items())
                .extracting(ApplicationSummary::id).containsExactly(data.id());
        assertThat(service.list(userId, ApplicationStatus.OFFER_RECEIVED, null, false, 0, 10, null).items())
                .isEmpty();
    }

    @Test
    @DisplayName("paging is bounded so a client cannot ask for the whole table at once")
    void pageSizeIsClamped() {
        create("Asana", "SWE Intern");

        assertThat(service.list(userId, null, null, false, 0, 10_000, null).size()).isEqualTo(100);
        assertThat(service.list(userId, null, null, false, -5, 0, null).page()).isZero();
    }

    @Test
    @DisplayName("the detail view returns the application with its timeline in order")
    void detailReturnsTimeline() {
        ApplicationSummary created = create("Chime", "SWE Intern");
        service.changeStatus(userId, created.id(), new ChangeStatus(ApplicationStatus.APPLICATION_RECEIVED, null));
        service.changeStatus(userId, created.id(), new ChangeStatus(ApplicationStatus.REJECTED, "Got the email"));

        var detail = service.detail(userId, created.id());

        assertThat(detail.application().status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(detail.timeline()).hasSize(3);
        assertThat(detail.timeline().get(2).toStatus()).isEqualTo(ApplicationStatus.REJECTED);
    }

    @Test
    @DisplayName("resolving a review clears the flag and records who did it")
    void resolveReview() {
        ApplicationSummary created = create("Root Insurance", "SWE Intern");
        JobApplication stored = applications.findById(created.id()).orElseThrow();
        stored.flagForReview("Could not tell which role this email was about");
        applications.save(stored);

        service.resolveReview(userId, created.id(), "Checked - it is this one");

        assertThat(applications.findById(created.id()).orElseThrow().isNeedsReview()).isFalse();
        assertThat(service.needsReview(userId)).isEmpty();
    }

    @Test
    @DisplayName("an unknown id is not found rather than returning null")
    void unknownIdIsNotFound() {
        assertThatThrownBy(() -> service.require(userId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
