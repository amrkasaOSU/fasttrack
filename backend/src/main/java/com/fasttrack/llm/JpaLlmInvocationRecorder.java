package com.fasttrack.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Persists LLM call metrics. A failure here never fails the classification. */
@Component
public class JpaLlmInvocationRecorder implements LlmInvocationRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaLlmInvocationRecorder.class);

    private final LlmInvocationRepository repository;

    public JpaLlmInvocationRecorder(LlmInvocationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(LlmInvocation invocation) {
        try {
            repository.save(invocation);
        } catch (RuntimeException ex) {
            log.warn("Could not record LLM invocation metrics: {}", ex.getClass().getSimpleName());
        }
    }
}
