package com.fasttrack.fit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.posting.PostingRequirements;
import com.fasttrack.resume.ResumeProfile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scoring arithmetic. These tests pin the published weights, the two
 * caps, and the rule that a suggestion never asks the user to claim something
 * they cannot back up.
 */
class FitScoreCalculatorTest {

    private final SkillDictionary dictionary = new SkillDictionary();
    private final FitScoreCalculator calculator = new FitScoreCalculator(dictionary);

    private ResumeProfile resume(List<String> languages, List<String> frameworks,
                                 String graduation, String workAuthorization) {
        return new ResumeProfile(languages, frameworks, List.of("PostgreSQL"), List.of("Git"),
                List.of("AWS"),
                List.of(new ResumeProfile.ExperienceEntry("SWE Intern", "Acme", "2026",
                        List.of("Built REST endpoints in Spring Boot backed by PostgreSQL"))),
                List.of(),
                new ResumeProfile.Education("OSU", "Bachelor of Science", "CS", graduation),
                workAuthorization);
    }

    private PostingRequirements posting(List<String> required, List<String> preferred) {
        return new PostingRequirements("SWE Intern", "Acme", required, preferred,
                List.of("Build REST APIs in Java"), null, null, null, "Columbus, OH", "Summer 2027");
    }

    private FitScoreCalculator.ExperienceRating perfect() {
        return FitScoreCalculator.ExperienceRating.of(1.0, "Directly comparable work.");
    }

    @Test
    @DisplayName("everything matched scores 100 and lands in the top band")
    void perfectMatch() {
        var score = calculator.score(
                resume(List.of("Java", "SQL"), List.of("Spring Boot"), "May 2027", "US Citizen"),
                posting(List.of("Java", "SQL"), List.of("Spring Boot")),
                perfect(), "Java SQL Spring Boot");

        assertThat(score.overall()).isEqualTo(100);
        assertThat(score.band()).isEqualTo(FitBand.STRONG);
        assertThat(score.requiredCoverage()).isEqualTo(1.0);
        assertThat(score.hardMismatch()).isFalse();
    }

    @Test
    @DisplayName("the published weights are what the arithmetic actually uses")
    void weightsAreAsDocumented() {
        // Required half covered, preferred none, experience 0, eligibility untested:
        // 0.5*0.5 + 0.2*0 + 0.15*0 + 0.15*1.0 = 0.40 -> 40
        var score = calculator.score(
                resume(List.of("Java"), List.of(), null, null),
                posting(List.of("Java", "Kotlin"), List.of("Terraform")),
                FitScoreCalculator.ExperienceRating.of(0, "Nothing comparable."),
                "Java");

        assertThat(score.requiredCoverage()).isEqualTo(0.5);
        assertThat(score.preferredCoverage()).isZero();
        assertThat(score.overall()).isEqualTo(40);
    }

    @Test
    @DisplayName("spelling differences do not cost coverage")
    void aliasesCount() {
        var score = calculator.score(
                resume(List.of("JS", "Postgres"), List.of("React.js"), "May 2027", null),
                posting(List.of("JavaScript", "PostgreSQL"), List.of("React")),
                perfect(), "JS Postgres React.js");

        assertThat(score.requiredCoverage()).isEqualTo(1.0);
        assertThat(score.preferredCoverage()).isEqualTo(1.0);
        assertThat(score.missingRequired()).isEmpty();
    }

    @Test
    @DisplayName("a superset skill covers what it implies")
    void impliedSkillsCount() {
        var score = calculator.score(
                resume(List.of(), List.of("Spring Boot"), "May 2027", null),
                posting(List.of("Java", "Spring"), List.of()),
                perfect(), "Spring Boot");

        assertThat(score.matchedRequired()).containsExactlyInAnyOrder("Java", "Spring");
    }

