package com.fasttrack.resume;

import com.fasttrack.fit.SkillDictionary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Structures a resume without a model.
 *
 * <p>Used when no LLM key is configured, and whenever the model's reply is
 * unusable. It is deliberately modest: it finds skills the dictionary knows,
 * the education line, and a stated work-authorisation phrase, and it leaves
 * experience and projects to the user. A thin but honest result beats a
 * confident wrong one - and everything it produces is shown for correction
 * before it is used.
 */
@Component
public class HeuristicResumeParser {

    private static final Pattern GRADUATION = Pattern.compile(
            "(?:expected\\s+graduation|graduation|expected|graduating)\\s*[:\\-]?\\s*"
            + "((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s*)?(20\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DEGREE = Pattern.compile(
            "\\b(bachelor(?:'s)?(?:\\s+of\\s+\\w+)?|b\\.?s\\.?|b\\.?a\\.?|master(?:'s)?|m\\.?s\\.?|ph\\.?d\\.?|associate)\\b"
            + "(?:\\s+(?:of|in)\\s+([A-Za-z ]{3,40}))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHOOL = Pattern.compile(
            "\\b([A-Z][\\w.'-]*(?:\\s+[A-Z][\\w.'-]*){0,4}\\s+(?:University|College|Institute of Technology|Institute))\\b");

    private static final Pattern WORK_AUTH = Pattern.compile(
            "\\b(u\\.?s\\.? citizen|us citizen|permanent resident|green card|f-?1 (?:visa|student)[^.\\n]*|"
            + "opt[^.\\n]*|cpt[^.\\n]*|authorized to work[^.\\n]*|require[sd]? sponsorship[^.\\n]*|"
            + "no sponsorship required[^.\\n]*)",
            Pattern.CASE_INSENSITIVE);

    /** Categories, so found skills land in the right bucket. */
    private static final Set<String> LANGUAGES = Set.of(
            "Java", "JavaScript", "TypeScript", "Python", "C++", "C#", "Go", "Kotlin", "Swift",
            "Rust", "Ruby", "PHP", "Scala", "R", "MATLAB", "SQL", "Bash", "HTML", "CSS");
    private static final Set<String> DATABASES = Set.of(
            "PostgreSQL", "MySQL", "MongoDB", "Redis", "SQLite", "DynamoDB", "Oracle",
            "SQL Server", "Elasticsearch", "Snowflake");
    private static final Set<String> CLOUD = Set.of("AWS", "Azure", "GCP");
    private static final Set<String> TOOLS = Set.of(
            "Git", "Docker", "Kubernetes", "Terraform", "Jenkins", "GitHub Actions", "GitLab CI",
            "CI/CD", "Linux", "Nginx", "Agile", "JUnit", "pytest", "Jest", "Playwright",
            "Selenium", "Jira", "Figma");

    private final SkillDictionary dictionary;

    public HeuristicResumeParser(SkillDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public ResumeProfile parse(String resumeText) {
        String text = resumeText == null ? "" : resumeText;

        Set<String> found = findSkills(text);
        List<String> languages = new ArrayList<>();
        List<String> databases = new ArrayList<>();
        List<String> cloud = new ArrayList<>();
        List<String> tools = new ArrayList<>();
        List<String> frameworks = new ArrayList<>();

        for (String skill : found) {
            if (LANGUAGES.contains(skill)) {
                languages.add(skill);
            } else if (DATABASES.contains(skill)) {
                databases.add(skill);
            } else if (CLOUD.contains(skill)) {
                cloud.add(skill);
            } else if (TOOLS.contains(skill)) {
                tools.add(skill);
            } else {
                frameworks.add(skill);
            }
        }

        return new ResumeProfile(languages, frameworks, databases, tools, cloud,
                List.of(), List.of(), education(text), workAuthorization(text));
    }

    /**
     * Finds every skill spelling the dictionary knows, as a whole word.
     * Unknown skills are not guessed at - the user adds them.
     */
    private Set<String> findSkills(String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        Set<String> found = new LinkedHashSet<>();

        for (String candidate : knownSpellings()) {
            if (containsWord(lowered, candidate.toLowerCase(Locale.ROOT))) {
                found.add(dictionary.canonical(candidate));
            }
        }
        return found;
    }

    private List<String> knownSpellings() {
        List<String> spellings = new ArrayList<>();
        spellings.addAll(LANGUAGES);
        spellings.addAll(DATABASES);
        spellings.addAll(CLOUD);
        spellings.addAll(TOOLS);
        spellings.addAll(List.of(
                "Spring Boot", "Spring", "React", "Next.js", "Node.js", "Express", "Angular",
                "Vue", "Django", "Flask", "FastAPI", "Rails", ".NET", "Hibernate", "Tailwind CSS",
                "PyTorch", "TensorFlow", "scikit-learn", "pandas", "NumPy", "Spark", "Kafka",
                "GraphQL", "REST", "Microservices", "Machine Learning", "Postgres", "Mongo",
                "k8s", "TypeScript", "JS"));
        return spellings;
    }

    /** Whole-word match, so "R" does not match every word containing r. */
    private boolean containsWord(String haystack, String needle) {
        String escaped = Pattern.quote(needle);
        return Pattern.compile("(?<![a-z0-9+#.])" + escaped + "(?![a-z0-9+#])")
                .matcher(haystack).find();
    }

    private ResumeProfile.Education education(String text) {
        Matcher graduation = GRADUATION.matcher(text);
        String expected = null;
        if (graduation.find()) {
            String month = graduation.group(1) == null ? "" : graduation.group(1).trim() + " ";
            expected = (month + graduation.group(2)).trim();
        }

        Matcher degree = DEGREE.matcher(text);
        String degreeText = null;
        String field = null;
        if (degree.find()) {
            degreeText = degree.group(1).trim();
            field = degree.group(2) == null ? null : degree.group(2).trim();
        }

        Matcher school = SCHOOL.matcher(text);
        String schoolName = school.find() ? school.group(1).trim() : null;

        if (expected == null && degreeText == null && schoolName == null) {
            return null;
        }
        return new ResumeProfile.Education(schoolName, degreeText, field, expected);
    }

    private String workAuthorization(String text) {
        Matcher matcher = WORK_AUTH.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
