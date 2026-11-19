package com.fasttrack.gmail;

import com.fasttrack.application.ApplicationEvent;
import com.fasttrack.application.ApplicationEventRepository;
import com.fasttrack.application.ApplicationEventType;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationStateMachine;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.JobApplication;
import com.fasttrack.application.StatusTransition;
import com.fasttrack.classify.ClassificationMethod;
import com.fasttrack.classify.ClassificationResult;
import com.fasttrack.classify.EmailClassificationService;
import com.fasttrack.classify.EmailContent;
import com.fasttrack.classify.EmailPrefilter;
import com.fasttrack.classify.PrefilterDecision;
import com.fasttrack.email.EmailClassification;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailMessage;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.email.EmailSource;
import com.fasttrack.email.PrefilterResult;
import com.fasttrack.matching.ApplicationMatcher;
import com.fasttrack.matching.MatchOutcome;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one fetched Gmail message into stored metadata, a classification, and
 * - when it means something - an application update.
 *
 * <p>Each message is its own transaction ({@code REQUIRES_NEW}): a sync can
 * touch hundreds of messages, and a failure on message 300 must not roll back
 * the first 299.
 */
@Service
public class SyncMessageProcessor {

    private final EmailMessageRepository emails;
    private final EmailClassificationRepository classifications;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final EmailPrefilter prefilter;
    private final EmailClassificationService classifier;
    private final ApplicationMatcher matcher;
    private final ApplicationStateMachine stateMachine;

    public SyncMessageProcessor(EmailMessageRepository emails,
                                EmailClassificationRepository classifications,
                                ApplicationRepository applications,
                                ApplicationEventRepository events,
                                EmailPrefilter prefilter,
                                EmailClassificationService classifier,
                                ApplicationMatcher matcher,
                                ApplicationStateMachine stateMachine) {
        this.emails = emails;
        this.classifications = classifications;
        this.applications = applications;
        this.events = events;
        this.prefilter = prefilter;
        this.classifier = classifier;
        this.matcher = matcher;
        this.stateMachine = stateMachine;
    }

    /** What happened to one message, so the sync can keep its counters. */
    public record Outcome(boolean alreadySeen,
                          boolean dropped,
                          boolean classified,
                          boolean cacheHit,
                          boolean llmCall,
                          boolean applicationCreated,
                          boolean applicationUpdated) {

        static Outcome skipped() {
            return new Outcome(true, false, false, false, false, false, false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome process(UUID userId, GmailMessage message) {
        if (emails.existsByUserIdAndGmailMessageId(userId, message.id())) {
            return Outcome.skipped();
        }

        EmailContent content = new EmailContent(
                message.fromAddress(), message.fromName(), message.subject(),
                message.body(), message.receivedAt());

        EmailMessage stored = new EmailMessage(
                userId, message.id(), message.fromAddress(), message.subject(),
                message.receivedAt(), content.contentHash());
        stored.setGmailThreadId(message.threadId());
        stored.setSenderName(message.fromName());
        stored.setSnippet(trimSnippet(message.snippet()));
        stored.setSource(EmailSource.GMAIL);

        PrefilterDecision decision = prefilter.evaluate(
                message.fromAddress(), message.subject(), message.body());

        if (!decision.keep()) {
            stored.prefiltered(PrefilterResult.DROPPED, decision.reason());
            emails.save(stored);
            return new Outcome(false, true, false, false, false, false, false);
        }

        stored.prefiltered(PrefilterResult.KEPT, decision.reason());
        EmailMessage savedEmail = emails.save(stored);

        ClassificationResult result = classifier.classify(content, userId);
        classifications.save(EmailClassification.from(savedEmail.getId(), userId, result));

        boolean created = false;
        boolean updated = false;
        if (result.category().isActionable()) {
            MatchOutcome match = applyToApplication(userId, savedEmail, result);
            if (match != null) {
                created = match.created();
                updated = !match.created();
            }
        }

        return new Outcome(false, false, true, result.cacheHit(),
                !result.cacheHit() && result.method() == ClassificationMethod.LLM,
                created, updated);
    }

    /** Links the email to an application and lets the state machine decide. */
    private MatchOutcome applyToApplication(UUID userId, EmailMessage email, ClassificationResult result) {
        MatchOutcome outcome = matcher.match(
                userId,
                result.company(),
                result.positionTitle(),
                result.requisitionId(),
                result.platform(),
                email.getReceivedAt());

        if (outcome == null) {
            // No company could be determined. The email is kept and surfaced
            // in the review queue rather than attached to a guess.
            return null;
        }

        JobApplication application = outcome.application();
        email.setApplicationId(application.getId());
        emails.save(email);

        if (outcome.created()) {
            events.save(ApplicationEvent.created(application, EventActor.EMAIL, email.getReceivedAt()));
        }

        events.save(ApplicationEvent.of(application, ApplicationEventType.EMAIL_LINKED, EventActor.EMAIL,
                shortSubject(email.getSubject()), email.getId(), email.getReceivedAt()));

        if (outcome.needsReview()) {
            application.flagForReview(outcome.reason());
        }
        if (result.needsReview()) {
            application.flagForReview("The classifier was unsure about \"" + shortSubject(email.getSubject())
                    + "\" (" + Math.round(result.confidence() * 100) + "% confidence)");
        }

        StatusTransition transition = stateMachine.apply(application.getStatus(), result.category());
        if (transition.applied()) {
            application.setStatus(transition.to());
            application.setLastEventAt(email.getReceivedAt());
            if (transition.to().isResponse()) {
                application.markResponded(email.getReceivedAt());
            }
            events.save(ApplicationEvent.statusChanged(application, transition.from(), transition.to(),
                    EventActor.EMAIL, email.getId(), transition.reason(), email.getReceivedAt()));
        } else {
            application.setLastEventAt(later(application.getLastEventAt(), email.getReceivedAt()));
        }
        applications.save(application);
        return outcome;
    }

    private String trimSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        return snippet.length() <= 200 ? snippet : snippet.substring(0, 200);
    }

    private String shortSubject(String subject) {
        if (subject == null) {
            return "(no subject)";
        }
        return subject.length() <= 120 ? subject : subject.substring(0, 117) + "...";
    }

    private Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
