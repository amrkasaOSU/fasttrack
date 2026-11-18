package com.fasttrack.application;

import com.fasttrack.application.dto.ApplicationRequests.AddNote;
import com.fasttrack.application.dto.ApplicationRequests.ChangeStatus;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationDetail;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationPage;
import com.fasttrack.application.dto.ApplicationResponses.ApplicationSummary;
import com.fasttrack.application.dto.ApplicationResponses.TimelineEntry;
import com.fasttrack.common.BadRequestException;
import com.fasttrack.common.NotFoundException;
import com.fasttrack.matching.TextNormalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applications and their history.
 *
 * <p>Every method takes the acting {@code userId} as its first argument and
 * every read goes through a user-scoped repository method, so an application
 * belonging to someone else is indistinguishable from one that does not exist.
 *
 * <p>Status changes always write an {@link ApplicationEvent}: the timeline is
 * the record, the {@code status} column is only a cache of its latest value.
 */
@Service
public class ApplicationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final ApplicationStateMachine stateMachine;
    private final TextNormalizer normalizer;

    public ApplicationService(ApplicationRepository applications,
                              ApplicationEventRepository events,
                              ApplicationStateMachine stateMachine,
                              TextNormalizer normalizer) {
        this.applications = applications;
        this.events = events;
        this.stateMachine = stateMachine;
        this.normalizer = normalizer;
    }

    // ----------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public ApplicationPage list(UUID userId, ApplicationStatus status, String search,
                                boolean includeArchived, int page, int size, String sort) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Page<JobApplication> result = applications.search(
                userId,
                status,
                search == null || search.isBlank() ? null : "%" + search.toLowerCase().trim() + "%",
                includeArchived,
                PageRequest.of(Math.max(page, 0), safeSize, sortOf(sort)));

        return new ApplicationPage(
                result.getContent().stream().map(ApplicationSummary::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    private Sort sortOf(String sort) {
        return switch (sort == null ? "" : sort) {
            case "appliedAt" -> Sort.by(Sort.Direction.DESC, "appliedAt").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "company" -> Sort.by(Sort.Direction.ASC, "companyName");
            case "status" -> Sort.by(Sort.Direction.ASC, "status").and(Sort.by(Sort.Direction.DESC, "updatedAt"));
            case "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt");
            default -> Sort.by(Sort.Direction.DESC, "updatedAt");
        };
    }

    @Transactional(readOnly = true)
    public JobApplication require(UUID userId, UUID applicationId) {
        return applications.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> new NotFoundException("Application"));
    }

    @Transactional(readOnly = true)
    public ApplicationDetail detail(UUID userId, UUID applicationId) {
        JobApplication application = require(userId, applicationId);
        List<TimelineEntry> timeline =
                events.findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(applicationId, userId)
                        .stream().map(TimelineEntry::from).toList();
        return new ApplicationDetail(ApplicationSummary.from(application), timeline, application.getNotes());
    }

    @Transactional(readOnly = true)
    public List<ApplicationSummary> needsReview(UUID userId) {
        return applications.findAllByUserIdAndNeedsReviewIsTrueOrderByUpdatedAtDesc(userId)
                .stream().map(ApplicationSummary::from).toList();
    }

    // ---------------------------------------------------------------- writes

    @Transactional
    public ApplicationSummary create(UUID userId, UpsertApplication request, ApplicationSource source,
                                     EventActor actor) {
        JobApplication application = new JobApplication(
                userId,
                request.companyName().trim(),
                normalizer.normalizeCompany(request.companyName()),
                request.positionTitle().trim(),
                normalizer.normalizePosition(request.positionTitle()),
                source);

        application.setRequisitionId(blankToNull(request.requisitionId()));
        application.setLocation(blankToNull(request.location()));
        application.setAtsPlatform(blankToNull(request.atsPlatform()));
        application.setNotes(blankToNull(request.notes()));
        application.setAppliedAt(request.appliedAt() == null ? Instant.now() : request.appliedAt());
        application.setStatus(request.status() == null ? stateMachine.initialStatus() : request.status());
        application.setLastEventAt(application.getAppliedAt());

        if (application.getCompanyNormalized().isBlank()) {
            throw new BadRequestException("Company name must contain at least one letter or number.");
        }

        rejectDuplicateRequisition(userId, application, null);

        JobApplication saved = applications.save(application);
        events.save(ApplicationEvent.created(saved, actor, saved.getAppliedAt()));

        if (saved.getStatus() != ApplicationStatus.APPLIED) {
            // Imported rows often arrive mid-funnel; record how they got there.
            events.save(ApplicationEvent.statusChanged(saved, ApplicationStatus.APPLIED, saved.getStatus(),
                    actor, null, "Status set when the application was added", saved.getAppliedAt()));
            if (saved.getStatus().isResponse()) {
                saved.markResponded(saved.getAppliedAt());
            }
        }
        return ApplicationSummary.from(saved);
    }

    @Transactional
    public ApplicationSummary update(UUID userId, UUID applicationId, UpsertApplication request) {
        JobApplication application = require(userId, applicationId);

        List<String> changes = new ArrayList<>();
        if (!application.getCompanyName().equals(request.companyName().trim())) {
            changes.add("company " + application.getCompanyName() + " -> " + request.companyName().trim());
            application.setCompanyName(request.companyName().trim());
            application.setCompanyNormalized(normalizer.normalizeCompany(request.companyName()));
        }
        if (!application.getPositionTitle().equals(request.positionTitle().trim())) {
            changes.add("position " + application.getPositionTitle() + " -> " + request.positionTitle().trim());
            application.setPositionTitle(request.positionTitle().trim());
            application.setPositionNormalized(normalizer.normalizePosition(request.positionTitle()));
        }
        application.setRequisitionId(blankToNull(request.requisitionId()));
        application.setLocation(blankToNull(request.location()));
        application.setAtsPlatform(blankToNull(request.atsPlatform()));
        application.setNotes(blankToNull(request.notes()));
        if (request.appliedAt() != null) {
            application.setAppliedAt(request.appliedAt());
        }

        rejectDuplicateRequisition(userId, application, applicationId);

        if (request.status() != null && request.status() != application.getStatus()) {
            changeStatusInternal(application, request.status(), EventActor.USER, null,
                    "Status changed by you", Instant.now());
        } else if (!changes.isEmpty()) {
            events.save(ApplicationEvent.of(application, ApplicationEventType.EDITED, EventActor.USER,
                    String.join("; ", changes), null, Instant.now()));
        }

        application.setLastEventAt(Instant.now());
        return ApplicationSummary.from(application);
    }

    @Transactional
    public ApplicationSummary changeStatus(UUID userId, UUID applicationId, ChangeStatus request) {
        JobApplication application = require(userId, applicationId);
        if (request.status() == application.getStatus()) {
            return ApplicationSummary.from(application);
        }
        String detail = request.note() == null || request.note().isBlank()
                ? "Status changed by you"
                : request.note().trim();
        changeStatusInternal(application, request.status(), EventActor.USER, null, detail, Instant.now());
        return ApplicationSummary.from(application);
    }

    /**
     * The one place the status column moves. Writes the history entry, keeps
     * {@code firstResponseAt} correct, and clears a review flag the user has
     * effectively resolved by setting a status by hand.
     */
    void changeStatusInternal(JobApplication application, ApplicationStatus to, EventActor actor,
                              UUID emailId, String detail, Instant when) {
        ApplicationStatus from = application.getStatus();
        application.setStatus(to);
        application.setLastEventAt(when);
        if (to.isResponse()) {
            application.markResponded(when);
        }
        events.save(ApplicationEvent.statusChanged(application, from, to, actor, emailId, detail, when));
    }

    @Transactional
    public ApplicationSummary addNote(UUID userId, UUID applicationId, AddNote request) {
        JobApplication application = require(userId, applicationId);
        String existing = application.getNotes();
        String appended = existing == null || existing.isBlank()
                ? request.note().trim()
                : existing + "\n" + request.note().trim();
        application.setNotes(appended);
        events.save(ApplicationEvent.of(application, ApplicationEventType.NOTE_ADDED, EventActor.USER,
                request.note().trim(), null, Instant.now()));
        return ApplicationSummary.from(application);
    }

    @Transactional
    public ApplicationSummary setArchived(UUID userId, UUID applicationId, boolean archived) {
        JobApplication application = require(userId, applicationId);
        application.setArchived(archived);
        events.save(ApplicationEvent.of(application, ApplicationEventType.EDITED, EventActor.USER,
                archived ? "Archived" : "Unarchived", null, Instant.now()));
        return ApplicationSummary.from(application);
    }

    @Transactional
    public ApplicationSummary resolveReview(UUID userId, UUID applicationId, String note) {
        JobApplication application = require(userId, applicationId);
        application.clearReview();
        events.save(ApplicationEvent.of(application, ApplicationEventType.REVIEW_RESOLVED, EventActor.USER,
                note == null || note.isBlank() ? "Marked as reviewed" : note.trim(), null, Instant.now()));
        return ApplicationSummary.from(application);
    }

    @Transactional
    public void delete(UUID userId, UUID applicationId) {
        JobApplication application = require(userId, applicationId);
        applications.delete(application);
    }

    /**
     * Folds {@code sourceId} into {@code targetId}: the target keeps the more
     * advanced status and the earlier applied date, the source's history is
     * re-pointed at the target, and the source row is removed.
     */
    @Transactional
    public ApplicationSummary merge(UUID userId, UUID targetId, UUID sourceId) {
        if (targetId.equals(sourceId)) {
            throw new BadRequestException("An application cannot be merged into itself.");
        }
        JobApplication target = require(userId, targetId);
        JobApplication source = require(userId, sourceId);

        // Copy what we need, then remove the source before writing the target:
        // the unique index on (user, company, requisition id) would otherwise
        // see both rows holding the same requisition id mid-transaction.
        ApplicationStatus sourceStatus = source.getStatus();
        Instant sourceAppliedAt = source.getAppliedAt();
        Instant sourceFirstResponseAt = source.getFirstResponseAt();
        String sourceRequisitionId = source.getRequisitionId();
        String sourceLocation = source.getLocation();
        String sourceAtsPlatform = source.getAtsPlatform();
        String sourceNotes = source.getNotes();
        String sourceLabel = source.getCompanyName() + " - " + source.getPositionTitle();

        applications.delete(source);
        applications.flush();

        if (sourceStatus.rank() > target.getStatus().rank() || sourceStatus.isTerminal()) {
            if (!target.getStatus().isTerminal()) {
                changeStatusInternal(target, sourceStatus, EventActor.USER, null,
                        "Adopted the more advanced status from the merged application", Instant.now());
            }
        }
        if (earlier(sourceAppliedAt, target.getAppliedAt())) {
            target.setAppliedAt(sourceAppliedAt);
        }
        if (earlier(sourceFirstResponseAt, target.getFirstResponseAt())) {
            target.setFirstResponseAt(sourceFirstResponseAt);
        }
        if (target.getRequisitionId() == null && sourceRequisitionId != null) {
            target.setRequisitionId(sourceRequisitionId);
        }
        if (target.getLocation() == null && sourceLocation != null) {
            target.setLocation(sourceLocation);
        }
        if (target.getAtsPlatform() == null && sourceAtsPlatform != null) {
            target.setAtsPlatform(sourceAtsPlatform);
        }
        if (sourceNotes != null && !sourceNotes.isBlank()) {
            target.setNotes(target.getNotes() == null || target.getNotes().isBlank()
                    ? sourceNotes
                    : target.getNotes() + "\n" + sourceNotes);
        }

        events.save(ApplicationEvent.of(target, ApplicationEventType.MERGED, EventActor.USER,
                "Merged in " + sourceLabel, null, Instant.now()));

        target.clearReview();
        target.setLastEventAt(Instant.now());
        return ApplicationSummary.from(target);
    }

    private boolean earlier(Instant candidate, Instant current) {
        return candidate != null && (current == null || candidate.isBefore(current));
    }

    private void rejectDuplicateRequisition(UUID userId, JobApplication application, UUID selfId) {
        if (application.getRequisitionId() == null) {
            return;
        }
        applications.findByUserIdAndCompanyNormalizedAndRequisitionId(
                        userId, application.getCompanyNormalized(), application.getRequisitionId())
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new BadRequestException(
                            "You already have an application for " + existing.getCompanyName()
                            + " with requisition " + existing.getRequisitionId() + ".");
                });
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
