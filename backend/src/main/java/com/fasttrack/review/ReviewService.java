package com.fasttrack.review;

import com.fasttrack.application.ApplicationEvent;
import com.fasttrack.application.ApplicationEventRepository;
import com.fasttrack.application.ApplicationEventType;
import com.fasttrack.application.ApplicationRepository;
import com.fasttrack.application.ApplicationStateMachine;
import com.fasttrack.application.EventActor;
import com.fasttrack.application.JobApplication;
import com.fasttrack.application.StatusTransition;
import com.fasttrack.classify.EmailCategory;
import com.fasttrack.common.NotFoundException;
import com.fasttrack.email.EmailClassification;
import com.fasttrack.email.EmailClassificationRepository;
import com.fasttrack.email.EmailCorrection;
import com.fasttrack.email.EmailCorrectionRepository;
import com.fasttrack.email.EmailMessage;
import com.fasttrack.email.EmailMessageRepository;
import com.fasttrack.matching.ApplicationMatcher;
import com.fasttrack.matching.MatchOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Needs review queue, and what happens when the user corrects something.
 *
 * <p>Two kinds of item land here:
 * <ul>
 *   <li>an email the classifier was not confident about, and</li>
 *   <li>an application the matcher attached an email to without being sure.</li>
 * </ul>
 *
 * <p>A correction does three things: it is recorded (so accuracy can be
 * measured from real judgements rather than synthetic ones), the stored
 * classification is overwritten, and the application is re-derived - because a
 * corrected category usually implies a different status.
 */
@Service
public class ReviewService {

    private final EmailMessageRepository emails;
    private final EmailClassificationRepository classifications;
    private final EmailCorrectionRepository corrections;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final ApplicationMatcher matcher;
    private final ApplicationStateMachine stateMachine;

    public ReviewService(EmailMessageRepository emails,
                         EmailClassificationRepository classifications,
                         EmailCorrectionRepository corrections,
                         ApplicationRepository applications,
                         ApplicationEventRepository events,
                         ApplicationMatcher matcher,
                         ApplicationStateMachine stateMachine) {
        this.emails = emails;
        this.classifications = classifications;
        this.corrections = corrections;
        this.applications = applications;
        this.events = events;
        this.matcher = matcher;
        this.stateMachine = stateMachine;
    }

    public record ReviewEmail(
            UUID emailId,
            String subject,
            String senderName,
            String senderEmail,
            String snippet,
            Instant receivedAt,
            EmailCategory category,
            double confidence,
            String reason,
            String method,
            String company,
            String positionTitle,
            UUID applicationId,
            String applicationLabel) {}

    public record ReviewApplication(
            UUID applicationId,
            String companyName,
            String positionTitle,
            String status,
            String reason,
            Instant updatedAt) {}

    public record ReviewQueue(
            List<ReviewEmail> uncertainEmails,
            List<ReviewApplication> uncertainApplications,
            int total) {}

    @Transactional(readOnly = true)
    public ReviewQueue queue(UUID userId) {
        List<ReviewEmail> uncertainEmails = new ArrayList<>();

        for (EmailClassification classification : classifications.findAllByUserIdAndNeedsReviewIsTrue(userId)) {
            emails.findByIdAndUserId(classification.getEmailId(), userId).ifPresent(email -> {
                JobApplication application = email.getApplicationId() == null ? null
                        : applications.findByIdAndUserId(email.getApplicationId(), userId).orElse(null);

                uncertainEmails.add(new ReviewEmail(
                        email.getId(),
                        email.getSubject(),
                        email.getSenderName(),
                        email.getSenderEmail(),
                        email.getSnippet(),
                        email.getReceivedAt(),
                        classification.getCategory(),
                        classification.getConfidence(),
                        classification.getReason(),
                        classification.getMethod().name(),
                        classification.getCompany(),
                        classification.getPositionTitle(),
                        application == null ? null : application.getId(),
                        application == null ? null
                                : application.getCompanyName() + " - " + application.getPositionTitle()));
            });
        }

        List<ReviewApplication> uncertainApplications = applications
                .findAllByUserIdAndNeedsReviewIsTrueOrderByUpdatedAtDesc(userId).stream()
                .map(a -> new ReviewApplication(a.getId(), a.getCompanyName(), a.getPositionTitle(),
                        a.getStatus().name(), a.getNeedsReviewReason(), a.getUpdatedAt()))
                .toList();

        return new ReviewQueue(uncertainEmails, uncertainApplications,
                uncertainEmails.size() + uncertainApplications.size());
    }

