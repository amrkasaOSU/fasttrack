package com.fasttrack.posting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A job posting, structured. Produced by the LLM from the pasted or fetched
 * text, or by the heuristic parser when no model is configured.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostingRequirements(
        String title,
        String company,
        @JsonProperty("required_skills") List<String> requiredSkills,
        @JsonProperty("preferred_skills") List<String> preferredSkills,
        List<String> responsibilities,
        @JsonProperty("min_education") String minEducation,
        @JsonProperty("graduation_window") String graduationWindow,
        @JsonProperty("work_authorization_requirements") String workAuthorizationRequirements,
        String location,
        @JsonProperty("internship_season") String internshipSeason) {

    public static PostingRequirements empty() {
        return new PostingRequirements(null, null, List.of(), List.of(), List.of(),
                null, null, null, null, null);
    }

    public List<String> requiredOrEmpty() {
        return requiredSkills == null ? List.of() : requiredSkills;
    }

    public List<String> preferredOrEmpty() {
        return preferredSkills == null ? List.of() : preferredSkills;
    }

    public List<String> responsibilitiesOrEmpty() {
        return responsibilities == null ? List.of() : responsibilities;
    }
}
