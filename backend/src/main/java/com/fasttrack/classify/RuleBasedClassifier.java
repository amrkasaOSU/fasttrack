package com.fasttrack.classify;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The deterministic classifier.
 *
 * <p>It has two jobs: it is the fallback whenever the LLM is unavailable,
 * unconfigured or returns something invalid, and it is the baseline the LLM is
 * measured against in the evaluation (RESULTS.md). Keeping a real baseline
 * honest matters - "the LLM is better" is only a claim if there is something
 * to be better than.
 *
 * <p>Phrases are checked in a fixed order chosen around the failure that
 * costs most: calling a rejection a confirmation. A decision sentence
 * ("we regret to inform", "move forward with other candidates") therefore
 * beats a polite opening ("thank you for your interest"), while the weaker
 * signal "unfortunately" is only consulted after the positive stages have had
 * their turn, so an offer that happens to contain the word is not misread.
 */
@Component
public class RuleBasedClassifier {

    private static final List<String> REJECTION_STRONG = List.of(
            "regret to inform", "we regret", "not be moving forward", "not moving forward",
            "will not be progressing", "decided to move forward with other",
            "move forward with other candidates", "pursue other candidates",
            "other applicants whose", "not been selected", "were not selected",
            "no longer under consideration", "unable to offer you",
            "will not be proceeding", "position has been filled",
            "decided not to proceed", "unsuccessful on this occasion",
            "we have decided to move ahead with");

    private static final List<String> OFFER_STRONG = List.of(
            "pleased to offer", "happy to offer", "excited to offer", "offer of employment",
            "your offer letter", "formal offer", "extend an offer", "offer details below");

    private static final List<String> CANCELLED = List.of(
            "position has been cancelled", "position has been canceled", "role has been cancelled",
            "req has been closed", "requisition has been closed", "on hold indefinitely",
            "no longer filling this", "role has been put on hold", "hiring for this role has been paused");

    private static final List<String> INTERVIEW_SCHEDULED = List.of(
            "your interview is scheduled", "interview is confirmed", "is confirmed for",
            "confirming your interview", "calendar invitation for your interview",
            "we have scheduled your", "interview confirmation", "your upcoming interview",
            "this message confirms your");

    private static final List<String> INTERVIEW_REQUEST = List.of(
            "invite you to interview", "invitation to interview", "interview invitation",
            "schedule an interview", "schedule time to chat", "your availability",
            "availability for a call", "phone screen", "speak with you about your application",
            "like to set up a", "next steps in the interview", "book a time");

    private static final List<String> ASSESSMENT = List.of(
            "online assessment", "coding challenge", "technical assessment", "take-home",
            "take home assignment", "codesignal", "hackerrank", "codility", "hirevue",
            "karat interview", "complete the assessment", "assessment invitation",
            "coding test", "skills assessment", "complete this assessment");

    /**
     * Account and survey wording only. "candidate portal" deliberately is not
     * here: confirmations, interview invitations and offers all point at the
     * portal, and keying on it made this category swallow them.
     */
    private static final List<String> SURVEY_OR_ACCOUNT = List.of(
            "voluntary self-identification", "self-identification", "self identification",
            "voluntary disclosure", "eeo", "demographic questions",
            "create your account", "activate your account", "reset your password",
            "password reset", "complete your candidate profile", "candidate profile",
            "background check authorization", "candidate experience survey",
            "this survey", "a survey", "how was your application experience",
            "two minutes of your time", "tell us how");

    private static final List<String> CONFIRMATION = List.of(
            "we received your application", "received your application", "application has been received",
            "thank you for applying", "thanks for applying", "your application was sent",
            "application submitted", "successfully submitted", "application for the position",
            "we have your application", "we've got your application", "got your application",
            "application confirmation", "reviewing your application", "begins reviewing applications",
            "application is under review", "thank you for your interest in");

    private static final List<String> RECRUITER_OUTREACH = List.of(
            "came across your profile", "found your profile", "i am a recruiter",
            "i'm a recruiter", "technical recruiter", "recruiter at",
            "would you be interested in", "reaching out about an opportunity",
            "thought you might be a great fit", "exploring new opportunities",
            "open to hearing more", "caught my eye", "we're hiring interns",
            "internship openings", "no application needed");

