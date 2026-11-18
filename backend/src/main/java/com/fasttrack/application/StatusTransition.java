package com.fasttrack.application;

/**
 * The outcome of asking the state machine what an email means.
 *
 * @param applied whether the status actually changes
 * @param from    the status before
 * @param to      the status after (equal to {@code from} when not applied)
 * @param reason  a short, user-facing explanation, shown in the timeline
 */
public record StatusTransition(boolean applied,
                               ApplicationStatus from,
                               ApplicationStatus to,
                               String reason) {

    public static StatusTransition unchanged(ApplicationStatus current, String reason) {
        return new StatusTransition(false, current, current, reason);
    }

    public static StatusTransition moved(ApplicationStatus from, ApplicationStatus to, String reason) {
        return new StatusTransition(true, from, to, reason);
    }
}
