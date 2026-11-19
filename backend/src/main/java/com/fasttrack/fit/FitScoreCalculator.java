package com.fasttrack.fit;

import com.fasttrack.fit.FitScore.EligibilityCheck;
import com.fasttrack.fit.FitScore.Suggestion;
import com.fasttrack.posting.PostingRequirements;
import com.fasttrack.resume.ResumeProfile;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a resume and a posting into a score, deterministically.
 *
 * <p>The arithmetic is fixed and shown to the user:
 *
 * <pre>
 *   50%  required-skill coverage
 *   20%  preferred-skill coverage
 *   15%  experience and project relevance   (rated 0-1, with a reason)
 *   15%  eligibility                        (graduation, degree, work authorisation)
 * </pre>
 *
 * <p>Two rules make the number honest rather than flattering:
 * <ul>
 *   <li><b>A hard eligibility mismatch caps the total at 40.</b> A posting
 *       that requires citizenship, or graduation by a date the user cannot
 *       meet, is not an 80% fit however well the skills line up.</li>
 *   <li><b>Missing sections are redistributed, not scored as zero.</b> A
 *       posting whose required-skills list could not be extracted would
 *       otherwise lose half its score for the posting's own vagueness; the
 *       weight moves to the components that do have data, and the response
 *       says so.</li>
 * </ul>
 *
 * <p>The only non-deterministic input is the experience rating, which the
 * caller supplies (from the LLM, or from the heuristic fallback). Given the
 * same inputs this class always produces the same output, which is what makes
 * the evaluation in RESULTS.md meaningful.
 */
@Component
public class FitScoreCalculator {

    private static final double WEIGHT_REQUIRED = 0.50;
    private static final double WEIGHT_PREFERRED = 0.20;
    private static final double WEIGHT_EXPERIENCE = 0.15;
    private static final double WEIGHT_ELIGIBILITY = 0.15;

    /** The ceiling applied when a hard requirement is not met. */
    public static final int HARD_MISMATCH_CAP = 40;

    /**
     * The ceiling applied when no skills could be read from the posting.
     *
     * <p>Without this, a vague posting - "bright, motivated students" - scores
     * highly, because the only components left to score are experience and
     * eligibility, and both tend to be generous. Telling someone they are a
     * strong fit for a posting the system could not read would be the most
     * misleading thing this product could do.
     */
    public static final int UNREADABLE_POSTING_CAP = 50;

    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern WORD = Pattern.compile("[a-z0-9+#./-]{3,}");

    /** Words that carry no signal in an ATS keyword comparison. */
    private static final Set<String> STOPWORDS = Set.of(
            "and", "the", "for", "with", "you", "your", "our", "are", "will", "have", "has",
            "this", "that", "from", "they", "their", "them", "who", "how", "what", "when",
            "work", "working", "team", "teams", "role", "roles", "job", "position", "company",
            "candidate", "candidates", "applicant", "experience", "experiences", "ability",
            "strong", "excellent", "good", "great", "plus", "etc", "including", "include",
            "includes", "other", "others", "new", "well", "also", "must", "should", "would",
            "can", "may", "help", "helps", "across", "within", "into", "about", "more", "most",
            "all", "any", "such", "like", "using", "use", "used", "uses", "while", "both");

    private final SkillDictionary dictionary;

    public FitScoreCalculator(SkillDictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * The LLM's (or fallback's) judgement of how relevant the candidate's
     * actual work is to this posting.
     *
     * @param score  0..1
     * @param reason one line, shown to the user
     */
    public record ExperienceRating(double score, String reason) {

        public static ExperienceRating of(double score, String reason) {
            return new ExperienceRating(Math.clamp(score, 0.0, 1.0), reason);
        }
    }

