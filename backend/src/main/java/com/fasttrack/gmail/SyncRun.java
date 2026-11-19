package com.fasttrack.gmail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One sync, with the counters that make throughput and prefilter savings
 * measurable rather than estimated (RESULTS.md).
 */
@Entity
@Table(name = "sync_runs")
public class SyncRun {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false)
    private SyncTrigger triggerSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SyncStatus status = SyncStatus.RUNNING;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "messages_fetched", nullable = false)
    private int messagesFetched;

    @Column(name = "messages_new", nullable = false)
    private int messagesNew;

    @Column(name = "messages_dropped", nullable = false)
    private int messagesDropped;

    @Column(name = "messages_classified", nullable = false)
    private int messagesClassified;

    @Column(name = "llm_calls", nullable = false)
    private int llmCalls;

    @Column(name = "cache_hits", nullable = false)
    private int cacheHits;

    @Column(name = "applications_created", nullable = false)
    private int applicationsCreated;

    @Column(name = "applications_updated", nullable = false)
    private int applicationsUpdated;

    @Column(name = "error_message")
    private String errorMessage;

    protected SyncRun() {
        // for JPA
    }

    public SyncRun(UUID userId, SyncTrigger trigger) {
        this.userId = userId;
        this.triggerSource = trigger;
    }

    public void countFetched() { messagesFetched++; }
    public void countNew() { messagesNew++; }
    public void countDropped() { messagesDropped++; }
    public void countClassified() { messagesClassified++; }
    public void countLlmCall() { llmCalls++; }
    public void countCacheHit() { cacheHits++; }
    public void countApplicationCreated() { applicationsCreated++; }
    public void countApplicationUpdated() { applicationsUpdated++; }

    public void finished(SyncStatus status, String errorMessage) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    /** Emails processed per minute, for the measured throughput number. */
    public double messagesPerMinute() {
        if (finishedAt == null) {
            return 0;
        }
        double seconds = Duration.between(startedAt, finishedAt).toMillis() / 1000.0;
        return seconds <= 0 ? messagesFetched * 60.0 : messagesFetched / seconds * 60.0;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public SyncTrigger getTriggerSource() { return triggerSource; }
    public SyncStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getMessagesFetched() { return messagesFetched; }
    public int getMessagesNew() { return messagesNew; }
    public int getMessagesDropped() { return messagesDropped; }
    public int getMessagesClassified() { return messagesClassified; }
    public int getLlmCalls() { return llmCalls; }
    public int getCacheHits() { return cacheHits; }
    public int getApplicationsCreated() { return applicationsCreated; }
    public int getApplicationsUpdated() { return applicationsUpdated; }
    public String getErrorMessage() { return errorMessage; }
}
