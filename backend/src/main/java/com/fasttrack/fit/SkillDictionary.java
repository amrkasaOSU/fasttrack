package com.fasttrack.fit;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Maps the many ways one skill is written onto a single canonical name, and
 * knows which skills imply others.
 *
 * <p>Without this, a resume saying "Postgres" scores zero against a posting
 * asking for "PostgreSQL", which is the single most common way a naive fit
 * score goes wrong. The dictionary is deliberately hand-written and
 * conservative: it folds spellings and genuine supersets ("Spring Boot"
 * implies "Spring"), and nothing else. It never claims a skill the resume
 * does not show.
 */
@Component
public class SkillDictionary {

    /** alias (lowercase) -> canonical name. */
    private static final Map<String, String> ALIASES = new HashMap<>();

    /** canonical skill -> skills it genuinely implies. */
    private static final Map<String, Set<String>> IMPLIES = new HashMap<>();

    private static void alias(String canonical, String... aliases) {
        ALIASES.put(canonical.toLowerCase(Locale.ROOT), canonical);
        for (String alias : aliases) {
            ALIASES.put(alias.toLowerCase(Locale.ROOT), canonical);
        }
    }

    private static void implies(String skill, String... implied) {
        IMPLIES.computeIfAbsent(skill, key -> new LinkedHashSet<>()).addAll(Set.of(implied));
    }

    static {
        // ---- languages ----
        alias("Java", "java 8", "java 11", "java 17", "java 21", "core java", "java se");
        alias("JavaScript", "js", "es6", "ecmascript", "vanilla js");
        alias("TypeScript", "ts");
        alias("Python", "python3", "python 3", "py");
        alias("C++", "cpp", "c plus plus", "c/c++");
        alias("C#", "csharp", "c sharp", ".net c#");
        alias("Go", "golang");
        alias("Kotlin");
        alias("Swift");
        alias("Rust");
        alias("Ruby");
        alias("PHP");
        alias("Scala");
        alias("R");
        alias("MATLAB");
        alias("SQL", "ansi sql", "t-sql", "tsql", "pl/sql", "plsql");
        alias("Bash", "shell", "shell scripting", "zsh", "sh");
        alias("HTML", "html5");
        alias("CSS", "css3");

        // ---- frameworks and libraries ----
        alias("Spring", "spring framework");
        alias("Spring Boot", "springboot", "spring-boot");
        alias("React", "react.js", "reactjs", "react 18", "react 19");
        alias("Next.js", "nextjs", "next js");
        alias("Node.js", "node", "nodejs", "node js");
        alias("Express", "express.js", "expressjs");
        alias("Angular", "angularjs", "angular 2+");
        alias("Vue", "vue.js", "vuejs");
        alias("Django");
        alias("Flask");
        alias("FastAPI", "fast api");
        alias("Rails", "ruby on rails", "ror");
        alias(".NET", "dotnet", "asp.net", "asp .net");
        alias("Hibernate", "jpa", "spring data jpa");
        alias("Tailwind CSS", "tailwind", "tailwindcss");
        alias("jQuery");
        alias("PyTorch", "torch");
        alias("TensorFlow", "tf", "tensor flow");
        alias("scikit-learn", "sklearn", "scikit learn");
        alias("pandas");
        alias("NumPy", "numpy");
        alias("Spark", "apache spark", "pyspark");
        alias("Kafka", "apache kafka");
        alias("GraphQL", "graph ql");
        alias("REST", "rest api", "restful", "restful api", "rest apis");
        alias("gRPC", "grpc");

        // ---- databases ----
        alias("PostgreSQL", "postgres", "postgresql 16", "psql", "postgre sql");
        alias("MySQL", "my sql", "mariadb");
        alias("MongoDB", "mongo", "mongo db");
        alias("Redis", "redis cache");
        alias("SQLite", "sqlite3");
        alias("DynamoDB", "dynamo db");
        alias("Oracle", "oracle db", "oracle database");
        alias("SQL Server", "microsoft sql server", "mssql");
        alias("Elasticsearch", "elastic search", "opensearch");
        alias("Snowflake");

        // ---- cloud and infrastructure ----
        alias("AWS", "amazon web services", "amazon aws");
        alias("Azure", "microsoft azure");
        alias("GCP", "google cloud", "google cloud platform");
        alias("Docker", "containers", "containerisation", "containerization");
        alias("Kubernetes", "k8s", "kube");
        alias("Terraform", "hashicorp terraform");
        alias("Jenkins");
        alias("GitHub Actions", "gh actions", "github action");
        alias("GitLab CI", "gitlab ci/cd");
        alias("CI/CD", "ci cd", "continuous integration", "continuous delivery", "continuous deployment");
        alias("Linux", "unix", "ubuntu", "debian");
        alias("Nginx", "nginx server");

        // ---- tools and practice ----
        alias("Git", "github", "gitlab", "version control");
        alias("Agile", "scrum", "kanban", "agile methodologies");
        alias("JUnit", "junit 5", "junit5");
        alias("pytest", "py.test");
        alias("Jest", "jest testing");
        alias("Playwright");
        alias("Selenium");
        alias("Jira");
        alias("Figma");
        alias("Microservices", "microservice architecture", "micro services");
        alias("Machine Learning", "ml", "machine-learning");
        alias("Data Structures", "data structures and algorithms", "dsa", "algorithms");
        alias("Object-Oriented Programming", "oop", "object oriented programming", "object-oriented design");
        alias("Distributed Systems", "distributed computing");

        // ---- supersets: holding the left implies the right ----
        implies("Spring Boot", "Spring", "Java");
        implies("Spring", "Java");
        implies("Hibernate", "SQL");
        implies("Next.js", "React", "JavaScript");
        implies("React", "JavaScript");
        implies("Angular", "TypeScript", "JavaScript");
        implies("Vue", "JavaScript");
        implies("TypeScript", "JavaScript");
        implies("Express", "Node.js", "JavaScript");
        implies("Node.js", "JavaScript");
        implies("Django", "Python");
        implies("Flask", "Python");
        implies("FastAPI", "Python");
        implies("PyTorch", "Python", "Machine Learning");
        implies("TensorFlow", "Python", "Machine Learning");
        implies("scikit-learn", "Python", "Machine Learning");
        implies("pandas", "Python");
        implies("NumPy", "Python");
        implies("Rails", "Ruby");
        implies("Kubernetes", "Docker");
        implies("GitHub Actions", "CI/CD");
        implies("GitLab CI", "CI/CD");
        implies("Jenkins", "CI/CD");
        implies("PostgreSQL", "SQL");
        implies("MySQL", "SQL");
        implies("SQL Server", "SQL");
        implies("Oracle", "SQL");
        implies("SQLite", "SQL");
        implies("Snowflake", "SQL");
    }

