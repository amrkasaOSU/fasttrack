package com.fasttrack.application.dto;

import com.fasttrack.application.ApplicationEvent;
import com.fasttrack.application.ApplicationEventType;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.JobApplication;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies for the applications API. */
public final class ApplicationResponses {

    private ApplicationResponses() {
    }

    public record ApplicationSummary(
            UUID id,
            String companyName,
            String positionTitle,
            String requisitionId,
            String location,
            String atsPlatform,
            ApplicationStatus status,
            ApplicationSource source,
            Instant appliedAt,
            Instant firstResponseAt,
            Instant lastEventAt,
            boolean needsReview,
            String needsReviewReason,
            boolean archived,
            Integer daysSinceApplied,
            Integer daysToFirstResponse,
            Instant createdAt,
            Instant updatedAt) {

        public static ApplicationSummary from(JobApplication a) {
            return new ApplicationSummary(
                    a.getId(), a.getCompanyName(), a.getPositionTitle(), a.getRequisitionId(),
                    a.getLocation(), a.getAtsPlatform(), a.getStatus(), a.getSource(),
                    a.getAppliedAt(), a.getFirstResponseAt(), a.getLastEventAt(),
                    a.isNeedsReview(), a.getNeedsReviewReason(), a.isArchived(),
                    daysBetween(a.getAppliedAt(), Instant.now()),
                    daysBetween(a.getAppliedAt(), a.getFirstResponseAt()),
                    a.getCreatedAt(), a.getUpdatedAt());
        }

        private static Integer daysBetween(Instant from, Instant to) {
            if (from == null || to == null) {
                return null;
            }
            long days = java.time.Duration.between(from, to).toDays();
            return days < 0 ? null : (int) days;
        }
    }

    public record TimelineEntry(
            UUID id,
            ApplicationEventType type,
            ApplicationStatus fromStatus,
            ApplicationStatus toStatus,
            EventActor actor,
            UUID emailId,
            String detail,
            Instant occurredAt) {

        public static TimelineEntry from(ApplicationEvent e) {
            return new TimelineEntry(e.getId(), e.getEventType(), e.getFromStatus(), e.getToStatus(),
                    e.getActor(), e.getEmailId(), e.getDetail(), e.getOccurredAt());
        }
    }

    public record ApplicationDetail(
            ApplicationSummary application,
            List<TimelineEntry> timeline,
            String notes) {}

    /** A page of results, shaped for the table view. */
    public record ApplicationPage(
            List<ApplicationSummary> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {}
}
