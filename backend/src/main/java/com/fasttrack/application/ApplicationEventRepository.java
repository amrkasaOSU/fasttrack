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

    /**
     * Every status change the user has ever had. Used by the dashboard to work
     * out how far each application really got, including ones that later ended
     * in a rejection.
     */
    List<ApplicationEvent> findAllByUserIdAndEventType(UUID userId, ApplicationEventType eventType);
}
