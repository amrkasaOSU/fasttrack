package com.fasttrack.email;

import com.fasttrack.classify.EmailCategory;
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
 * A user disagreeing with the classifier.
 *
 * <p>This table is the only honest accuracy signal the product has: the
 * offline evaluation runs on synthetic email, while these rows come from real
 * mail that a real person judged. The original values are kept alongside the
 * correction so the comparison stays possible after the classification row
 * has been overwritten.
 */
@Entity
@Table(name = "email_corrections")
public class EmailCorrection {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "email_id", nullable = false)
    private UUID emailId;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_category", nullable = false)
    private EmailCategory originalCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_category", nullable = false)
    private EmailCategory correctedCategory;

    @Column(name = "original_company")
    private String originalCompany;

    @Column(name = "corrected_company")
    private String correctedCompany;

    @Column(name = "original_position")
    private String originalPosition;

    @Column(name = "corrected_position")
    private String correctedPosition;

    /** Whether the thing being corrected came from the model or from the rules. */
    @Column(name = "was_llm", nullable = false)
    private boolean wasLlm = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EmailCorrection() {
        // for JPA
    }

    public EmailCorrection(UUID userId, UUID emailId, EmailClassification before,
                           EmailCategory correctedCategory, String correctedCompany,
                           String correctedPosition) {
        this.userId = userId;
        this.emailId = emailId;
        this.originalCategory = before.getCategory();
        this.originalCompany = before.getCompany();
        this.originalPosition = before.getPositionTitle();
        this.correctedCategory = correctedCategory;
        this.correctedCompany = correctedCompany;
        this.correctedPosition = correctedPosition;
        this.wasLlm = before.getMethod() == com.fasttrack.classify.ClassificationMethod.LLM;
    }

    /** True when the user changed the category, not just the company or title. */
    public boolean changedCategory() {
        return originalCategory != correctedCategory;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getEmailId() { return emailId; }
    public EmailCategory getOriginalCategory() { return originalCategory; }
    public EmailCategory getCorrectedCategory() { return correctedCategory; }
    public String getOriginalCompany() { return originalCompany; }
    public String getCorrectedCompany() { return correctedCompany; }
    public String getOriginalPosition() { return originalPosition; }
    public String getCorrectedPosition() { return correctedPosition; }
    public boolean isWasLlm() { return wasLlm; }
    public Instant getCreatedAt() { return createdAt; }
}