    /**
     * Applies a user's correction to one email: records it, rewrites the
     * classification, and re-derives the application it belongs to.
     */
    @Transactional
    public ReviewEmail correct(UUID userId, UUID emailId, EmailCategory category,
                               String company, String positionTitle) {

        EmailMessage email = emails.findByIdAndUserId(emailId, userId)
                .orElseThrow(() -> new NotFoundException("Email"));
        EmailClassification classification = classifications.findByEmailId(emailId)
                .orElseThrow(() -> new NotFoundException("Classification"));

        corrections.save(new EmailCorrection(userId, emailId, classification,
                category, blankToNull(company), blankToNull(positionTitle)));

        classification.correctTo(category, company, positionTitle);

        // A corrected category usually means a different status, and a
        // corrected company usually means a different application.
        reapply(userId, email, classification);

        return queueEntryFor(userId, email, classification);
    }

    private void reapply(UUID userId, EmailMessage email, EmailClassification classification) {
        if (!classification.getCategory().isActionable()) {
            detach(email);
            return;
        }

        MatchOutcome outcome = matcher.match(userId,
                classification.getCompany(),
                classification.getPositionTitle(),
                classification.getRequisitionId(),
                classification.getPlatform(),
                email.getReceivedAt());

        if (outcome == null) {
            detach(email);
            return;
        }

        JobApplication application = outcome.application();
        email.setApplicationId(application.getId());
        emails.save(email);

        events.save(ApplicationEvent.of(application, ApplicationEventType.EMAIL_LINKED, EventActor.USER,
                "Re-linked after you corrected the classification", email.getId(), Instant.now()));

        StatusTransition transition = stateMachine.apply(application.getStatus(), classification.getCategory());
        if (transition.applied()) {
            application.setStatus(transition.to());
            application.setLastEventAt(Instant.now());
            if (transition.to().isResponse()) {
                application.markResponded(email.getReceivedAt());
            }
            events.save(ApplicationEvent.statusChanged(application, transition.from(), transition.to(),
                    EventActor.USER, email.getId(), "You corrected this email to "
                            + classification.getCategory().name().toLowerCase().replace('_', ' '),
                    Instant.now()));
        }
        application.clearReview();
        applications.save(application);
    }

    private void detach(EmailMessage email) {
        if (email.getApplicationId() != null) {
            email.setApplicationId(null);
            emails.save(email);
        }
    }

    private ReviewEmail queueEntryFor(UUID userId, EmailMessage email, EmailClassification classification) {
        JobApplication application = email.getApplicationId() == null ? null
                : applications.findByIdAndUserId(email.getApplicationId(), userId).orElse(null);

        return new ReviewEmail(email.getId(), email.getSubject(), email.getSenderName(),
                email.getSenderEmail(), email.getSnippet(), email.getReceivedAt(),
                classification.getCategory(), classification.getConfidence(),
                classification.getReason(), classification.getMethod().name(),
                classification.getCompany(), classification.getPositionTitle(),
                application == null ? null : application.getId(),
                application == null ? null
                        : application.getCompanyName() + " - " + application.getPositionTitle());
    }

    /**
     * Accuracy measured from real corrections rather than synthetic email.
     *
     * <p>Stated carefully, because the denominator is not "emails a human
     * checked" - it is every classification. A user who never opens the review
     * queue produces no corrections, which would flatter the number. The
     * fields are named so the UI can say exactly that.
     */
    public record LiveAccuracy(
            long classifications,
            long corrections,
            long categoryCorrections,
            long llmClassifications,
            long llmCategoryCorrections,
            Double uncorrectedShare,
            String caveat) {}

    @Transactional(readOnly = true)
    public LiveAccuracy liveAccuracy(UUID userId) {
        long total = classifications.countByUserId(userId);
        long allCorrections = corrections.countByUserId(userId);
        long categoryCorrections = corrections.countCategoryCorrections(userId);
        long llmTotal = classifications.countLlmClassifications(userId);
        long llmCorrections = corrections.countLlmCategoryCorrections(userId);

        Double uncorrected = total == 0 ? null : 1.0 - ((double) categoryCorrections / total);

        return new LiveAccuracy(total, allCorrections, categoryCorrections, llmTotal, llmCorrections,
                uncorrected,
                "This is the share of classifications you have not corrected, not a verified "
                + "accuracy: emails you never reviewed count as uncorrected.");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
