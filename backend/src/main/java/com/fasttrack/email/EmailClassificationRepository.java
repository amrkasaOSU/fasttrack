package com.fasttrack.email;

import com.fasttrack.classify.EmailCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailClassificationRepository extends JpaRepository<EmailClassification, UUID> {

    Optional<EmailClassification> findByEmailId(UUID emailId);

    List<EmailClassification> findAllByUserIdAndNeedsReviewIsTrue(UUID userId);

    long countByUserIdAndNeedsReviewIsTrue(UUID userId);

    long countByUserIdAndCategory(UUID userId, EmailCategory category);

    /** Rows backing the live-accuracy metric: how many were produced by the LLM. */
    @Query("select count(c) from EmailClassification c where c.userId = :userId and c.method = com.fasttrack.classify.ClassificationMethod.LLM")
    long countLlmClassifications(@Param("userId") UUID userId);

    long countByUserId(UUID userId);
}
