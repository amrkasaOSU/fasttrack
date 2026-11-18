package com.fasttrack.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.account.User;
import com.fasttrack.application.ApplicationEvent;
import com.fasttrack.application.ApplicationEventRepository;
import com.fasttrack.application.ApplicationEventType;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.JobApplication;
import com.fasttrack.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The demo account has to look like a real job search, not a happy path, and
 * it has to be identical on every machine.
 */
class DemoDataSeederIntegrationTest extends AbstractIntegrationTest {

    @Autowired DemoAccountService demoAccounts;
    @Autowired DemoDataSeeder seeder;
    @Autowired ApplicationRepository applications;
    @Autowired ApplicationEventRepository events;

    private List<JobApplication> seed() {
        User demo = demoAccounts.ensureDemoUser();
        seeder.seedApplications(demo);
        return applications.findAllByUserIdOrderByCreatedAtDesc(demo.getId());
    }

    @Test
    @DisplayName("the demo account gets 60 applications across many employers")
    void seedsSixtyApplications() {
        List<JobApplication> seeded = seed();

        assertThat(seeded).hasSize(DemoDataSeeder.TARGET_APPLICATIONS);
        assertThat(seeded.stream().map(JobApplication::getCompanyName).distinct().count())
                .isGreaterThanOrEqualTo(40);
        assertThat(seeded).allSatisfy(a -> {
            assertThat(a.getCompanyNormalized()).isNotBlank();
            assertThat(a.getPositionNormalized()).isNotBlank();
            assertThat(a.getAppliedAt()).isNotNull().isBefore(Instant.now());
            assertThat(a.getAtsPlatform()).isNotBlank();
        });
    }

    @Test
    @DisplayName("seeding twice does not double the data")
    void seedingIsIdempotent() {
        seed();
        User demo = demoAccounts.ensureDemoUser();

        int second = seeder.seedApplications(demo);

        assertThat(second).isZero();
        assertThat(applications.countByUserId(demo.getId()))
                .isEqualTo(DemoDataSeeder.TARGET_APPLICATIONS);
    }

    @Test
    @DisplayName("the outcome mix looks like a real search: mostly silence, few interviews, one offer")
    void outcomesAreRealistic() {
        List<JobApplication> seeded = seed();
        Map<ApplicationStatus, Long> byStatus = seeded.stream()
                .collect(Collectors.groupingBy(JobApplication::getStatus, Collectors.counting()));

        long noReply = byStatus.getOrDefault(ApplicationStatus.APPLIED, 0L);
        long rejected = byStatus.getOrDefault(ApplicationStatus.REJECTED, 0L);
        long interviews = seeded.stream()
                .filter(a -> ApplicationStatus.INTERVIEW_STAGES.contains(a.getStatus()))
                .count();
        long offers = seeded.stream()
                .filter(a -> ApplicationStatus.OFFER_STAGES.contains(a.getStatus()))
                .count();

        assertThat(noReply).as("most applications never get a reply").isGreaterThan(seeded.size() / 4);
        assertThat(rejected).as("rejections outnumber interviews").isGreaterThan(interviews);
        assertThat(offers).as("an offer or two, not a dozen").isBetween(0L, 3L);
        assertThat(noReply + rejected).isLessThan(seeded.size());
    }

    @Test
    @DisplayName("every application has a timeline that starts with CREATED and only moves forward")
    void timelinesAreConsistent() {
        List<JobApplication> seeded = seed();

        for (JobApplication application : seeded) {
            List<ApplicationEvent> history = events
                    .findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(
                            application.getId(), application.getUserId());

            assertThat(history).isNotEmpty();
            assertThat(history.get(0).getEventType()).isEqualTo(ApplicationEventType.CREATED);
            assertThat(history.get(history.size() - 1).getToStatus()).isEqualTo(application.getStatus());

            Instant previous = Instant.MIN;
            for (ApplicationEvent event : history) {
                assertThat(event.getOccurredAt()).isAfterOrEqualTo(previous);
                assertThat(event.getOccurredAt()).isBeforeOrEqualTo(Instant.now());
                previous = event.getOccurredAt();
            }
        }
    }

    @Test
    @DisplayName("an application that was answered carries a first-response date, and one that was not does not")
    void firstResponseIsOnlySetWhenThereWasAResponse() {
        List<JobApplication> seeded = seed();

        assertThat(seeded).allSatisfy(application -> {
            if (application.getStatus() == ApplicationStatus.APPLIED) {
                assertThat(application.getFirstResponseAt())
                        .as("%s has no reply yet", application.getCompanyName())
                        .isNull();
            } else {
                assertThat(application.getFirstResponseAt())
                        .as("%s was answered", application.getCompanyName())
                        .isNotNull()
                        .isAfterOrEqualTo(application.getAppliedAt());
            }
        });
    }

    @Test
    @DisplayName("nothing is marked rejected without a rejection step in its history")
    void rejectionsAlwaysHaveAnEvent() {
        List<JobApplication> seeded = seed();

        seeded.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.REJECTED)
                .forEach(a -> assertThat(events
                        .findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(a.getId(), a.getUserId()))
                        .anySatisfy(event ->
                                assertThat(event.getToStatus()).isEqualTo(ApplicationStatus.REJECTED)));
    }
}
