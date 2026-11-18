package com.fasttrack.llm;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmInvocationRepository extends JpaRepository<LlmInvocation, UUID> {

    long countByUserIdAndSuccessIsFalse(UUID userId);

    long countByUserId(UUID userId);
}
