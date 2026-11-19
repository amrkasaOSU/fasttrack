package com.fasttrack.email;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailCorrectionRepository extends JpaRepository<EmailCorrection, UUID> {

    List<EmailCorrection> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserId(UUID userId);

    /** Corrections where the category itself was wrong - the real error count. */
    @Query("""
            select count(c) from EmailCorrection c
            where c.userId = :userId and c.originalCategory <> c.correctedCategory
            """)
    long countCategoryCorrections(@Param("userId") UUID userId);

    @Query("""
            select count(c) from EmailCorrection c
            where c.userId = :userId and c.wasLlm = true and c.originalCategory <> c.correctedCategory
            """)
    long countLlmCategoryCorrections(@Param("userId") UUID userId);
}
