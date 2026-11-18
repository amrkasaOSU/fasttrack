package com.fasttrack.application;

/** Who caused a history entry. */
public enum EventActor {
    /** The application itself (scheduled jobs, derived changes). */
    SYSTEM,
    /** The signed-in user, through the UI. */
    USER,
    /** An incoming email, classified and applied by the state machine. */
    EMAIL,
    /** A CSV import or the demo seeder. */
    IMPORT
}
