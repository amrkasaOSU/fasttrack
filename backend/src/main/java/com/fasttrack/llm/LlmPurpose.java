package com.fasttrack.llm;

/** What an LLM call was for. Mirrors the check constraint on llm_invocations. */
public enum LlmPurpose {
    CLASSIFY_EMAIL,
    PARSE_RESUME,
    PARSE_POSTING,
    RATE_EXPERIENCE
}
