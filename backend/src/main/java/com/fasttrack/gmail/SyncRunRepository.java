package com.fasttrack.gmail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, UUID> {

    Optional<SyncRun> findFirstByUserIdOrderByStartedAtDesc(UUID userId);

    List<SyncRun> findTop20ByUserIdOrderByStartedAtDesc(UUID userId);

    long countByUserId(UUID userId);
}
