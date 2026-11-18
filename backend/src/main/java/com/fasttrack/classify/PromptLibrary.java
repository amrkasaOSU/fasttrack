package com.fasttrack.classify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads versioned prompt templates from {@code prompts/} at the repository
 * root (copied onto the classpath by the build).
 *
 * <p>Prompts are files, not string literals, so a prompt change shows up as a
 * reviewable diff; the version is stored on every classification and forms
 * part of the cache key, so results from different prompt versions are never
 * mixed.
 */
@Component
public class PromptLibrary {

    public static final String CLASSIFY_VERSION = "v1";
    private static final String CLASSIFY_TEMPLATE = "prompts/classify_v1.txt";
    private static final DateTimeFormatter RECEIVED =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Map<String, String[]> cache = new ConcurrentHashMap<>();

    /** The system half of the classification prompt. */
    public String classifySystemPrompt() {
        return sections(CLASSIFY_TEMPLATE)[0];
    }

    /** The user half, with the email filled in. */
    public String classifyUserPrompt(EmailContent email) {
        String template = sections(CLASSIFY_TEMPLATE)[1];
        return template
                .replace("{{from}}", display(email.senderName(), email.senderEmail()))
                .replace("{{subject}}", nullSafe(email.subject()))
                .replace("{{received}}", email.receivedAt() == null ? "unknown" : RECEIVED.format(email.receivedAt()))
                .replace("{{body}}", email.truncatedBody());
    }

    /**
     * Splits a template into its SYSTEM: and USER: halves, dropping the
     * leading {@code #} comment lines that document the version.
     */
    private String[] sections(String path) {
        return cache.computeIfAbsent(path, key -> {
            String content = read(key);
            int systemAt = content.indexOf("SYSTEM:");
            int userAt = content.indexOf("USER:");
            if (systemAt < 0 || userAt < systemAt) {
                throw new IllegalStateException("Prompt template " + key + " must contain SYSTEM: and USER: sections");
            }
            return new String[]{
                    content.substring(systemAt + "SYSTEM:".length(), userAt).trim(),
                    content.substring(userAt + "USER:".length()).trim()
            };
        });
    }

    private String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Prompt template not found on the classpath: " + path, e);
        }
    }

    private String display(String name, String address) {
        if (name == null || name.isBlank()) {
            return nullSafe(address);
        }
        return name + " <" + nullSafe(address) + ">";
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
