package com.fasttrack.gmail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GmailAccountRepository extends JpaRepository<GmailAccount, UUID> {

    Optional<GmailAccount> findByUserId(UUID userId);

    /** Accounts the scheduler should sync: connected, not revoked, not already running. */
    @Query("""
            select a from GmailAccount a
            where a.refreshTokenCipher is not null
              and a.revokedAt is null
              and a.syncStatus <> com.fasttrack.gmail.GmailSyncStatus.RUNNING
            """)
    List<GmailAccount> findSyncable();
}
