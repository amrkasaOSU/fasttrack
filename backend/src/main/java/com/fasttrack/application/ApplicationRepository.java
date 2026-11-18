package com.fasttrack.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every query here is scoped by {@code userId}. There is deliberately no
 * finder that takes an id alone: a caller cannot accidentally load another
 * user's application.
 */
public interface ApplicationRepository extends JpaRepository<JobApplication, UUID> {

    Optional<JobApplication> findByIdAndUserId(UUID id, UUID userId);

    List<JobApplication> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserId(UUID userId);

    long countByUserIdAndNeedsReviewIsTrue(UUID userId);

    List<JobApplication> findAllByUserIdAndNeedsReviewIsTrueOrderByUpdatedAtDesc(UUID userId);

    Optional<JobApplication> findByUserIdAndCompanyNormalizedAndRequisitionId(
            UUID userId, String companyNormalized, String requisitionId);

    List<JobApplication> findAllByUserIdAndCompanyNormalized(UUID userId, String companyNormalized);

    /**
     * The applications table view: optional free-text search over company and
     * position, optional status filter, newest activity first.
     */
    @Query("""
            select a from JobApplication a
            where a.userId = :userId
              and (:includeArchived = true or a.archived = false)
              and (:status is null or a.status = :status)
              and (:search is null
                   or lower(a.companyName) like :search
                   or lower(a.positionTitle) like :search
                   or lower(coalesce(a.location, '')) like :search)
            """)
    Page<JobApplication> search(@Param("userId") UUID userId,
                                @Param("status") ApplicationStatus status,
                                @Param("search") String search,
                                @Param("includeArchived") boolean includeArchived,
                                Pageable pageable);

    /** Applications with no reply at all, applied before the given instant. */
    @Query("""
            select a from JobApplication a
            where a.userId = :userId
              and a.archived = false
              and a.firstResponseAt is null
              and a.status = com.fasttrack.application.ApplicationStatus.APPLIED
              and a.appliedAt is not null
              and a.appliedAt < :before
            order by a.appliedAt asc
            """)
    List<JobApplication> findSilentSince(@Param("userId") UUID userId, @Param("before") Instant before);
}