    private static final List<String> JOB_ALERT = List.of(
            "job alert", "jobs for you", "new jobs", "recommended jobs", "jobs matching",
            "saved job", "newsletter", "webinar", "career fair");

    private static final List<String> ASSESSMENT_PLATFORMS = List.of(
            "CodeSignal", "HackerRank", "Codility", "HireVue", "Karat", "CoderPad");

    /** "Your application to Acme", "Thank you for applying to Acme Corp". */
    private static final Pattern COMPANY_IN_SUBJECT = Pattern.compile(
            "(?:application (?:to|at|with)|applying to|interest in|position at|role at|opportunity (?:at|with))\\s+([A-Z][\\w&.,'-]*(?:\\s+[A-Z][\\w&.,'-]*){0,3})",
            Pattern.CASE_INSENSITIVE);

    /** "Software Engineer Intern - Acme", "Acme | Data Engineer Intern". */
    private static final Pattern POSITION_IN_SUBJECT = Pattern.compile(
            "(?:application for|applying for|your application for|position of|role of|regarding)\\s+(?:the\\s+)?([A-Za-z][\\w/()&.,'+-]*(?:\\s+[A-Za-z][\\w/()&.,'+-]*){0,6})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REQUISITION = Pattern.compile(
            "\\b(?:req(?:uisition)?(?:\\s*(?:id|#|no\\.?))?|job\\s*id|posting\\s*id)\\s*[:#-]?\\s*([A-Z]{0,4}[-_]?\\d{3,}[A-Z0-9-]*)",
            Pattern.CASE_INSENSITIVE);

    public ClassificationResult classify(EmailContent email) {
        long started = System.nanoTime();
        String subject = lower(email.subject());
        String body = lower(email.body());
        String all = subject + " \n " + body;

        Decision decision = decide(subject, all);

        String company = extractCompany(email);
        String position = extractPosition(email);
        String requisition = extractRequisition(email);
        String platform = extractPlatform(all);

        long latency = (System.nanoTime() - started) / 1_000_000;
        return new ClassificationResult(
                decision.category(),
                company,
                position,
                requisition,
                null,  // the rule path does not attempt date extraction
                null,
                decision.category() == EmailCategory.ASSESSMENT_INVITE ? platform : null,
                decision.confidence(),
                decision.reason(),
                ClassificationMethod.RULE,
                null,
                "rules-v1",
                false,
                latency);
    }

    private record Decision(EmailCategory category, double confidence, String reason) {}

    private Decision decide(String subject, String all) {
        String match;

        if ((match = firstMatch(subject, JOB_ALERT)) != null) {
            return new Decision(EmailCategory.NOT_JOB_RELATED, 0.80,
                    "Subject looks like a job alert or newsletter (\"" + match + "\")");
        }
        if ((match = firstMatch(all, OFFER_STRONG)) != null) {
            return new Decision(EmailCategory.OFFER, 0.85, "Contains \"" + match + "\"");
        }
        if ((match = firstMatch(all, REJECTION_STRONG)) != null) {
            return new Decision(EmailCategory.REJECTION, 0.85, "Contains \"" + match + "\"");
        }
        if ((match = firstMatch(all, CANCELLED)) != null) {
            return new Decision(EmailCategory.POSITION_CANCELLED, 0.80, "Contains \"" + match + "\"");
        }
        if ((match = firstMatch(all, INTERVIEW_SCHEDULED)) != null) {
            return new Decision(EmailCategory.INTERVIEW_SCHEDULED, 0.80, "Contains \"" + match + "\"");
        }
        if ((match = firstMatch(all, ASSESSMENT)) != null) {
            return new Decision(EmailCategory.ASSESSMENT_INVITE, 0.80, "Contains \"" + match + "\"");
        }
        if ((match = firstMatch(all, INTERVIEW_REQUEST)) != null) {
            return new Decision(EmailCategory.INTERVIEW_REQUEST, 0.75, "Contains \"" + match + "\"");
        }
        if ((match = firstMatch(all, CONFIRMATION)) != null) {
            return new Decision(EmailCategory.APPLICATION_CONFIRMATION, 0.80, "Contains \"" + match + "\"");
        }
        if ((match = firstMatch(all, RECRUITER_OUTREACH)) != null) {
            return new Decision(EmailCategory.RECRUITER_OUTREACH, 0.75, "Contains \"" + match + "\"");
        }
        // Account and survey wording is checked last among the job categories:
        // portal and profile language also appears in mail that announces a
        // real decision, and that decision must win.
        if ((match = firstMatch(all, SURVEY_OR_ACCOUNT)) != null) {
            return new Decision(EmailCategory.SURVEY_OR_ACCOUNT, 0.70, "Contains \"" + match + "\"");
        }
        // The weakest rejection signal is consulted last, so it cannot
        // outrank an offer or an interview invitation that happens to use it.
        if (all.contains("unfortunately")) {
            return new Decision(EmailCategory.REJECTION, 0.60,
                    "Contains \"unfortunately\" with no other clear signal");
        }
        return new Decision(EmailCategory.NOT_JOB_RELATED, 0.50,
                "No rule matched this email");
    }

