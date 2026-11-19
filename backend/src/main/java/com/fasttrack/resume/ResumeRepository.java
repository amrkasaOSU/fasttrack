package com.fasttrack.resume;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

    Optional<Resume> findByIdAndUserId(UUID id, UUID userId);

    List<Resume> findAllByUserIdOrderByVersionDesc(UUID userId);

    Optional<Resume> findByUserIdAndActiveIsTrue(UUID userId);

    @Query("select coalesce(max(r.version), 0) from Resume r where r.userId = :userId")
    int highestVersion(@Param("userId") UUID userId);

    long countByUserId(UUID userId);
}
