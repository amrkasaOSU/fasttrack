package com.fasttrack.email;

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
 * Metadata for one processed email. Bodies are never stored: they are fetched,
 * used in memory to classify, and discarded (see docs/privacy.md).
 */
@Entity
@Table(name = "email_messages")
public class EmailMessage {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    @Column(name = "gmail_thread_id")
    private String gmailThreadId;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    @Column(name = "sender_name")
    private String senderName;

    @Column(name = "sender_domain", nullable = false)
    private String senderDomain;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "snippet", nullable = false)
    private String snippet = "";

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private EmailSource source = EmailSource.GMAIL;

    @Enumerated(EnumType.STRING)
    @Column(name = "prefilter_result", nullable = false)
    private PrefilterResult prefilterResult = PrefilterResult.PENDING;

    @Column(name = "prefilter_reason")
    private String prefilterReason;

    /** SHA-256 of sender + subject + body, the cache key for classification. */
    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EmailMessage() {
        // for JPA
    }

    public EmailMessage(UUID userId, String gmailMessageId, String senderEmail, String subject,
                        Instant receivedAt, String contentHash) {
        this.userId = userId;
        this.gmailMessageId = gmailMessageId;
        this.senderEmail = senderEmail;
        this.senderDomain = domainOf(senderEmail);
        this.subject = subject;
        this.receivedAt = receivedAt;
        this.contentHash = contentHash;
    }

    public static String domainOf(String emailAddress) {
        if (emailAddress == null) {
            return "";
        }
        int at = emailAddress.lastIndexOf('@');
        return at < 0 ? "" : emailAddress.substring(at + 1).trim().toLowerCase();
    }

    public void prefiltered(PrefilterResult result, String reason) {
        this.prefilterResult = result;
        this.prefilterReason = reason;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getGmailMessageId() { return gmailMessageId; }
    public String getGmailThreadId() { return gmailThreadId; }
    public void setGmailThreadId(String gmailThreadId) { this.gmailThreadId = gmailThreadId; }
    public String getSenderEmail() { return senderEmail; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getSenderDomain() { return senderDomain; }
    public String getSubject() { return subject; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet == null ? "" : snippet; }
    public Instant getReceivedAt() { return receivedAt; }
    public EmailSource getSource() { return source; }
    public void setSource(EmailSource source) { this.source = source; }
    public PrefilterResult getPrefilterResult() { return prefilterResult; }
    public String getPrefilterReason() { return prefilterReason; }
    public String getContentHash() { return contentHash; }
    public UUID getApplicationId() { return applicationId; }
    public void setApplicationId(UUID applicationId) { this.applicationId = applicationId; }
    public Instant getCreatedAt() { return createdAt; }
}
