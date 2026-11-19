package com.fasttrack.posting;

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
 * Reads a posting without a model, by section headings.
 *
 * <p>Postings are more structured than resumes: almost all of them have a
 * "Requirements"/"Qualifications" block and often a "Preferred"/"Nice to
 * have" block. Splitting on those headings and matching known skill spellings
 * inside each gets most of the way, and the user can see exactly what was
 * found.
 */
@Component
public class HeuristicPostingParser {

    private static final Pattern REQUIRED_HEADING = Pattern.compile(
            "(?im)^\\s*(?:basic\\s+)?(?:requirements|qualifications|minimum qualifications|"
            + "required skills|what you(?:'| wi)ll need|must have|who you are)\\s*:?\\s*$");

    private static final Pattern PREFERRED_HEADING = Pattern.compile(
            "(?im)^\\s*(?:preferred(?: qualifications| skills)?|nice to have|bonus(?: points)?|"
            + "a plus|desired(?: skills)?)\\s*:?\\s*$");

    private static final Pattern RESPONSIBILITY_HEADING = Pattern.compile(
            "(?im)^\\s*(?:responsibilities|what you(?:'| wi)ll do|the role|about the role|"
            + "day to day|your impact)\\s*:?\\s*$");

    private static final Pattern ANY_HEADING = Pattern.compile(
            "(?im)^\\s*[A-Z][A-Za-z ’'/&-]{2,40}\\s*:?\\s*$");

    private static final Pattern EDUCATION = Pattern.compile(
            "(?i)(bachelor[^.\\n]{0,80}|b\\.?s\\.?[^.\\n]{0,60}degree[^.\\n]{0,40}|"
            + "master[^.\\n]{0,80}|currently (?:enrolled|pursuing)[^.\\n]{0,80})");

    private static final Pattern GRADUATION_WINDOW = Pattern.compile(
            "(?i)(graduat\\w+[^.\\n]{0,80}20\\d{2}[^.\\n]{0,40}|"
            + "(?:december|may|june|spring|summer|fall|winter)\\s+20\\d{2}\\s*[-–to]+\\s*"
            + "(?:december|may|june|spring|summer|fall|winter)?\\s*20\\d{2})");

    private static final Pattern WORK_AUTH = Pattern.compile(
            "(?i)([^.\\n]{0,60}(?:without sponsorship|not (?:provide|offer) sponsorship|"
            + "u\\.?s\\.? citizen[^.\\n]{0,60}|security clearance[^.\\n]{0,60}|"
            + "authorized to work[^.\\n]{0,60})[^.\\n]{0,40})");

    private static final Pattern SEASON = Pattern.compile(
            "(?i)\\b((?:spring|summer|fall|autumn|winter)\\s+20\\d{2})\\b");

    private static final Pattern LOCATION = Pattern.compile(
            "(?im)^\\s*(?:location|office|based in)\\s*:?\\s*(.+)$");

    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*[-*•·▪]\\s*(.+)$");

    private final SkillDictionary dictionary;

    public HeuristicPostingParser(SkillDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public PostingRequirements parse(String postingText) {
        String text = postingText == null ? "" : postingText;

        String requiredSection = section(text, REQUIRED_HEADING);
        String preferredSection = section(text, PREFERRED_HEADING);
        String responsibilitiesSection = section(text, RESPONSIBILITY_HEADING);

        List<String> required = new ArrayList<>(findSkills(
                requiredSection.isBlank() ? text : requiredSection));
        List<String> preferred = new ArrayList<>(findSkills(preferredSection));

        // A skill listed as preferred should not also count as required.
        required.removeAll(preferred);

        List<String> responsibilities = bullets(
                responsibilitiesSection.isBlank() ? text : responsibilitiesSection);

        return new PostingRequirements(
                null,
                null,
                required,
                preferred,
                responsibilities,
                firstMatch(text, EDUCATION),
                firstMatch(text, GRADUATION_WINDOW),
                firstMatch(text, WORK_AUTH),
                firstGroup(text, LOCATION),
                firstMatch(text, SEASON));
    }

    /** The text under one heading, up to the next heading. */
    private String section(String text, Pattern heading) {
        Matcher matcher = heading.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        int start = matcher.end();
        Matcher next = ANY_HEADING.matcher(text);
        int end = text.length();
        while (next.find(start)) {
            if (next.start() > start) {
                end = next.start();
                break;
            }
        }
        return text.substring(start, Math.max(start, end));
    }

    private Set<String> findSkills(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        Set<String> found = new LinkedHashSet<>();

        for (String spelling : KNOWN_SPELLINGS) {
            String needle = spelling.toLowerCase(Locale.ROOT);
            if (Pattern.compile("(?<![a-z0-9+#.])" + Pattern.quote(needle) + "(?![a-z0-9+#])")
                    .matcher(lowered).find()) {
                found.add(dictionary.canonical(spelling));
            }
        }
        return found;
    }

    private List<String> bullets(String text) {
        List<String> lines = new ArrayList<>();
        Matcher matcher = BULLET.matcher(text);
        while (matcher.find() && lines.size() < 20) {
            String line = matcher.group(1).trim();
            if (line.length() > 8) {
                lines.add(line);
            }
        }
        return lines;
    }

    private String firstMatch(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String firstGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static final List<String> KNOWN_SPELLINGS = List.of(
            "Java", "JavaScript", "TypeScript", "Python", "C++", "C#", "Go", "Golang", "Kotlin",
            "Swift", "Rust", "Ruby", "PHP", "Scala", "SQL", "Bash", "HTML", "CSS",
            "Spring Boot", "Spring", "React", "Next.js", "Node.js", "Express", "Angular", "Vue",
            "Django", "Flask", "FastAPI", "Rails", ".NET", "Hibernate", "Tailwind",
            "PyTorch", "TensorFlow", "scikit-learn", "pandas", "NumPy", "Spark", "Kafka",
            "GraphQL", "REST", "gRPC", "Microservices", "Machine Learning",
            "PostgreSQL", "Postgres", "MySQL", "MongoDB", "Redis", "SQLite", "DynamoDB",
            "Oracle", "SQL Server", "Elasticsearch", "Snowflake",
            "AWS", "Azure", "GCP", "Google Cloud", "Docker", "Kubernetes", "Terraform",
            "Jenkins", "GitHub Actions", "CI/CD", "Linux", "Git", "Agile", "JUnit", "pytest",
            "Jest", "Selenium", "Jira", "Data Structures", "Algorithms",
            "Object-Oriented Programming", "Distributed Systems",
            // Short forms postings really use.
            "k8s", "JS", "TS", "Node", "ML", "OOP", "CI", "GitHub", "Mongo", "sklearn");
}
