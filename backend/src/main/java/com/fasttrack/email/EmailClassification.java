package com.fasttrack.email;

import com.fasttrack.classify.ClassificationMethod;
import com.fasttrack.classify.ClassificationResult;
import com.fasttrack.classify.EmailCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** The stored classification for one email. One row per email. */
@Entity
@Table(name = "email_classifications")
public class EmailClassification {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email_id", nullable = false)
    private UUID emailId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private EmailCategory category;

    @Column(name = "company")
    private String company;

    @Column(name = "position_title")
    private String positionTitle;

    @Column(name = "requisition_id")
    private String requisitionId;

    @Column(name = "interview_datetime")
    private Instant interviewDatetime;

    @Column(name = "assessment_deadline")
    private Instant assessmentDeadline;

    @Column(name = "platform")
    private String platform;

    @Column(name = "confidence", nullable = false)
    private BigDecimal confidence = BigDecimal.ZERO;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false)
    private ClassificationMethod method;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EmailClassification() {
        // for JPA
    }

    public static EmailClassification from(UUID emailId, UUID userId, ClassificationResult result) {
        EmailClassification entity = new EmailClassification();
        entity.emailId = emailId;
        entity.userId = userId;
        entity.apply(result);
        return entity;
    }

    /** Overwrites the stored result, used when a classification is re-run. */
    public void apply(ClassificationResult result) {
        this.category = result.category();
        this.company = result.company();
        this.positionTitle = result.positionTitle();
        this.requisitionId = result.requisitionId();
        this.interviewDatetime = result.interviewDatetime();
        this.assessmentDeadline = result.assessmentDeadline();
        this.platform = result.platform();
        this.confidence = BigDecimal.valueOf(result.confidence()).setScale(3, RoundingMode.HALF_UP);
        this.reason = result.reason();
        this.method = result.method();
        this.model = result.model();
        this.promptVersion = result.promptVersion();
        this.cacheHit = result.cacheHit();
        this.latencyMs = (int) Math.min(result.latencyMs(), Integer.MAX_VALUE);
        this.needsReview = result.needsReview();
    }

    /** Applies a user's correction. The original values stay in email_corrections. */
    public void correctTo(EmailCategory category, String company, String positionTitle) {
        this.category = category;
        if (company != null && !company.isBlank()) {
            this.company = company.trim();
        }
        if (positionTitle != null && !positionTitle.isBlank()) {
            this.positionTitle = positionTitle.trim();
        }
        this.method = ClassificationMethod.MANUAL;
        this.confidence = BigDecimal.ONE;
        this.needsReview = false;
        this.reason = "Corrected by you";
    }

    public UUID getId() { return id; }
    public UUID getEmailId() { return emailId; }
    public UUID getUserId() { return userId; }
    public EmailCategory getCategory() { return category; }
    public String getCompany() { return company; }
    public String getPositionTitle() { return positionTitle; }
    public String getRequisitionId() { return requisitionId; }
    public Instant getInterviewDatetime() { return interviewDatetime; }
    public Instant getAssessmentDeadline() { return assessmentDeadline; }
    public String getPlatform() { return platform; }
    public double getConfidence() { return confidence.doubleValue(); }
    public String getReason() { return reason; }
    public ClassificationMethod getMethod() { return method; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public boolean isCacheHit() { return cacheHit; }
    public Integer getLatencyMs() { return latencyMs; }
    public boolean isNeedsReview() { return needsReview; }
    public Instant getCreatedAt() { return createdAt; }
}