    @Test
    @DisplayName("a graduation date outside the window is a hard mismatch and caps the score at 40")
    void graduationMismatchCaps() {
        var posting = new PostingRequirements("SWE Intern", "Acme",
                List.of("Java", "SQL"), List.of("Spring Boot"), List.of(),
                null, "Graduating May 2026", null, null, null);

        var score = calculator.score(
                resume(List.of("Java", "SQL"), List.of("Spring Boot"), "May 2027", "US Citizen"),
                posting, perfect(), "Java SQL Spring Boot");

        assertThat(score.hardMismatch()).isTrue();
        assertThat(score.overall()).isEqualTo(FitScoreCalculator.HARD_MISMATCH_CAP);
        assertThat(score.eligibility()).anySatisfy(check -> {
            assertThat(check.name()).isEqualTo("Graduation window");
            assertThat(check.status()).isEqualTo(FitScore.EligibilityCheck.Status.FAIL);
            assertThat(check.hard()).isTrue();
        });
    }

    @Test
    @DisplayName("a citizenship requirement against a sponsorship-needing resume is a hard mismatch")
    void workAuthorizationMismatchCaps() {
        var posting = new PostingRequirements("SWE Intern", "Defense Co",
                List.of("Java"), List.of(), List.of(), null, null,
                "Must be a U.S. Citizen; we do not provide sponsorship", null, null);

        var score = calculator.score(
                resume(List.of("Java"), List.of(), "May 2027", "F-1 student, requires sponsorship"),
                posting, perfect(), "Java");

        assertThat(score.hardMismatch()).isTrue();
        assertThat(score.overall()).isLessThanOrEqualTo(FitScoreCalculator.HARD_MISMATCH_CAP);
    }

