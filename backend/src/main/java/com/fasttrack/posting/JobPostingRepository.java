package com.fasttrack.posting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, UUID> {

    Optional<JobPosting> findByIdAndUserId(UUID id, UUID userId);

    Optional<JobPosting> findFirstByUserIdAndContentHashOrderByCreatedAtDesc(UUID userId, String contentHash);

    List<JobPosting> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserId(UUID userId);
}
