package com.fasttrack.fit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FitCheckRepository extends JpaRepository<FitCheck, UUID> {

    Optional<FitCheck> findByIdAndUserId(UUID id, UUID userId);

    List<FitCheck> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<FitCheck> findAllByUserIdAndApplicationIdOrderByCreatedAtDesc(UUID userId, UUID applicationId);

    List<FitCheck> findAllByUserIdAndApplicationIdIsNotNull(UUID userId);

    long countByUserId(UUID userId);
}
