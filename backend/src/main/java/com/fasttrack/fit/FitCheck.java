package com.fasttrack.fit;

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

/**
 * One scored resume against one posting.
 *
 * <p>Component scores are stored next to the total so the arithmetic stays
 * checkable later, and so the dashboard can compare outcome by fit band
 * without recomputing anything.
 */
@Entity
@Table(name = "fit_checks")
public class FitCheck {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @Column(name = "job_posting_id", nullable = false)
    private UUID jobPostingId;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "overall_score", nullable = false)
    private int overallScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", nullable = false)
    private FitBand band;

    @Column(name = "required_coverage", nullable = false)
    private BigDecimal requiredCoverage = BigDecimal.ZERO;

    @Column(name = "preferred_coverage", nullable = false)
    private BigDecimal preferredCoverage = BigDecimal.ZERO;

    @Column(name = "experience_score", nullable = false)
    private BigDecimal experienceScore = BigDecimal.ZERO;

    @Column(name = "eligibility_score", nullable = false)
    private BigDecimal eligibilityScore = BigDecimal.ZERO;

    @Column(name = "ats_keyword_coverage", nullable = false)
    private BigDecimal atsKeywordCoverage = BigDecimal.ZERO;

    @Column(name = "hard_mismatch", nullable = false)
    private boolean hardMismatch;

    @Column(name = "scoring_version", nullable = false)
    private String scoringVersion = FitScore.VERSION;

    /** The full breakdown, as JSON, so the page can be re-rendered exactly. */
    @Column(name = "details_json", nullable = false)
    private String detailsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FitCheck() {
        // for JPA
    }

    public FitCheck(UUID userId, UUID resumeId, UUID jobPostingId, FitScore score, String detailsJson) {
        this.userId = userId;
        this.resumeId = resumeId;
        this.jobPostingId = jobPostingId;
        this.overallScore = score.overall();
        this.band = score.band();
        this.requiredCoverage = scale(score.requiredCoverage());
        this.preferredCoverage = scale(score.preferredCoverage());
        this.experienceScore = scale(score.experienceScore());
        this.eligibilityScore = scale(score.eligibilityScore());
        this.atsKeywordCoverage = scale(score.atsKeywordCoverage());
        this.hardMismatch = score.hardMismatch();
        this.scoringVersion = score.scoringVersion();
        this.detailsJson = detailsJson;
    }

    private static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    public void linkTo(UUID applicationId) {
        this.applicationId = applicationId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getResumeId() { return resumeId; }
    public UUID getJobPostingId() { return jobPostingId; }
    public UUID getApplicationId() { return applicationId; }
    public int getOverallScore() { return overallScore; }
    public FitBand getBand() { return band; }
    public double getRequiredCoverage() { return requiredCoverage.doubleValue(); }
    public double getPreferredCoverage() { return preferredCoverage.doubleValue(); }
    public double getExperienceScore() { return experienceScore.doubleValue(); }
    public double getEligibilityScore() { return eligibilityScore.doubleValue(); }
    public double getAtsKeywordCoverage() { return atsKeywordCoverage.doubleValue(); }
    public boolean isHardMismatch() { return hardMismatch; }
    public String getScoringVersion() { return scoringVersion; }
    public String getDetailsJson() { return detailsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
