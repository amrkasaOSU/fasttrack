package com.fasttrack.resume;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded resume, versioned per user.
 *
 * <p>The extracted text is kept because every fit check needs it for the ATS
 * keyword comparison, and re-parsing a PDF on each check would be wasteful.
 * The original PDF is not stored.
 */
@Entity
@Table(name = "resumes")
public class Resume {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "content_text", nullable = false)
    private String contentText;

    /** The structured profile, as JSON, after any corrections the user made. */
    @Column(name = "parsed_json")
    private String parsedJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_method", nullable = false)
    private ResumeParseMethod parseMethod = ResumeParseMethod.PENDING;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Resume() {
        // for JPA
    }

    public Resume(UUID userId, int version, String filename, int sizeBytes, String contentText) {
        this.userId = userId;
        this.version = version;
        this.filename = filename;
        this.sizeBytes = sizeBytes;
        this.contentText = contentText;
    }

    public void storeParsed(String parsedJson, ResumeParseMethod method) {
        this.parsedJson = parsedJson;
        this.parseMethod = method;
    }

    public void markCorrected(String parsedJson) {
        this.parsedJson = parsedJson;
        this.parseMethod = ResumeParseMethod.MANUAL;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public int getVersion() { return version; }
    public String getFilename() { return filename; }
    public int getSizeBytes() { return sizeBytes; }
    public String getContentText() { return contentText; }
    public String getParsedJson() { return parsedJson; }
    public ResumeParseMethod getParseMethod() { return parseMethod; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