    /**
     * The canonical name for a written skill, or a tidied version of the input
     * when the dictionary has never heard of it. Unknown skills are kept, not
     * dropped - a posting asking for something niche still has to be scored.
     */
    public String canonical(String written) {
        if (written == null || written.isBlank()) {
            return "";
        }
        String cleaned = written.trim()
                .replaceAll("^[-*•\\s]+", "")
                .replaceAll("[.,;:]+$", "")
                .replaceAll("\\s{2,}", " ");
        String known = ALIASES.get(cleaned.toLowerCase(Locale.ROOT));
        return known != null ? known : cleaned;
    }

    /** Canonical skills a resume holding {@code skill} can also be said to have. */
    public Set<String> expand(String skill) {
        String canonical = canonical(skill);
        Set<String> all = new LinkedHashSet<>();
        if (canonical.isEmpty()) {
            return all;
        }
        all.add(canonical);
        Set<String> implied = IMPLIES.get(canonical);
        if (implied != null) {
            implied.forEach(value -> all.add(canonical(value)));
        }
        return all;
    }

    /** Every canonical skill a set of written skills covers, implications included. */
    public Set<String> expandAll(Iterable<String> skills) {
        Set<String> all = new LinkedHashSet<>();
        if (skills == null) {
            return all;
        }
        for (String skill : skills) {
            all.addAll(expand(skill));
        }
        return all;
    }

    /** True when the dictionary recognises this spelling. */
    public boolean isKnown(String written) {
        return written != null && ALIASES.containsKey(written.trim().toLowerCase(Locale.ROOT));
    }

    /** Number of distinct canonical skills the dictionary knows, for the docs. */
    public int size() {
        return (int) ALIASES.values().stream().distinct().count();
    }
}
