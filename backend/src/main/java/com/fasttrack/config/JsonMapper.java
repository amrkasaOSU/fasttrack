package com.fasttrack.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * A configured {@link ObjectMapper} usable from places Spring cannot inject
 * into (filters, static helpers). The Spring-managed bean in
 * {@link JacksonConfig} is built from the same settings.
 */
public final class JsonMapper {

    private static final ObjectMapper SHARED = build();

    private JsonMapper() {
    }

    public static ObjectMapper shared() {
        return SHARED;
    }

    public static ObjectMapper build() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
