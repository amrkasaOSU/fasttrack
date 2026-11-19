package com.fasttrack.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.fit.SkillDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading a posting without a model: headings, skills, and eligibility lines. */
class HeuristicPostingParserTest {

    private final HeuristicPostingParser parser = new HeuristicPostingParser(new SkillDictionary());

    private static final String POSTING = """
            Software Engineer Intern - Summer 2027
            Location: Columbus, OH

            Responsibilities
            - Build and test REST APIs in Java and Spring Boot
            - Work with PostgreSQL schemas and write efficient queries

            Requirements
            - Pursuing a Bachelor's degree in Computer Science
            - Coursework or project experience with Java
            - Familiarity with SQL
            - Must be authorised to work in the US without sponsorship

            Preferred qualifications
            - Experience with Docker
            - Exposure to Kubernetes

            Graduating December 2026 - June 2027.
            """;

    @Test
    @DisplayName("required and preferred skills are read from their own sections")
    void splitsRequiredAndPreferred() {
        var requirements = parser.parse(POSTING);

        assertThat(requirements.requiredSkills()).contains("Java", "SQL");
        assertThat(requirements.preferredSkills()).contains("Docker", "Kubernetes");
        assertThat(requirements.requiredSkills()).doesNotContain("Docker", "Kubernetes");
    }

    @Test
    @DisplayName("responsibilities are read as bullets")
    void readsResponsibilities() {
        var requirements = parser.parse(POSTING);

        assertThat(requirements.responsibilities()).isNotEmpty();
        assertThat(requirements.responsibilities().get(0)).contains("REST APIs");
    }

    @Test
    @DisplayName("education, graduation window, work authorisation, season and location are picked up")
    void readsEligibilityFields() {
        var requirements = parser.parse(POSTING);

        assertThat(requirements.minEducation()).contains("Bachelor");
        assertThat(requirements.graduationWindow()).contains("2026").contains("2027");
        assertThat(requirements.workAuthorizationRequirements()).contains("without sponsorship");
        assertThat(requirements.internshipSeason()).isEqualTo("Summer 2027");
        assertThat(requirements.location()).isEqualTo("Columbus, OH");
    }

    @Test
    @DisplayName("a posting with no sections still yields the skills it mentions")
    void handlesUnstructuredPostings() {
        var requirements = parser.parse(
                "We need someone comfortable with Python and AWS to help build data pipelines.");

        assertThat(requirements.requiredSkills()).contains("Python", "AWS");
        assertThat(requirements.preferredSkills()).isEmpty();
    }

    @Test
    @DisplayName("a posting that states nothing yields nothing rather than guesses")
    void emptyPosting() {
        var requirements = parser.parse(
                "We are looking for bright, motivated students to join our growing team.");

        assertThat(requirements.requiredSkills()).isEmpty();
        assertThat(requirements.minEducation()).isNull();
        assertThat(requirements.graduationWindow()).isNull();
        assertThat(parser.parse("").requiredSkills()).isEmpty();
        assertThat(parser.parse(null).requiredSkills()).isEmpty();
    }

    @Test
    @DisplayName("spellings are folded so the scorer sees canonical names")
    void foldsSpellings() {
        var requirements = parser.parse("""
                Requirements
                - Strong Postgres experience
                - Golang
                - k8s
                """);

        assertThat(requirements.requiredSkills()).contains("PostgreSQL", "Go", "Kubernetes");
    }
}
