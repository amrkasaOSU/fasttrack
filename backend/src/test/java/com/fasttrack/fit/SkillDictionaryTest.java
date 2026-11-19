package com.fasttrack.fit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Skill spelling is the most common way a naive fit score goes wrong. */
class SkillDictionaryTest {

    private final SkillDictionary dictionary = new SkillDictionary();

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource({
            "postgres,            PostgreSQL",
            "Postgres,            PostgreSQL",
            "POSTGRESQL,          PostgreSQL",
            "js,                  JavaScript",
            "ES6,                 JavaScript",
            "ts,                  TypeScript",
            "golang,              Go",
            "k8s,                 Kubernetes",
            "amazon web services, AWS",
            "springboot,          Spring Boot",
            "react.js,            React",
            "node,                Node.js",
            "sklearn,             scikit-learn",
            "ci cd,               CI/CD",
            "oop,                 Object-Oriented Programming"
    })
    void foldsSpellings(String written, String canonical) {
        assertThat(dictionary.canonical(written)).isEqualTo(canonical);
    }

    @Test
    @DisplayName("an unknown skill is kept as written, not dropped")
    void unknownSkillsSurvive() {
        assertThat(dictionary.canonical("Verilog")).isEqualTo("Verilog");
        assertThat(dictionary.canonical("  - Rust  ")).isEqualTo("Rust");
        assertThat(dictionary.canonical("")).isEmpty();
        assertThat(dictionary.canonical(null)).isEmpty();
        assertThat(dictionary.isKnown("Verilog")).isFalse();
        assertThat(dictionary.isKnown("java")).isTrue();
    }

    @Test
    @DisplayName("a framework implies the language and platform it is built on")
    void supersetsExpand() {
        assertThat(dictionary.expand("Spring Boot")).contains("Spring Boot", "Spring", "Java");
        assertThat(dictionary.expand("Next.js")).contains("Next.js", "React", "JavaScript");
        assertThat(dictionary.expand("PyTorch")).contains("PyTorch", "Python", "Machine Learning");
        assertThat(dictionary.expand("Kubernetes")).contains("Kubernetes", "Docker");
        assertThat(dictionary.expand("PostgreSQL")).contains("PostgreSQL", "SQL");
    }

    @Test
    @DisplayName("implication does not run backwards - Java does not imply Spring Boot")
    void implicationIsOneWay() {
        assertThat(dictionary.expand("Java")).containsExactly("Java");
        assertThat(dictionary.expand("JavaScript")).containsExactly("JavaScript");
        assertThat(dictionary.expand("SQL")).containsExactly("SQL");
    }

    @Test
    @DisplayName("expanding a whole resume folds and implies in one pass")
    void expandAll() {
        var expanded = dictionary.expandAll(List.of("postgres", "springboot", "ts"));

        assertThat(expanded).contains("PostgreSQL", "SQL", "Spring Boot", "Spring", "Java",
                "TypeScript", "JavaScript");
        assertThat(dictionary.expandAll(null)).isEmpty();
    }

    @Test
    @DisplayName("the dictionary covers a usable number of skills")
    void sizeIsReported() {
        assertThat(dictionary.size()).isGreaterThan(80);
    }
}
