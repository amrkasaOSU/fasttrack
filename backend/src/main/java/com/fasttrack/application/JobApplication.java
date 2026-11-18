package com.fasttrack.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One application: a single company and a single position.
 *
 * <p>The normalized company and position are stored alongside the display
 * values because they are what the duplicate matcher compares, and recomputing
 * them on every query would make the matcher's behaviour depend on code
 * version rather than on data.
 */
@Entity
@Table(name = "applications")
public class JobApplication {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "company_normalized", nullable = false)
    private String companyNormalized;

    @Column(name = "position_title", nullable = false)
    private String positionTitle;

    @Column(name = "position_normalized", nullable = false)
    private String positionNormalized;

    @Column(name = "requisition_id")
    private String requisitionId;

    @Column(name = "location")
    private String location;

    @Column(name = "ats_platform")
    private String atsPlatform;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private ApplicationSource source = ApplicationSource.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    @Column(name = "applied_at")
    private Instant appliedAt;

    /** When the company first replied in any way. Null means no response yet. */
    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "needs_review_reason")
    private String needsReviewReason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected JobApplication() {
        // for JPA
    }

    public JobApplication(UUID userId,
                          String companyName,
                          String companyNormalized,
                          String positionTitle,
                          String positionNormalized,
                          ApplicationSource source) {
        this.userId = userId;
        this.companyName = companyName;
        this.companyNormalized = companyNormalized;
        this.positionTitle = positionTitle;
        this.positionNormalized = positionNormalized;
        this.source = source;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Records the first reply from the company, if this is the first one. */
    public void markResponded(Instant when) {
        if (when != null && (firstResponseAt == null || when.isBefore(firstResponseAt))) {
            this.firstResponseAt = when;
        }
    }

    public void flagForReview(String reason) {
        this.needsReview = true;
        this.needsReviewReason = reason;
    }

    public void clearReview() {
        this.needsReview = false;
        this.needsReviewReason = null;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getCompanyNormalized() { return companyNormalized; }
    public void setCompanyNormalized(String companyNormalized) { this.companyNormalized = companyNormalized; }
    public String getPositionTitle() { return positionTitle; }
    public void setPositionTitle(String positionTitle) { this.positionTitle = positionTitle; }
    public String getPositionNormalized() { return positionNormalized; }
    public void setPositionNormalized(String positionNormalized) { this.positionNormalized = positionNormalized; }
    public String getRequisitionId() { return requisitionId; }
    public void setRequisitionId(String requisitionId) { this.requisitionId = requisitionId; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getAtsPlatform() { return atsPlatform; }
    public void setAtsPlatform(String atsPlatform) { this.atsPlatform = atsPlatform; }
    public ApplicationSource getSource() { return source; }
    public void setSource(ApplicationSource source) { this.source = source; }
    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
    public Instant getFirstResponseAt() { return firstResponseAt; }
    public void setFirstResponseAt(Instant firstResponseAt) { this.firstResponseAt = firstResponseAt; }
    public Instant getLastEventAt() { return lastEventAt; }
    public void setLastEventAt(Instant lastEventAt) { this.lastEventAt = lastEventAt; }
    public boolean isNeedsReview() { return needsReview; }
    public String getNeedsReviewReason() { return needsReviewReason; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
