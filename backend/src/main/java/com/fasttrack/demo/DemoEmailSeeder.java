package com.fasttrack.demo;

import com.fasttrack.application.ApplicationEvent;
import com.fasttrack.application.ApplicationEventRepository;
import com.fasttrack.application.ApplicationEventType;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.JobApplication;
import com.fasttrack.classify.ClassificationMethod;
import com.fasttrack.classify.ClassificationResult;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.classify.EmailContent;
import com.fasttrack.classify.EmailPrefilter;
import com.fasttrack.classify.PrefilterDecision;
import com.fasttrack.email.EmailClassification;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailMessage;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.EmailSource;
import com.fasttrack.email.PrefilterResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives the demo account the mail its applications came from.
 *
 * <p>The emails are generated from the applications' own histories, so the
 * demo is internally consistent: a rejection event has a rejection email
 * behind it, dated the same day, and the dashboard's counts add up. Noise is
 * included deliberately - job alerts and newsletters that the prefilter drops
 * - because the "emails filtered out before AI" number on the settings page
 * is meaningless without any.
 *
 * <p>A few classifications are given low confidence so the Needs review queue
 * has something in it: an empty queue demonstrates nothing.
 */
@Service
public class DemoEmailSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoEmailSeeder.class);
    private static final long SEED = 20260217L;

    /** Noise messages added on top of the application mail. */
    private static final int NOISE_EMAILS = 40;

    private final EmailMessageRepository emails;
    private final EmailClassificationRepository classifications;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final EmailPrefilter prefilter;

    public DemoEmailSeeder(EmailMessageRepository emails,
                           EmailClassificationRepository classifications,
                           ApplicationRepository applications,
                           ApplicationEventRepository events,
                           EmailPrefilter prefilter) {
        this.emails = emails;
        this.classifications = classifications;
        this.applications = applications;
        this.events = events;
        this.prefilter = prefilter;
    }

    @Transactional
    public int seedEmails(UUID demoUserId) {
        if (emails.countByUserId(demoUserId) > 0) {
            return 0;
        }

        Random random = new Random(SEED);
        int created = 0;

        for (JobApplication application : applications.findAllByUserIdOrderByCreatedAtDesc(demoUserId)) {
            for (ApplicationEvent event : events
                    .findAllByApplicationIdAndUserIdOrderByOccurredAtAscCreatedAtAsc(
                            application.getId(), demoUserId)) {

                if (event.getEventType() != ApplicationEventType.STATUS_CHANGED
                        || event.getToStatus() == null) {
                    continue;
                }
                EmailCategory category = categoryFor(event.getToStatus());
                if (category == null) {
                    continue;
                }
                created += storeApplicationEmail(demoUserId, application, event, category, random) ? 1 : 0;
            }
        }

        created += storeNoise(demoUserId, random);

        log.info("Seeded demo account with {} emails", created);
        return created;
    }

    private boolean storeApplicationEmail(UUID userId, JobApplication application,
                                          ApplicationEvent event, EmailCategory category,
                                          Random random) {
        String sender = senderFor(application, category);
        String subject = subjectFor(application, category);
        String body = bodyFor(application, category);
        Instant receivedAt = event.getOccurredAt();

        EmailContent content = new EmailContent(sender, application.getCompanyName() + " Recruiting",
                subject, body, receivedAt);

        EmailMessage email = new EmailMessage(userId, "demo-" + UUID.randomUUID(),
                sender, subject, receivedAt, content.contentHash());
        email.setSenderName(application.getCompanyName() + " Recruiting");
        email.setSnippet(body.length() > 160 ? body.substring(0, 160) : body);
        email.setSource(EmailSource.DEMO);
        email.setApplicationId(application.getId());

        PrefilterDecision decision = prefilter.evaluate(sender, subject, body);
        email.prefiltered(decision.keep() ? PrefilterResult.KEPT : PrefilterResult.DROPPED,
                decision.reason());
        EmailMessage saved = emails.save(email);

        if (!decision.keep()) {
            return true;
        }

        // One in twelve is deliberately uncertain, so the review queue is not empty.
        boolean uncertain = random.nextInt(12) == 0;
        double confidence = uncertain ? 0.45 + random.nextDouble() * 0.2 : 0.86 + random.nextDouble() * 0.13;

        classifications.save(EmailClassification.from(saved.getId(), userId,
                new ClassificationResult(category,
                        application.getCompanyName(),
                        application.getPositionTitle(),
                        application.getRequisitionId(),
                        category == EmailCategory.INTERVIEW_SCHEDULED
                                ? event.getOccurredAt().plus(Duration.ofDays(5 + random.nextInt(9)))
                                : null,
                        category == EmailCategory.ASSESSMENT_INVITE
                                ? event.getOccurredAt().plus(Duration.ofDays(3 + random.nextInt(5)))
                                : null,
                        category == EmailCategory.ASSESSMENT_INVITE
                                ? DemoCatalog.ASSESSMENT_PLATFORMS.get(
                                        random.nextInt(DemoCatalog.ASSESSMENT_PLATFORMS.size()))
                                : null,
                        Math.round(confidence * 100) / 100.0,
                        reasonFor(category),
                        ClassificationMethod.LLM,
                        "demo-model",
                        "v1", false, 420 + random.nextInt(900))));

        if (uncertain) {
            application.flagForReview("The classifier was only "
                    + Math.round(confidence * 100) + "% sure about \"" + subject + "\"");
            applications.save(application);
        }
        return true;
    }

    /** Job alerts, newsletters and networking noise - the mail that gets dropped. */
    private int storeNoise(UUID userId, Random random) {
        List<String[]> noise = List.of(
                new String[]{"jobalerts-noreply@linkedin.com", "LinkedIn Job Alerts",
                        "20 new jobs for \"software engineer intern\"",
                        "Your job alert for software engineer intern. See all new jobs matching your search."},
                new String[]{"jobs-noreply@joinhandshake.com", "Handshake",
                        "Jobs for you: Data Engineer Intern and more",
                        "Based on your profile, here are jobs we think you'll like. View all recommended jobs."},
                new String[]{"news@acm.org", "ACM Student Chapter",
                        "This week in tech: our newsletter",
                        "This week's newsletter covers the upcoming hackathon and a tech talk on distributed systems."},
                new String[]{"events@careers.osu.edu", "University Career Services",
                        "Register now: Spring Career Fair",
                        "Join us for the Spring Career Fair featuring 60 employers. Register now to reserve your spot."},
                new String[]{"notifications@linkedin.com", "LinkedIn",
                        "Someone viewed your profile",
                        "Your profile was viewed by a recruiter. See who viewed your profile with Premium."});

        int created = 0;
        for (int i = 0; i < NOISE_EMAILS; i++) {
            String[] template = noise.get(i % noise.size());
            Instant receivedAt = Instant.now().minus(Duration.ofDays(1 + random.nextInt(110)));

            EmailContent content = new EmailContent(template[0], template[1], template[2], template[3], receivedAt);
            EmailMessage email = new EmailMessage(userId, "demo-noise-" + UUID.randomUUID(),
                    template[0], template[2], receivedAt, content.contentHash());
            email.setSenderName(template[1]);
            email.setSnippet(template[3]);
            email.setSource(EmailSource.DEMO);

            PrefilterDecision decision = prefilter.evaluate(template[0], template[2], template[3]);
            email.prefiltered(decision.keep() ? PrefilterResult.KEPT : PrefilterResult.DROPPED,
                    decision.reason());
            emails.save(email);
            created++;
        }
        return created;
    }

    private EmailCategory categoryFor(com.fasttrack.application.ApplicationStatus status) {
        return switch (status) {
            case APPLICATION_RECEIVED -> EmailCategory.APPLICATION_CONFIRMATION;
            case ASSESSMENT_REQUESTED -> EmailCategory.ASSESSMENT_INVITE;
            case RECRUITER_CONTACT -> EmailCategory.RECRUITER_OUTREACH;
            case INTERVIEW_REQUESTED -> EmailCategory.INTERVIEW_REQUEST;
            case INTERVIEW_SCHEDULED, FINAL_ROUND -> EmailCategory.INTERVIEW_SCHEDULED;
            case OFFER_RECEIVED -> EmailCategory.OFFER;
            case REJECTED -> EmailCategory.REJECTION;
            case POSITION_CANCELLED -> EmailCategory.POSITION_CANCELLED;
            // Assessment completed and interview completed are things the
            // candidate did, not emails the company sent.
            default -> null;
        };
    }

    private String senderFor(JobApplication application, EmailCategory category) {
        String domain = switch (application.getAtsPlatform() == null ? "" : application.getAtsPlatform()) {
            case "Greenhouse" -> "greenhouse-mail.io";
            case "Workday" -> "myworkday.com";
            case "Lever" -> "lever.co";
            case "Ashby" -> "ashbyhq.com";
            case "iCIMS" -> "icims.com";
            case "SmartRecruiters" -> "smartrecruiters.com";
            case "Jobvite" -> "jobvite.com";
            case "SuccessFactors" -> "successfactors.com";
            default -> "careers.example.com";
        };
        return (category == EmailCategory.RECRUITER_OUTREACH ? "recruiting@" : "no-reply@") + domain;
    }

    private String subjectFor(JobApplication application, EmailCategory category) {
        String company = application.getCompanyName();
        String role = application.getPositionTitle();
        return switch (category) {
            case APPLICATION_CONFIRMATION -> "Thank you for applying to " + company;
            case ASSESSMENT_INVITE -> company + " invites you to complete an online assessment";
            case RECRUITER_OUTREACH -> "A quick note about your application to " + company;
            case INTERVIEW_REQUEST -> "Interview invitation - " + role + " at " + company;
            case INTERVIEW_SCHEDULED -> "Confirmed: your interview with " + company;
            case OFFER -> "Your offer from " + company;
            case REJECTION -> "Your application to " + company;
            case POSITION_CANCELLED -> "Update: " + role;
            default -> company + " - update";
        };
    }

    private String bodyFor(JobApplication application, EmailCategory category) {
        String company = application.getCompanyName();
        String role = application.getPositionTitle();
        return switch (category) {
            case APPLICATION_CONFIRMATION -> "Hi Demo,\n\nThank you for applying to the " + role
                    + " position at " + company + ". We have received your application and our "
                    + "recruiting team is reviewing it.";
            case ASSESSMENT_INVITE -> "Hi Demo,\n\n" + company + " has invited you to complete an "
                    + "online assessment for the " + role + " position. The assessment takes about "
                    + "70 minutes and must be completed before the deadline.";
            case RECRUITER_OUTREACH -> "Hi Demo,\n\nI'm the recruiter for " + role + " at " + company
                    + ". I reviewed your application and would like to tell you more about the team.";
            case INTERVIEW_REQUEST -> "Hi Demo,\n\nWe were impressed by your application for " + role
                    + " and would like to invite you to a 45-minute technical interview. Please "
                    + "choose a time that works for you.";
            case INTERVIEW_SCHEDULED -> "Hi Demo,\n\nYour interview for " + role + " is confirmed. "
                    + "A calendar invitation with the video link has been sent separately.";
            case OFFER -> "Hi Demo,\n\nWe are pleased to offer you the " + role + " position at "
                    + company + ". Your offer letter is attached.";
            case REJECTION -> "Hi Demo,\n\nThank you for your interest in the " + role
                    + " position at " + company + ". After careful review, we have decided to move "
                    + "forward with other candidates.";
            case POSITION_CANCELLED -> "Hi Demo,\n\nThe " + role + " requisition has been closed due "
                    + "to a change in our hiring plan. This is not a reflection of your candidacy.";
            default -> "Hi Demo,\n\nThere is an update on your application to " + company + ".";
        };
    }

    private String reasonFor(EmailCategory category) {
        return switch (category) {
            case APPLICATION_CONFIRMATION -> "The email confirms the application was received.";
            case ASSESSMENT_INVITE -> "The email invites the candidate to an online assessment.";
            case RECRUITER_OUTREACH -> "A recruiter is reaching out about the application.";
            case INTERVIEW_REQUEST -> "The candidate is invited to interview.";
            case INTERVIEW_SCHEDULED -> "The interview is confirmed with a time.";
            case OFFER -> "The email extends an offer of employment.";
            case REJECTION -> "The email states the candidate is not moving forward.";
            case POSITION_CANCELLED -> "The role was closed without a decision on the candidate.";
            default -> "Classified from the email's wording.";
        };
    }
}
