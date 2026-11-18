package com.fasttrack.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry in an application's history. Append-only: rows are inserted and
 * never updated, so the timeline shown on the application page is the true
 * record of what happened and which email caused it.
 */
@Entity
@Table(name = "application_events")
public class ApplicationEvent {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private ApplicationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private ApplicationStatus toStatus;

    /** The email that caused this entry, when there was one. */
    @Column(name = "email_id")
    private UUID emailId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false)
    private EventActor actor = EventActor.SYSTEM;

    @Column(name = "detail")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ApplicationEvent() {
        // for JPA
    }

    private ApplicationEvent(UUID applicationId, UUID userId, ApplicationEventType type, EventActor actor) {
        this.applicationId = applicationId;
        this.userId = userId;
        this.eventType = type;
        this.actor = actor;
    }

    public static ApplicationEvent created(JobApplication application, EventActor actor, Instant when) {
        ApplicationEvent event = new ApplicationEvent(
                application.getId(), application.getUserId(), ApplicationEventType.CREATED, actor);
        event.toStatus = application.getStatus();
        event.occurredAt = when == null ? Instant.now() : when;
        event.detail = "Application created";
        return event;
    }

    public static ApplicationEvent statusChanged(JobApplication application,
                                                 ApplicationStatus from,
                                                 ApplicationStatus to,
                                                 EventActor actor,
                                                 UUID emailId,
                                                 String detail,
                                                 Instant when) {
        ApplicationEvent event = new ApplicationEvent(
                application.getId(), application.getUserId(), ApplicationEventType.STATUS_CHANGED, actor);
        event.fromStatus = from;
        event.toStatus = to;
        event.emailId = emailId;
        event.detail = detail;
        event.occurredAt = when == null ? Instant.now() : when;
        return event;
    }

    public static ApplicationEvent of(JobApplication application,
                                      ApplicationEventType type,
                                      EventActor actor,
                                      String detail,
                                      UUID emailId,
                                      Instant when) {
        ApplicationEvent event = new ApplicationEvent(
                application.getId(), application.getUserId(), type, actor);
        event.detail = detail;
        event.emailId = emailId;
        event.occurredAt = when == null ? Instant.now() : when;
        return event;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public UUID getUserId() { return userId; }
    public ApplicationEventType getEventType() { return eventType; }
    public ApplicationStatus getFromStatus() { return fromStatus; }
    public ApplicationStatus getToStatus() { return toStatus; }
    public UUID getEmailId() { return emailId; }
    public EventActor getActor() { return actor; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