    String extractCompany(EmailContent email) {
        Matcher matcher = COMPANY_IN_SUBJECT.matcher(nullSafe(email.subject()));
        if (matcher.find()) {
            String candidate = cleanup(matcher.group(1));
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        // A display name like "Stripe Recruiting" or "Careers at Datadog".
        String senderName = nullSafe(email.senderName());
        if (!senderName.isBlank()) {
            String cleaned = senderName
                    .replaceAll("(?i)\\b(careers|recruiting|recruitment|talent|hiring|no-?reply|jobs|team|hr)\\b", "")
                    .replaceAll("(?i)^at\\s+", "")
                    .replaceAll("[|<>()\\[\\]]", " ")
                    .replaceAll("\\s{2,}", " ")
                    .trim();
            if (cleaned.length() > 1) {
                return cleaned;
            }
        }
        // Fall back to the sending domain, unless it is a shared ATS domain.
        String domain = com.fasttrack.email.EmailMessage.domainOf(email.senderEmail());
        if (!domain.isBlank() && !isSharedDomain(domain)) {
            String root = domain.split("\\.")[0];
            if (!root.isBlank() && !root.equals("mail") && !root.equals("no-reply")) {
                return capitalise(root);
            }
        }
        return null;
    }

    String extractPosition(EmailContent email) {
        String subject = nullSafe(email.subject());
        Matcher matcher = POSITION_IN_SUBJECT.matcher(subject);
        if (matcher.find()) {
            String candidate = cleanup(matcher.group(1));
            if (looksLikePosition(candidate)) {
                return candidate;
            }
        }
        // "Acme - Software Engineer Intern" / "Software Engineer Intern | Acme"
        for (String part : subject.split("\\s[|\\-–]\\s")) {
            String candidate = cleanup(part);
            if (looksLikePosition(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    String extractRequisition(EmailContent email) {
        Matcher matcher = REQUISITION.matcher(nullSafe(email.subject()) + " " + nullSafe(email.body()));
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractPlatform(String all) {
        for (String platform : ASSESSMENT_PLATFORMS) {
            if (all.contains(platform.toLowerCase(Locale.ROOT))) {
                return platform;
            }
        }
        return null;
    }

    private boolean looksLikePosition(String candidate) {
        if (candidate == null || candidate.length() < 4 || candidate.length() > 80) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        return lower.contains("intern") || lower.contains("engineer") || lower.contains("developer")
                || lower.contains("analyst") || lower.contains("scientist") || lower.contains("co-op")
                || lower.contains("manager") || lower.contains("designer");
    }

    private boolean isSharedDomain(String domain) {
        return domain.endsWith("greenhouse-mail.io") || domain.endsWith("greenhouse.io")
                || domain.endsWith("myworkday.com") || domain.endsWith("lever.co")
                || domain.endsWith("ashbyhq.com") || domain.endsWith("icims.com")
                || domain.endsWith("smartrecruiters.com") || domain.endsWith("jobvite.com")
                || domain.endsWith("successfactors.com") || domain.endsWith("gmail.com")
                || domain.endsWith("linkedin.com") || domain.endsWith("indeed.com");
    }

    private String firstMatch(String haystack, List<String> phrases) {
        for (String phrase : phrases) {
            if (haystack.contains(phrase)) {
                return phrase;
            }
        }
        return null;
    }

    private String cleanup(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[!.,;:]+$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String capitalise(String value) {
        return value.isEmpty() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
