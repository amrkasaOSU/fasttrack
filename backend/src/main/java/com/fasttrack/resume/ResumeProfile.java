package com.fasttrack.resume;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * A resume, structured. Produced by the LLM from the extracted PDF text, or by
 * the heuristic parser when no model is configured, and always shown to the
 * user for correction before it is used.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeProfile(
        List<String> languages,
        List<String> frameworks,
        List<String> databases,
        List<String> tools,
        List<String> cloud,
        List<ExperienceEntry> experience,
        List<ProjectEntry> projects,
        Education education,
        @JsonAlias("work_authorization") String workAuthorization) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExperienceEntry(String title, String organization, String period, List<String> highlights) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectEntry(String name, String description, List<String> technologies) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(
            String school,
            String degree,
            String field,
            @JsonAlias("expected_graduation") String expectedGraduation) {}

    public static ResumeProfile empty() {
        return new ResumeProfile(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null);
    }

    /** Every skill the resume claims, across all five categories. */
    public List<String> allSkills() {
        List<String> all = new ArrayList<>();
        addAll(all, languages);
        addAll(all, frameworks);
        addAll(all, databases);
        addAll(all, tools);
        addAll(all, cloud);
        return all;
    }

    /**
     * The free text of the experience and project entries, used for the
     * relevance rating and the ATS keyword check. Nothing is invented: this is
     * only what the resume itself says.
     */
    public String narrative() {
        StringBuilder text = new StringBuilder();
        if (experience != null) {
            for (ExperienceEntry entry : experience) {
                append(text, entry.title());
                append(text, entry.organization());
                if (entry.highlights() != null) {
                    entry.highlights().forEach(highlight -> append(text, highlight));
                }
            }
        }
        if (projects != null) {
            for (ProjectEntry project : projects) {
                append(text, project.name());
                append(text, project.description());
                if (project.technologies() != null) {
                    project.technologies().forEach(technology -> append(text, technology));
                }
            }
        }
        return text.toString().trim();
    }

    private static void append(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(value).append('\n');
        }
    }

    private static void addAll(List<String> target, List<String> source) {
        if (source != null) {
            source.stream().filter(value -> value != null && !value.isBlank()).forEach(target::add);
        }
    }
}
