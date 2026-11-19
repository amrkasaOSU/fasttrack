package com.fasttrack;

import com.fasttrack.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FastTrack - AI-powered internship application tracker.
 *
 * <p>The application is a modular monolith: one Spring Boot process, packages
 * split by domain. It is designed to start and serve traffic with no Gmail
 * credentials and no LLM key - those features report a "not configured" state
 * instead of failing.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
@EnableAsync
public class FastTrackApplication {

    public static void main(String[] args) {
        SpringApplication.run(FastTrackApplication.class, args);
    }
}
