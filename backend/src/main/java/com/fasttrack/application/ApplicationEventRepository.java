package com.fasttrack.application;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only history. Nothing in the codebase updates or deletes a row here. */
public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, UUID> {

    List<ApplicationEvent> findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(
            UUID applicationId, UUID userId);

    List<ApplicationEvent> findTop50ByUserIdOrderByOccurredAtDesc(UUID userId);

    long countByUserId(UUID userId);
}
