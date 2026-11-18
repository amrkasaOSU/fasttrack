package com.fasttrack.application;

import com.fasttrack.classify.EmailCategory;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Decides what an incoming, classified email does to an application's status.
 *
 * <p>Three rules govern everything here:
 * <ol>
 *   <li><b>Forward only.</b> An email that implies an earlier stage than the
 *       one already recorded does not move the application backwards. Mail
 *       arrives out of order; a confirmation that lands after an interview
 *       invite is late, not a regression.</li>
 *   <li><b>Silence is never a rejection.</b> {@link ApplicationStatus#REJECTED}
 *       comes only from an explicit rejection email or from the user. Nothing
 *       in this class infers it from elapsed time.</li>
 *   <li><b>Terminal states are sticky.</b> Once rejected, cancelled, withdrawn
 *       or decided, only the user can change the status. A later automated
 *       email cannot silently reopen or overwrite it.</li>
 * </ol>
 */
@Component
public class ApplicationStateMachine {

    private static final Map<EmailCategory, ApplicationStatus> TARGETS = new EnumMap<>(EmailCategory.class);

    static {
        TARGETS.put(EmailCategory.APPLICATION_CONFIRMATION, ApplicationStatus.APPLICATION_RECEIVED);
        TARGETS.put(EmailCategory.ASSESSMENT_INVITE, ApplicationStatus.ASSESSMENT_REQUESTED);
        TARGETS.put(EmailCategory.RECRUITER_OUTREACH, ApplicationStatus.RECRUITER_CONTACT);
        TARGETS.put(EmailCategory.INTERVIEW_REQUEST, ApplicationStatus.INTERVIEW_REQUESTED);
        TARGETS.put(EmailCategory.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEW_SCHEDULED);
        TARGETS.put(EmailCategory.OFFER, ApplicationStatus.OFFER_RECEIVED);
        TARGETS.put(EmailCategory.REJECTION, ApplicationStatus.REJECTED);
        TARGETS.put(EmailCategory.POSITION_CANCELLED, ApplicationStatus.POSITION_CANCELLED);
    }

    /** What an email of this category does to an application in this status. */
    public StatusTransition apply(ApplicationStatus current, EmailCategory category) {
        if (current == null || category == null) {
            throw new IllegalArgumentException("current status and category are required");
        }

        ApplicationStatus target = TARGETS.get(category);
        if (target == null) {
            return StatusTransition.unchanged(current,
                    "Email is " + humanise(category) + ", which does not change the status");
        }

        if (current.isTerminal()) {
            return StatusTransition.unchanged(current,
                    "Application is already " + humanise(current) + "; only you can change it from here");
        }

        if (target.isTerminal()) {
            return StatusTransition.moved(current, target, humanise(category) + " received");
        }

        if (target.rank() <= current.rank()) {
            return StatusTransition.unchanged(current,
                    "Email implies " + humanise(target) + ", which is not ahead of " + humanise(current));
        }

        return StatusTransition.moved(current, target, humanise(category) + " received");
    }

    /**
     * Whether a user is allowed to set this status by hand. Users may set any
     * status, including moving backwards - they know things the mailbox does
     * not - but not to a value the enum does not contain.
     */
    public boolean isUserSettable(ApplicationStatus target) {
        return target != null;
    }

    /** The status a brand-new application starts in. */
    public ApplicationStatus initialStatus() {
        return ApplicationStatus.APPLIED;
    }

    private String humanise(Enum<?> value) {
        String[] words = value.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word);
        }
        return sb.toString();
    }
}