    public FitScore score(ResumeProfile resume,
                          PostingRequirements posting,
                          ExperienceRating experience,
                          String resumeFullText) {

        Set<String> held = dictionary.expandAll(resume.allSkills());

        SkillMatch required = matchSkills(posting.requiredOrEmpty(), held);
        SkillMatch preferred = matchSkills(posting.preferredOrEmpty(), held);

        List<EligibilityCheck> checks = eligibilityChecks(resume, posting);
        boolean hardMismatch = checks.stream()
                .anyMatch(check -> check.hard() && check.status() == EligibilityCheck.Status.FAIL);
        double eligibilityScore = eligibilityScore(checks);

        KeywordMatch keywords = atsKeywords(posting, resumeFullText, resume);

        // Weights are redistributed when a section has nothing to score.
        double weightRequired = required.total() > 0 ? WEIGHT_REQUIRED : 0;
        double weightPreferred = preferred.total() > 0 ? WEIGHT_PREFERRED : 0;
        double weightExperience = WEIGHT_EXPERIENCE;
        double weightEligibility = WEIGHT_ELIGIBILITY;
        double totalWeight = weightRequired + weightPreferred + weightExperience + weightEligibility;

        double weighted =
                required.coverage() * weightRequired
                + preferred.coverage() * weightPreferred
                + experience.score() * weightExperience
                + eligibilityScore * weightEligibility;

        int overall = (int) Math.round(weighted / totalWeight * 100);
        String weightingNote = weightingNote(required.total(), preferred.total());

        boolean unreadablePosting = required.total() == 0 && preferred.total() == 0;
        if (unreadablePosting && overall > UNREADABLE_POSTING_CAP) {
            overall = UNREADABLE_POSTING_CAP;
        }
        if (hardMismatch && overall > HARD_MISMATCH_CAP) {
            overall = HARD_MISMATCH_CAP;
        }
        overall = Math.clamp(overall, 0, 100);

        return new FitScore(
                overall,
                FitBand.of(overall),
                required.coverage(),
                preferred.coverage(),
                experience.score(),
                eligibilityScore,
                keywords.coverage(),
                hardMismatch,
                required.matched(),
                required.missing(),
                preferred.matched(),
                preferred.missing(),
                keywords.matched(),
                keywords.missing(),
                checks,
                suggestions(resume, posting, required, preferred, checks, keywords),
                experience.reason(),
                weightingNote,
                FitScore.VERSION);
    }

    // ------------------------------------------------------------- skills

    private record SkillMatch(List<String> matched, List<String> missing) {

        int total() {
            return matched.size() + missing.size();
        }

        double coverage() {
            return total() == 0 ? 0 : (double) matched.size() / total();
        }
    }

