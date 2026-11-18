package com.fasttrack.llm;

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
 * One row per LLM call that actually left the process. Cache hits and
 * rule-based classifications are not calls and are not recorded here.
 *
 * <p>This table is where the measured latency, retry and fallback numbers in
 * RESULTS.md come from - they are observed, not estimated.
 */
@Entity
@Table(name = "llm_invocations")
public class LlmInvocation {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    private LlmPurpose purpose;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "success", nullable = false)
    private boolean success = true;

    @Column(name = "retried", nullable = false)
    private boolean retried;

    @Column(name = "fell_back", nullable = false)
    private boolean fellBack;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LlmInvocation() {
        // for JPA
    }

    public LlmInvocation(UUID userId, LlmPurpose purpose, String model, String promptVersion) {
        this.userId = userId;
        this.purpose = purpose;
        this.model = model;
        this.promptVersion = promptVersion;
    }

    public LlmInvocation succeeded(long latencyMs, Integer promptTokens, Integer completionTokens, boolean retried) {
        this.success = true;
        this.fellBack = false;
        this.latencyMs = (int) Math.min(latencyMs, Integer.MAX_VALUE);
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.retried = retried;
        return this;
    }

    public LlmInvocation failed(long latencyMs, boolean retried) {
        this.success = false;
        this.fellBack = true;
        this.retried = retried;
        this.latencyMs = (int) Math.min(latencyMs, Integer.MAX_VALUE);
        return this;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public LlmPurpose getPurpose() { return purpose; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public boolean isCacheHit() { return cacheHit; }
    public boolean isSuccess() { return success; }
    public boolean isRetried() { return retried; }
    public boolean isFellBack() { return fellBack; }
    public int getLatencyMs() { return latencyMs; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public Instant getCreatedAt() { return createdAt; }
}
