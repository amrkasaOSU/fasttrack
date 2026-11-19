package com.fasttrack.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.dto.ApplicationRequests.UpsertApplication;
import com.fasttrack.application.ApplicationService;
import com.fasttrack.support.AbstractIntegrationTest;
import com.fasttrack.support.UserFixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The duplicate-detection rules. Milestone 5 adds the labeled evaluation and
 * re-derives the similarity threshold from it; these tests pin the behaviour
 * the rules are supposed to have.
 */
class ApplicationMatcherIntegrationTest extends AbstractIntegrationTest {

    @Autowired ApplicationMatcher matcher;
    @Autowired ApplicationService applicationService;
    @Autowired ApplicationRepository applications;
    @Autowired UserFixtures fixtures;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = fixtures.newUser("matcher").getId();
    }

    private UUID seed(String company, String position, String requisitionId) {
        return applicationService.create(userId,
                new UpsertApplication(company, position, requisitionId, null, null, null,
                        Instant.now().minusSeconds(86_400), null),
                ApplicationSource.MANUAL, EventActor.USER).id();
    }

    @Test
    @DisplayName("rule 1: the same requisition id at the same company is the same application")
    void requisitionIdWins() {
        UUID existing = seed("Stripe", "Software Engineer Intern", "JR12345");

        MatchOutcome outcome = matcher.match(userId, "Stripe, Inc.",
                "Completely Different Title", "JR12345", "Greenhouse", Instant.now());

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.application().getId()).isEqualTo(existing);
        assertThat(outcome.rule()).isEqualTo("requisition_id");
    }

    @Test
    @DisplayName("rule 2: the same role written differently is the same application")
    void sameRoleDifferentWording() {
        UUID existing = seed("Acme Corporation", "Software Engineer Intern - Summer 2027", null);

        MatchOutcome outcome = matcher.match(userId, "ACME, Inc.",
                "Software Engineering Intern (Summer 2027)", null, null, Instant.now());

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.application().getId()).isEqualTo(existing);
        assertThat(outcome.needsReview()).isFalse();
    }

    @Test
    @DisplayName("rule 3: a highly similar title matches without asking")
    void similarTitleMatches() {
        UUID existing = seed("Datadog", "Backend Engineer Intern", null);

        MatchOutcome outcome = matcher.match(userId, "Datadog",
                "Backend Engineer Intern - Summer 2027", null, null, Instant.now());

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.application().getId()).isEqualTo(existing);
    }

    @Test
    @DisplayName("rule 4: a genuinely different role at the same company is a new application")
    void differentRoleAtSameCompany() {
        seed("Stripe", "Software Engineer Intern", null);

        MatchOutcome outcome = matcher.match(userId, "Stripe",
                "Technical Program Manager", null, null, Instant.now());

        assertThat(outcome.created()).isTrue();
        assertThat(applications.countByUserId(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("the same role at a different company is a new application")
    void sameRoleDifferentCompany() {
        seed("Stripe", "Software Engineer Intern", null);

        MatchOutcome outcome = matcher.match(userId, "Datadog",
                "Software Engineer Intern", null, null, Instant.now());

        assertThat(outcome.created()).isTrue();
    }

    @Test
    @DisplayName("rule 5: a borderline title attaches to the closest match and asks for review")
    void borderlineAsksForReview() {
        // "data engineer intern" vs "data analyst intern" overlaps on 2 of 4
        // tokens - close enough to be the same posting renamed, far enough
        // that guessing would be wrong half the time.
        seed("Snowflake", "Data Engineer Intern", null);

        MatchOutcome outcome = matcher.match(userId, "Snowflake",
                "Data Analyst Intern", null, null, Instant.now());

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.needsReview()).isTrue();
        assertThat(outcome.reason()).contains("check this is the same application");
    }

    @Test
    @DisplayName("an email with no company at all is not matched to anything")
    void noCompanyMeansNoMatch() {
        assertThat(matcher.match(userId, null, "Software Engineer Intern", null, null, Instant.now()))
                .isNull();
        assertThat(matcher.match(userId, "   ", "Software Engineer Intern", null, null, Instant.now()))
                .isNull();
    }

    @Test
    @DisplayName("an email with a company but no role creates an application flagged for review")
    void missingRoleIsFlagged() {
        MatchOutcome outcome = matcher.match(userId, "Cloudflare", null, null, null, Instant.now());

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.needsReview()).isTrue();
        assertThat(outcome.application().getPositionTitle()).isEqualTo("Unknown position");
        assertThat(applications.findById(outcome.application().getId()).orElseThrow().isNeedsReview())
                .isTrue();
    }

    @Test
    @DisplayName("matching never reaches into another user's applications")
    void matchingIsPerUser() {
        seed("Stripe", "Software Engineer Intern", "JR999");
        UUID otherUser = fixtures.newUser("matcher-other").getId();

        MatchOutcome outcome = matcher.match(otherUser, "Stripe",
                "Software Engineer Intern", "JR999", null, Instant.now());

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.application().getUserId()).isEqualTo(otherUser);
    }

    @Test
    @DisplayName("similarity is token overlap, and it behaves the way the thresholds assume")
    void similarityScores() {
        assertThat(matcher.similarity("software engineer intern", "software engineer intern")).isEqualTo(1.0);
        assertThat(matcher.similarity("backend engineer intern", "backend engineer"))
                .isGreaterThanOrEqualTo(ApplicationMatcher.SIMILARITY_THRESHOLD);
        assertThat(matcher.similarity("software engineer intern", "data engineer intern"))
                .isLessThan(ApplicationMatcher.SIMILARITY_THRESHOLD);
        assertThat(matcher.similarity("", "software engineer intern")).isZero();
        assertThat(matcher.similarity(null, null)).isZero();
    }
}
