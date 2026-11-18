package com.fasttrack.email;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

    Optional<EmailMessage> findByIdAndUserId(UUID id, UUID userId);

    Optional<EmailMessage> findByUserIdAndGmailMessageId(UUID userId, String gmailMessageId);

    boolean existsByUserIdAndGmailMessageId(UUID userId, String gmailMessageId);

    List<EmailMessage> findAllByApplicationIdAndUserIdOrderByReceivedAtDesc(UUID applicationId, UUID userId);

    Page<EmailMessage> findAllByUserIdOrderByReceivedAtDesc(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);

    long countByUserIdAndPrefilterResult(UUID userId, PrefilterResult result);

    @Query("select count(e) from EmailMessage e where e.userId = :userId and e.receivedAt >= :since")
    long countSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