    @Test
    @DisplayName("an unstated status is scored as unknown, not as a failure")
    void unknownEligibilityIsNotAFailure() {
        var posting = new PostingRequirements("SWE Intern", "Acme",
                List.of("Java"), List.of(), List.of(), null, null,
                "Must be authorised to work in the US without sponsorship", null, null);

        var score = calculator.score(
                resume(List.of("Java"), List.of(), "May 2027", null),
                posting, perfect(), "Java");

        assertThat(score.hardMismatch()).isFalse();
        assertThat(score.eligibility()).anySatisfy(check ->
                assertThat(check.status()).isEqualTo(FitScore.EligibilityCheck.Status.UNKNOWN));
        assertThat(score.eligibilityScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a posting with no readable skills is capped and says why")
    void unreadablePostingIsCapped() {
        var vague = new PostingRequirements("SWE Intern", "Acme", List.of(), List.of(),
                List.of("Work on interesting problems"), null, null, null, null, null);

        var score = calculator.score(
                resume(List.of("Java"), List.of("Spring Boot"), "May 2027", "US Citizen"),
                vague, perfect(), "Java Spring Boot");

        assertThat(score.overall()).isLessThanOrEqualTo(FitScoreCalculator.UNREADABLE_POSTING_CAP);
        assertThat(score.weightingNote()).contains("No skills could be read");
    }

    @Test
    @DisplayName("a posting with no preferred list redistributes that weight instead of scoring zero")
    void missingPreferredIsRedistributed() {
        var withPreferred = calculator.score(
                resume(List.of("Java"), List.of(), "May 2027", "US Citizen"),
                posting(List.of("Java"), List.of("Terraform")), perfect(), "Java");
        var withoutPreferred = calculator.score(
                resume(List.of("Java"), List.of(), "May 2027", "US Citizen"),
                posting(List.of("Java"), List.of()), perfect(), "Java");

        assertThat(withoutPreferred.overall()).isGreaterThan(withPreferred.overall());
        assertThat(withoutPreferred.weightingNote()).contains("no preferred skills");
    }

    @Test
    @DisplayName("band boundaries are exactly the ones the product promises")
    void bandBoundaries() {
        assertThat(FitBand.of(100)).isEqualTo(FitBand.STRONG);
        assertThat(FitBand.of(75)).isEqualTo(FitBand.STRONG);
        assertThat(FitBand.of(74)).isEqualTo(FitBand.GOOD);
        assertThat(FitBand.of(55)).isEqualTo(FitBand.GOOD);
        assertThat(FitBand.of(54)).isEqualTo(FitBand.STRETCH);
        assertThat(FitBand.of(35)).isEqualTo(FitBand.STRETCH);
        assertThat(FitBand.of(34)).isEqualTo(FitBand.LONG_SHOT);
        assertThat(FitBand.of(0)).isEqualTo(FitBand.LONG_SHOT);
    }

    @Test
    @DisplayName("a missing required skill produces a learning suggestion, never 'add it to your resume'")
    void missingSkillsSuggestLearning() {
        var score = calculator.score(
                resume(List.of("Java"), List.of(), "May 2027", "US Citizen"),
                posting(List.of("Java", "Kubernetes"), List.of()), perfect(), "Java");

        assertThat(score.missingRequired()).contains("Kubernetes");
        assertThat(score.suggestions()).anySatisfy(suggestion -> {
            assertThat(suggestion.kind()).isEqualTo(FitScore.Suggestion.Kind.LEARN);
            assertThat(suggestion.text()).contains("Kubernetes");
        });
        assertThat(score.suggestions())
                .noneMatch(suggestion -> suggestion.text().toLowerCase().contains("add kubernetes"));
    }

    @Test
    @DisplayName("a skill listed but never demonstrated is surfaced, not invented")
    void listedButUndemonstratedSkill() {
        var profile = new ResumeProfile(List.of("Java", "Kotlin"), List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ResumeProfile.ExperienceEntry("Intern", "Acme", "2026",
                        List.of("Wrote Java services"))),
                List.of(), new ResumeProfile.Education("OSU", "BS", "CS", "May 2027"), "US Citizen");

        var score = calculator.score(profile, posting(List.of("Kotlin"), List.of()),
                perfect(), "Java Kotlin");

        assertThat(score.suggestions()).anySatisfy(suggestion -> {
            assertThat(suggestion.kind()).isEqualTo(FitScore.Suggestion.Kind.SURFACE);
            assertThat(suggestion.text()).contains("Kotlin").contains("no bullet point");
        });
    }

    @Test
    @DisplayName("ATS keyword coverage is reported but never folded into the score")
    void keywordCoverageIsInformationOnly() {
        var withKeywords = calculator.score(
                resume(List.of("Java"), List.of(), "May 2027", "US Citizen"),
                posting(List.of("Java"), List.of()), perfect(),
                "Java REST APIs microservices deployment pipeline");
        var withoutKeywords = calculator.score(
                resume(List.of("Java"), List.of(), "May 2027", "US Citizen"),
                posting(List.of("Java"), List.of()), perfect(), "Java");

        assertThat(withKeywords.atsKeywordCoverage())
                .isGreaterThan(withoutKeywords.atsKeywordCoverage());
        assertThat(withKeywords.overall()).isEqualTo(withoutKeywords.overall());
    }

    @Test
    @DisplayName("the same inputs always produce the same score")
    void deterministic() {
        var profile = resume(List.of("Java", "SQL"), List.of("Spring Boot"), "May 2027", "US Citizen");
        var job = posting(List.of("Java", "SQL", "Docker"), List.of("Spring Boot"));
        var rating = FitScoreCalculator.ExperienceRating.of(0.62, "Comparable work.");

        var first = calculator.score(profile, job, rating, "Java SQL Spring Boot");
        var second = calculator.score(profile, job, rating, "Java SQL Spring Boot");

        assertThat(first.overall()).isEqualTo(second.overall());
        assertThat(first.missingRequired()).isEqualTo(second.missingRequired());
    }

    @Test
    @DisplayName("an experience rating outside 0..1 is clamped rather than distorting the total")
    void experienceRatingIsClamped() {
        assertThat(FitScoreCalculator.ExperienceRating.of(1.7, "x").score()).isEqualTo(1.0);
        assertThat(FitScoreCalculator.ExperienceRating.of(-3, "x").score()).isZero();
    }
}
