package com.fasttrack.classify;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides, with rules alone, whether an email is worth sending to the LLM.
 *
 * <p>This is the cheapest and most effective cost control in the system: a
 * mailbox during application season is mostly job <em>alerts</em>, networking
 * noise and marketing, none of which say anything about an application the
 * user submitted. Every email this filter drops is an LLM call that never
 * happens, and the drop rate is reported in RESULTS.md.
 *
 * <p>Order is deliberate:
 * <ol>
 *   <li><b>Drop rules first.</b> "20 new Software Engineer jobs for you"
 *       contains the word "engineer" and "jobs" and would otherwise be kept by
 *       a keyword rule. The noise phrases are specific enough to win.</li>
 *   <li><b>ATS senders second.</b> Mail from Workday, Greenhouse, Lever and
 *       friends is almost always about a real application.</li>
 *   <li><b>Keyword rules last</b>, over subject and snippet.</li>
 *   <li><b>Default drop.</b> Keeping an unrelated email costs an LLM call and
 *       risks a wrong application; the classifier never sees it.</li>
 * </ol>
 */
@Component
public class EmailPrefilter {

    /** Applicant tracking systems and assessment platforms. */
    static final Set<String> ATS_DOMAINS = Set.of(
            "myworkday.com", "workday.com", "wd1.myworkdayjobs.com", "wd5.myworkdayjobs.com",
            "greenhouse-mail.io", "greenhouse.io", "us.greenhouse-mail.io",
            "lever.co", "hire.lever.co",
            "ashbyhq.com", "app.ashbyhq.com",
            "icims.com", "talent.icims.com",
            "smartrecruiters.com", "smartrecruiters.io",
            "jobvite.com", "hire.jobvite.com",
            "pinpoint.email", "breezy-mail.com", "breezy.hr",
            "successfactors.com", "successfactors.eu", "sap.com",
            "taleo.net", "brassring.com", "avature.net", "eightfold.ai",
            "paylocity.com", "paycomonline.net", "ultipro.com", "dayforcehcm.com",
            "bamboohr.com", "recruitee.com", "workable.com", "teamtailor-mail.com",
            "codesignal.com", "hackerrank.com", "hackerrankforwork.com",
            "codility.com", "hirevue.com", "karat.io", "coderpad.io",
            "goodtime.io", "calendly.com", "modernloop.io");

    /**
     * Phrases that mark an email as noise even when it is full of job words.
     * Each one is matched against the subject and the snippet, lowercased.
     */
    static final List<String> NOISE_PHRASES = List.of(
            // job alerts and recommendations
            "job alert", "jobs for you", "new jobs", "jobs you may", "recommended for you",
            "recommended jobs", "job recommendations", "jobs matching", "new opportunities for you",
            "based on your profile", "top job picks", "your job alert", "similar jobs",
            "keep searching", "jobs in your area", "apply before", "still hiring",
            // saved jobs / reminders to apply
            "saved job", "you saved", "finish your application for", "complete your application for",
            "don't forget to apply", "your saved search",
            // networking noise
            "viewed your profile", "people you may know", "invitation to connect",
            "added you", "endorsed you", "wants to connect", "new connection",
            "congratulate", "work anniversary", "is hiring - see who",
            // marketing / events / newsletters
            "newsletter", "webinar", "register now", "free trial", "upgrade your",
            "premium free", "% off", "limited time", "join us for", "rsvp",
            "career fair", "info session", "tech talk", "workshop",
            "digest", "weekly roundup", "this week in", "sponsored",
            // account noise that is not about an application decision
            "password reset", "verify your email address", "security alert",
            "your subscription", "receipt for", "invoice");

    /** Words that mean the email is probably about an application the user made. */
    static final List<String> JOB_KEYWORDS = List.of(
            "application", "applying", "applied", "applicant", "candidate", "candidacy",
            "interview", "assessment", "coding challenge", "online assessment",
            "unfortunately", "next steps", "move forward", "offer", "recruiter",
            "hiring team", "talent acquisition", "req id", "requisition",
            "position you applied", "role you applied", "your submission",
            "thank you for your interest", "we received your", "status of your",
            // recruiter outreach rarely uses the word "application" at all
            "opportunity at", "opportunity with", "came across your profile",
            "recruiting for", "internship opening", "openings for", "your profile caught",
            "summer 2027", "summer 2026", "internship");

    /**
     * Phrases that are decisive on their own: if one appears, the email is kept
     * even when a noise phrase also matched. "Your application was sent to
     * Acme" is a real confirmation that happens to come from a job board.
     */
    static final List<String> STRONG_SIGNALS = List.of(
            "your application was sent", "application received", "we received your application",
            "thank you for applying", "application to", "your application for",
            "interview invitation", "schedule your interview", "assessment invitation",
            "we would like to invite you", "regret to inform", "not moving forward",
            "offer of employment", "your offer");

    public PrefilterDecision evaluate(String senderEmail, String subject, String body) {
        String domain = domainOf(senderEmail);
        String haystack = lower(subject) + " \n " + lower(body);

        String strong = firstMatch(haystack, STRONG_SIGNALS);
        if (strong != null) {
            return PrefilterDecision.keep("strong_signal",
                    "Contains \"" + strong + "\", which only appears in real application mail");
        }

        String noise = firstMatch(haystack, NOISE_PHRASES);
        if (noise != null) {
            return PrefilterDecision.drop("noise_phrase",
                    "Looks like a job alert, newsletter or networking notice (\"" + noise + "\")");
        }

        if (isAtsDomain(domain)) {
            return PrefilterDecision.keep("ats_domain",
                    "Sent by an applicant tracking system (" + domain + ")");
        }

        String keyword = firstMatch(haystack, JOB_KEYWORDS);
        if (keyword != null) {
            return PrefilterDecision.keep("job_keyword",
                    "Mentions \"" + keyword + "\"");
        }

        return PrefilterDecision.drop("no_signal",
                "Nothing in this email refers to a job application");
    }

    /** True for an ATS domain or any subdomain of one. */
    public boolean isAtsDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        if (ATS_DOMAINS.contains(normalized)) {
            return true;
        }
        return ATS_DOMAINS.stream().anyMatch(known -> normalized.endsWith("." + known));
    }

    private String firstMatch(String haystack, List<String> phrases) {
        for (String phrase : phrases) {
            if (haystack.contains(phrase)) {
                return phrase;
            }
        }
        return null;
    }

    private String domainOf(String email) {
        if (email == null) {
            return "";
        }
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? email : email.substring(at + 1);
        return domain.trim().toLowerCase(Locale.ROOT).replaceAll("[>\\s]", "");
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
