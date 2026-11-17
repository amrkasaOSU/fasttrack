package com.fasttrack.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A FastTrack account. Either a Google-authenticated user or the demo user. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "picture_url")
    private String pictureUrl;

    /** Google's stable {@code sub} claim. Null for the demo user. */
    @Column(name = "google_subject")
    private String googleSubject;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    /** Demo sessions can read everything but cannot mutate the shared demo data. */
    @Column(name = "is_read_only", nullable = false)
    private boolean readOnly;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
        // for JPA
    }

    public static User googleUser(String email, String displayName, String pictureUrl, String googleSubject) {
        User u = new User();
        u.email = email;
        u.displayName = displayName;
        u.pictureUrl = pictureUrl;
        u.googleSubject = googleSubject;
        return u;
    }

    public static User demoUser(String email, String displayName) {
        User u = new User();
        u.email = email;
        u.displayName = displayName;
        u.demo = true;
        u.readOnly = false;
        return u;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPictureUrl() { return pictureUrl; }
    public void setPictureUrl(String pictureUrl) { this.pictureUrl = pictureUrl; }
    public String getGoogleSubject() { return googleSubject; }
    public void setGoogleSubject(String googleSubject) { this.googleSubject = googleSubject; }
    public boolean isDemo() { return demo; }
    public void setDemo(boolean demo) { this.demo = demo; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
