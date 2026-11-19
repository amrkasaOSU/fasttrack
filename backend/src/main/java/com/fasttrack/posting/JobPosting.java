package com.fasttrack.posting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A job posting the user wants scored against their resume. */
@Entity
@Table(name = "job_postings")
public class JobPosting {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "title")
    private String title;

    @Column(name = "company")
    private String company;

    @Column(name = "source_url")
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private PostingSource sourceType = PostingSource.PASTED;

    @Column(name = "raw_text", nullable = false)
    private String rawText;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "parsed_json")
    private String parsedJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_method", nullable = false)
    private PostingParseMethod parseMethod = PostingParseMethod.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected JobPosting() {
        // for JPA
    }

    public JobPosting(UUID userId, String rawText, String contentHash, PostingSource sourceType) {
        this.userId = userId;
        this.rawText = rawText;
        this.contentHash = contentHash;
        this.sourceType = sourceType;
    }

    public void storeParsed(String parsedJson, PostingParseMethod method, String title, String company) {
        this.parsedJson = parsedJson;
        this.parseMethod = method;
        if (title != null && !title.isBlank()) {
            this.title = title.trim();
        }
        if (company != null && !company.isBlank()) {
            this.company = company.trim();
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public PostingSource getSourceType() { return sourceType; }
    public String getRawText() { return rawText; }
    public String getContentHash() { return contentHash; }
    public String getParsedJson() { return parsedJson; }
    public PostingParseMethod getParseMethod() { return parseMethod; }
    public Instant getCreatedAt() { return createdAt; }
}