    private SkillMatch matchSkills(List<String> wanted, Set<String> held) {
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String skill : wanted) {
            String canonical = dictionary.canonical(skill);
            if (canonical.isEmpty() || !seen.add(canonical)) {
                continue;
            }
            if (held.contains(canonical)) {
                matched.add(canonical);
            } else {
                missing.add(canonical);
            }
        }
        return new SkillMatch(matched, missing);
    }

    // -------------------------------------------------------- eligibility

    private List<EligibilityCheck> eligibilityChecks(ResumeProfile resume, PostingRequirements posting) {
        List<EligibilityCheck> checks = new ArrayList<>();

        checks.add(graduationCheck(resume, posting));
        checks.add(degreeCheck(resume, posting));
        checks.add(workAuthorizationCheck(resume, posting));

        checks.removeIf(java.util.Objects::isNull);
        return checks;
    }

    private EligibilityCheck graduationCheck(ResumeProfile resume, PostingRequirements posting) {
        String window = posting.graduationWindow();
        if (window == null || window.isBlank()) {
            return null;
        }
        String graduation = resume.education() == null ? null : resume.education().expectedGraduation();
        if (graduation == null || graduation.isBlank()) {
            return new EligibilityCheck("Graduation window", EligibilityCheck.Status.UNKNOWN, false,
                    "The posting wants " + window + "; your resume does not state a graduation date");
        }

        Integer resumeYear = firstYear(graduation);
        List<Integer> wantedYears = allYears(window);
        if (resumeYear == null || wantedYears.isEmpty()) {
            return new EligibilityCheck("Graduation window", EligibilityCheck.Status.UNKNOWN, false,
                    "Could not compare \"" + graduation + "\" with \"" + window + "\"");
        }

        boolean inWindow = wantedYears.size() == 1
                ? resumeYear.equals(wantedYears.get(0))
                : resumeYear >= wantedYears.get(0) && resumeYear <= wantedYears.get(wantedYears.size() - 1);

        return new EligibilityCheck("Graduation window",
                inWindow ? EligibilityCheck.Status.PASS : EligibilityCheck.Status.FAIL,
                true,
                inWindow
                        ? "You graduate " + graduation + ", which the posting accepts (" + window + ")"
                        : "The posting wants " + window + "; your resume says " + graduation);
    }

    private EligibilityCheck degreeCheck(ResumeProfile resume, PostingRequirements posting) {
        String required = posting.minEducation();
        if (required == null || required.isBlank()) {
            return null;
        }
        String degree = resume.education() == null ? null : resume.education().degree();
        if (degree == null || degree.isBlank()) {
            return new EligibilityCheck("Degree", EligibilityCheck.Status.UNKNOWN, false,
                    "The posting asks for " + required + "; your resume does not state a degree");
        }

        int requiredLevel = degreeLevel(required);
        int heldLevel = degreeLevel(degree);
        boolean meets = heldLevel >= requiredLevel;

        return new EligibilityCheck("Degree",
                meets ? EligibilityCheck.Status.PASS : EligibilityCheck.Status.FAIL,
                true,
                meets ? degree + " meets the requirement (" + required + ")"
                      : "The posting asks for " + required + "; your resume says " + degree);
    }

    private EligibilityCheck workAuthorizationCheck(ResumeProfile resume, PostingRequirements posting) {
        String requirement = posting.workAuthorizationRequirements();
        if (requirement == null || requirement.isBlank()) {
            return null;
        }
        String lowered = requirement.toLowerCase(Locale.ROOT);
        boolean strict = lowered.contains("citizen")
                || lowered.contains("clearance")
                || lowered.contains("without sponsorship")
                || lowered.contains("no sponsorship")
                || lowered.contains("not provide sponsorship")
                || lowered.contains("permanent resident");

        String stated = resume.workAuthorization();
        if (stated == null || stated.isBlank()) {
            return new EligibilityCheck("Work authorisation", EligibilityCheck.Status.UNKNOWN, false,
                    "The posting says: " + requirement + ". Your resume does not state your status, "
                    + "so this has not been scored either way");
        }

        String statedLower = stated.toLowerCase(Locale.ROOT);
        boolean needsSponsorship = statedLower.contains("sponsor")
                || statedLower.contains("f-1") || statedLower.contains("f1")
                || statedLower.contains("opt") || statedLower.contains("visa");
        boolean citizenOrPr = statedLower.contains("citizen") || statedLower.contains("permanent resident")
                || statedLower.contains("green card");

        if (strict && needsSponsorship && !citizenOrPr) {
            return new EligibilityCheck("Work authorisation", EligibilityCheck.Status.FAIL, true,
                    "The posting requires " + requirement + ", and your resume states " + stated);
        }
        return new EligibilityCheck("Work authorisation", EligibilityCheck.Status.PASS, true,
                "Your stated status (" + stated + ") meets the posting's requirement");
    }

    private double eligibilityScore(List<EligibilityCheck> checks) {
        if (checks.isEmpty()) {
            // Nothing stated either way: neither a pass nor a penalty.
            return 1.0;
        }
        double sum = 0;
        for (EligibilityCheck check : checks) {
            sum += switch (check.status()) {
                case PASS -> 1.0;
                case UNKNOWN -> 0.5;   // unstated is not the same as failing
                case FAIL -> 0.0;
            };
        }
        return sum / checks.size();
    }

    private int degreeLevel(String degree) {
        String lowered = degree.toLowerCase(Locale.ROOT);
        if (lowered.contains("phd") || lowered.contains("doctor")) {
            return 4;
        }
        if (lowered.contains("master") || lowered.contains("msc") || lowered.contains("m.s")
                || lowered.contains("ms ") || lowered.contains("mba")) {
            return 3;
        }
        if (lowered.contains("bachelor") || lowered.contains("bsc") || lowered.contains("b.s")
                || lowered.contains("bs ") || lowered.contains("undergrad")) {
            return 2;
        }
        if (lowered.contains("associate") || lowered.contains("high school") || lowered.contains("diploma")) {
            return 1;
        }
        // "Currently pursuing a degree" reads as working toward a bachelor's.
        return lowered.contains("pursuing") || lowered.contains("in progress") ? 2 : 2;
    }

    private Integer firstYear(String text) {
        Matcher matcher = YEAR.matcher(text);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private List<Integer> allYears(String text) {
        List<Integer> years = new ArrayList<>();
        Matcher matcher = YEAR.matcher(text);
        while (matcher.find()) {
            years.add(Integer.valueOf(matcher.group(1)));
        }
        years.sort(Integer::compareTo);
        return years;
    }

    // ------------------------------------------------------- ATS keywords

    private record KeywordMatch(List<String> matched, List<String> missing) {

        double coverage() {
            int total = matched.size() + missing.size();
            return total == 0 ? 0 : (double) matched.size() / total;
        }
    }

    /**
     * The share of the posting's distinctive words that appear anywhere in the
     * resume. This is a rough stand-in for what a keyword-matching ATS does -
     * it is reported as information, not folded into the score, because
     * keyword stuffing is exactly the behaviour this product should not
     * encourage.
     */
    private KeywordMatch atsKeywords(PostingRequirements posting, String resumeText, ResumeProfile resume) {
        Set<String> keywords = new LinkedHashSet<>();
        posting.requiredOrEmpty().forEach(skill -> keywords.add(dictionary.canonical(skill).toLowerCase(Locale.ROOT)));
        posting.preferredOrEmpty().forEach(skill -> keywords.add(dictionary.canonical(skill).toLowerCase(Locale.ROOT)));

        for (String responsibility : posting.responsibilitiesOrEmpty()) {
            Matcher matcher = WORD.matcher(responsibility.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String word = matcher.group();
                if (!STOPWORDS.contains(word)) {
                    keywords.add(word);
                }
            }
        }
        keywords.removeIf(String::isBlank);

        String haystack = ((resumeText == null ? "" : resumeText) + "\n"
                + String.join("\n", resume.allSkills()) + "\n" + resume.narrative())
                .toLowerCase(Locale.ROOT);

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) {
                matched.add(keyword);
            } else {
                missing.add(keyword);
            }
        }
        return new KeywordMatch(matched, missing);
    }

    // -------------------------------------------------------- suggestions

    /**
     * Honest suggestions only.
     *
     * <p>The hard rule, asserted by a test: a suggestion may never tell the
     * user to add a skill, a number or an experience the resume does not
     * already evidence. Missing skills produce learning suggestions, never
     * "add this to your resume".
     */
    private List<Suggestion> suggestions(ResumeProfile resume,
                                         PostingRequirements posting,
                                         SkillMatch required,
                                         SkillMatch preferred,
                                         List<EligibilityCheck> checks,
                                         KeywordMatch keywords) {
        List<Suggestion> suggestions = new ArrayList<>();
        Set<String> held = dictionary.expandAll(resume.allSkills());
        String narrative = resume.narrative().toLowerCase(Locale.ROOT);

        // 1. Skills the resume has but the narrative never demonstrates.
        for (String skill : required.matched()) {
            if (!narrative.contains(skill.toLowerCase(Locale.ROOT))) {
                suggestions.add(new Suggestion(Suggestion.Kind.SURFACE,
                        "You list " + skill + " but no bullet point mentions it. The posting asks "
                        + "for it - move it into a project or experience bullet where you used it."));
            }
        }

        // 2. Words the posting uses that the resume evidences differently.
        for (String keyword : keywords.missing()) {
            String canonical = dictionary.canonical(keyword);
            if (held.contains(canonical) && !canonical.equalsIgnoreCase(keyword)) {
                suggestions.add(new Suggestion(Suggestion.Kind.REWORD,
                        "The posting says \"" + keyword + "\" where your resume says \"" + canonical
                        + "\". Using the posting's wording helps keyword screens."));
            }
        }

        // 3. Genuine gaps: learn them, do not claim them.
        for (String skill : required.missing()) {
            suggestions.add(new Suggestion(Suggestion.Kind.LEARN,
                    skill + " is required and your resume does not show it. Build something small "
                    + "with it before applying, or be ready to say honestly that you have not used it."));
        }
        for (String skill : preferred.missing()) {
            suggestions.add(new Suggestion(Suggestion.Kind.LEARN,
                    skill + " is preferred, not required - a weekend project using it would close "
                    + "the gap, and its absence should not stop you applying."));
        }

        // 4. Eligibility problems, stated plainly.
        for (EligibilityCheck check : checks) {
            if (check.status() == EligibilityCheck.Status.FAIL) {
                suggestions.add(new Suggestion(Suggestion.Kind.ELIGIBILITY,
                        check.name() + ": " + check.detail()
                        + ". This is a hard requirement, so the score is capped."));
            } else if (check.status() == EligibilityCheck.Status.UNKNOWN) {
                suggestions.add(new Suggestion(Suggestion.Kind.ELIGIBILITY,
                        check.name() + ": " + check.detail()
                        + ". Stating it on your resume would let this be scored."));
            }
        }

        if (suggestions.isEmpty()) {
            suggestions.add(new Suggestion(Suggestion.Kind.SURFACE,
                    "Nothing to flag: your resume covers what this posting asks for."));
        }
        return suggestions;
    }

    /** Says when the published weights were not all usable, and why. */
    private String weightingNote(int requiredCount, int preferredCount) {
        if (requiredCount > 0 && preferredCount > 0) {
            return null;
        }
        if (requiredCount == 0 && preferredCount == 0) {
            return "No skills could be read from this posting, so the score rests on experience "
                 + "relevance and eligibility alone and is capped at " + UNREADABLE_POSTING_CAP
                 + ". Paste the full posting text, including its requirements section, for a "
                 + "score worth acting on.";
        }
        if (requiredCount == 0) {
            return "This posting does not separate required skills, so the required-skill weight "
                 + "was redistributed across the other components.";
        }
        return "This posting lists no preferred skills, so that weight was redistributed.";
    }

    /** Exposed for the docs and the UI legend. */
    public static String weightsDescription() {
        return "50% required skills, 20% preferred skills, 15% experience relevance, "
             + "15% eligibility. A hard eligibility mismatch caps the total at " + HARD_MISMATCH_CAP
             + "; a posting with no readable skills caps it at " + UNREADABLE_POSTING_CAP + ".";
    }

    /** The current academic year, used by the heuristic experience rating. */
    static int currentYear() {
        return Year.now().getValue();
    }
}
