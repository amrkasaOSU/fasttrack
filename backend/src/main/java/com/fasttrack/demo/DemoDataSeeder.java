package com.fasttrack.demo;

import com.fasttrack.account.User;
import com.fasttrack.application.ApplicationEvent;
import com.fasttrack.application.ApplicationEventRepository;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationSource;
import com.fasttrack.application.ApplicationStatus;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.JobApplication;
import com.fasttrack.matching.TextNormalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the demo account's data.
 *
 * <p>Everything is generated from a fixed seed, so the demo is identical on
 * every machine and every boot - which also makes it usable as a fixture for
 * the dashboard and insight tests.
 *
 * <p>The outcome mix is modelled on a real internship cycle rather than a
 * flattering one: most applications never get a reply, rejections outnumber
 * interviews by a wide margin, and there is exactly one offer. A demo that
 * showed a 60% interview rate would be useless as a product demonstration.
 */
@Service
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Fixed so the demo is byte-identical across machines. */
    private static final long SEED = 20260216L;
    static final int TARGET_APPLICATIONS = 60;

    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final TextNormalizer normalizer;

    public DemoDataSeeder(ApplicationRepository applications,
                          ApplicationEventRepository events,
                          TextNormalizer normalizer) {
        this.applications = applications;
        this.events = events;
        this.normalizer = normalizer;
    }

    /** The funnel an application walked through, used to build its timeline. */
    private record Outcome(ApplicationStatus finalStatus, List<ApplicationStatus> path) {}

    @Transactional
    public int seedApplications(User demoUser) {
        if (applications.countByUserId(demoUser.getId()) > 0) {
            return 0;
        }

        Random random = new Random(SEED);
        Instant now = Instant.now();
        List<JobApplication> created = new ArrayList<>();

        for (int i = 0; i < TARGET_APPLICATIONS; i++) {
            DemoCatalog.Employer employer = DemoCatalog.EMPLOYERS.get(i % DemoCatalog.EMPLOYERS.size());
            String position = DemoCatalog.POSITIONS.get(random.nextInt(DemoCatalog.POSITIONS.size()));
            String location = DemoCatalog.LOCATIONS.get(random.nextInt(DemoCatalog.LOCATIONS.size()));

            // Applications spread over the last 115 days, newest first.
            Instant appliedAt = now.minus(Duration.ofDays(3L + random.nextInt(112)))
                    .truncatedTo(ChronoUnit.MINUTES);

            JobApplication application = new JobApplication(
                    demoUser.getId(),
                    employer.name(),
                    normalizer.normalizeCompany(employer.name()),
                    position,
                    normalizer.normalizePosition(position),
                    ApplicationSource.DEMO);
            application.setLocation(location);
            application.setAtsPlatform(employer.atsPlatform());
            application.setAppliedAt(appliedAt);
            application.setStatus(ApplicationStatus.APPLIED);
            application.setLastEventAt(appliedAt);
            if (random.nextInt(10) == 0) {
                application.setRequisitionId("JR" + (100000 + random.nextInt(899999)));
            }
            created.add(applications.save(application));
        }

        int eventCount = 0;
        for (JobApplication application : created) {
            eventCount += buildTimeline(application, pickOutcome(random), random, now);
        }

        log.info("Seeded demo account with {} applications and {} timeline events",
                created.size(), eventCount);
        return created.size();
    }

    /**
     * Outcome mix (out of 100): 42 never answered, 28 rejected after the
     * confirmation, 12 assessment, 10 interview, 4 final round, 2 offer,
     * 2 position cancelled.
     */
    private Outcome pickOutcome(Random random) {
        int roll = random.nextInt(100);
        if (roll < 42) {
            return new Outcome(ApplicationStatus.APPLIED, List.of());
        }
        if (roll < 70) {
            return new Outcome(ApplicationStatus.REJECTED,
                    List.of(ApplicationStatus.APPLICATION_RECEIVED, ApplicationStatus.REJECTED));
        }
        if (roll < 82) {
            boolean rejectedAfter = random.nextBoolean();
            List<ApplicationStatus> path = new ArrayList<>(List.of(
                    ApplicationStatus.APPLICATION_RECEIVED,
                    ApplicationStatus.ASSESSMENT_REQUESTED,
                    ApplicationStatus.ASSESSMENT_COMPLETED));
            if (rejectedAfter) {
                path.add(ApplicationStatus.REJECTED);
            }
            return new Outcome(path.get(path.size() - 1), path);
        }
        if (roll < 92) {
            boolean rejectedAfter = random.nextInt(3) > 0;
            List<ApplicationStatus> path = new ArrayList<>(List.of(
                    ApplicationStatus.APPLICATION_RECEIVED,
                    ApplicationStatus.ASSESSMENT_REQUESTED,
                    ApplicationStatus.ASSESSMENT_COMPLETED,
                    ApplicationStatus.INTERVIEW_REQUESTED,
                    ApplicationStatus.INTERVIEW_SCHEDULED));
            if (rejectedAfter) {
                path.add(ApplicationStatus.INTERVIEW_COMPLETED);
                path.add(ApplicationStatus.REJECTED);
            }
            return new Outcome(path.get(path.size() - 1), path);
        }
        if (roll < 96) {
            return new Outcome(ApplicationStatus.FINAL_ROUND, List.of(
                    ApplicationStatus.APPLICATION_RECEIVED,
                    ApplicationStatus.RECRUITER_CONTACT,
                    ApplicationStatus.INTERVIEW_REQUESTED,
                    ApplicationStatus.INTERVIEW_SCHEDULED,
                    ApplicationStatus.INTERVIEW_COMPLETED,
                    ApplicationStatus.FINAL_ROUND));
        }
        if (roll < 98) {
            return new Outcome(ApplicationStatus.OFFER_RECEIVED, List.of(
                    ApplicationStatus.APPLICATION_RECEIVED,
                    ApplicationStatus.ASSESSMENT_REQUESTED,
                    ApplicationStatus.ASSESSMENT_COMPLETED,
                    ApplicationStatus.INTERVIEW_REQUESTED,
                    ApplicationStatus.INTERVIEW_SCHEDULED,
                    ApplicationStatus.INTERVIEW_COMPLETED,
                    ApplicationStatus.FINAL_ROUND,
                    ApplicationStatus.OFFER_RECEIVED));
        }
        return new Outcome(ApplicationStatus.POSITION_CANCELLED, List.of(
                ApplicationStatus.APPLICATION_RECEIVED,
                ApplicationStatus.POSITION_CANCELLED));
    }

    /**
     * Walks the outcome path forward in time from the applied date, writing one
     * history entry per step and stopping if the path would run past today.
     */
    private int buildTimeline(JobApplication application, Outcome outcome, Random random, Instant now) {
        events.save(ApplicationEvent.created(application, EventActor.IMPORT, application.getAppliedAt()));
        int written = 1;

        Instant cursor = application.getAppliedAt();
        ApplicationStatus current = ApplicationStatus.APPLIED;

        for (ApplicationStatus next : outcome.path()) {
            cursor = cursor.plus(Duration.ofDays(stepDays(current, next, random)))
                    .plus(Duration.ofHours(random.nextInt(9)));
            if (cursor.isAfter(now)) {
                break;
            }
            events.save(ApplicationEvent.statusChanged(application, current, next, EventActor.EMAIL,
                    null, describe(next, application), cursor));
            written++;
            current = next;
            application.setStatus(next);
            application.setLastEventAt(cursor);
            if (next.isResponse()) {
                application.markResponded(cursor);
            }
        }

        // A couple of live assessments and interviews sit in the near future so
        // the dashboard's "action items" panel has something real to show.
        if (current == ApplicationStatus.ASSESSMENT_REQUESTED && random.nextBoolean()) {
            application.setLastEventAt(now.minus(Duration.ofDays(1)));
        }
        return written;
    }

    private int stepDays(ApplicationStatus from, ApplicationStatus to, Random random) {
        if (to == ApplicationStatus.APPLICATION_RECEIVED) {
            return random.nextInt(2); // same day or next day
        }
        if (to == ApplicationStatus.REJECTED) {
            return 3 + random.nextInt(25);
        }
        if (to == ApplicationStatus.ASSESSMENT_COMPLETED) {
            return 1 + random.nextInt(5);
        }
        return 2 + random.nextInt(12);
    }

    private String describe(ApplicationStatus status, JobApplication application) {
        return switch (status) {
            case APPLICATION_RECEIVED -> "Application confirmation from " + application.getCompanyName();
            case UNDER_REVIEW -> "Application moved to review";
            case ASSESSMENT_REQUESTED -> "Online assessment invitation";
            case ASSESSMENT_COMPLETED -> "Assessment submitted";
            case RECRUITER_CONTACT -> "Recruiter reached out";
            case INTERVIEW_REQUESTED -> "Interview request received";
            case INTERVIEW_SCHEDULED -> "Interview scheduled";
            case INTERVIEW_COMPLETED -> "Interview completed";
            case FINAL_ROUND -> "Invited to the final round";
            case OFFER_RECEIVED -> "Offer received";
            case REJECTED -> "Rejection email received";
            case POSITION_CANCELLED -> "The role was closed";
            default -> "Status updated";
        };
    }
}
