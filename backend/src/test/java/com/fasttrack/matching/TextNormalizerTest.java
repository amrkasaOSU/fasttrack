package com.fasttrack.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Normalization is what stops one role becoming five applications. These cases
 * are taken from the shapes real ATS emails use.
 */
class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @ParameterizedTest(name = "company \"{0}\" -> \"{1}\"")
    @CsvSource({
            "'Acme, Inc.',               acme",
            "'Acme Corporation',         acme",
            "'ACME Corp',                acme",
            "'Acme LLC',                 acme",
            "'The Home Depot',           home depot",
            "'Capital One',              capital one",
            "'Procter & Gamble',         procter gamble",
            "'Abercrombie & Fitch Co.',  abercrombie fitch",
            "'Honda R&D Americas',       honda r d americas",
            "'stripe.com',               stripe",
            "'Palo Alto Networks',       palo alto networks"
    })
    void companyNormalization(String raw, String expected) {
        assertThat(normalizer.normalizeCompany(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "position \"{0}\" -> \"{1}\"")
    @CsvSource({
            "'Software Engineer Intern - Summer 2027',            software engineer intern",
            "'Software Engineering Intern (Summer 2027)',         software engineer intern",
            "'SWE Intern, Summer 2027',                           software intern",
            "'Backend Engineer Intern [Req #12345]',              backend engineer intern",
            "'Data Engineer Intern - Fall 2026 Co-op',            data engineer intern",
            "'Software Developer Co-op',                          software engineer intern",
            "'Machine Learning Engineer Intern 2027',             machinelearning engineer intern",
            "'ML Engineer Intern',                                machinelearning engineer intern",
            "'Back End Engineer Intern',                          backend engineer intern",
            "'Mobile Engineer Intern (iOS)',                      mobile engineer intern ios"
    })
    void positionNormalization(String raw, String expected) {
        assertThat(normalizer.normalizePosition(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the same role written three ways produces the same key")
    void sameRoleDifferentSpellings() {
        String a = normalizer.normalizePosition("Software Engineer Intern - Summer 2027");
        String b = normalizer.normalizePosition("Software Engineering Intern (Summer 2027)");
        String c = normalizer.normalizePosition("Software Engineer Intern");

        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    @DisplayName("genuinely different roles keep different keys")
    void differentRolesStayDifferent() {
        String swe = normalizer.normalizePosition("Software Engineer Intern - Summer 2027");
        String data = normalizer.normalizePosition("Data Engineer Intern - Summer 2027");
        String security = normalizer.normalizePosition("Security Engineer Intern");

        assertThat(swe).isNotEqualTo(data);
        assertThat(swe).isNotEqualTo(security);
        assertThat(data).isNotEqualTo(security);
    }

    @Test
    @DisplayName("accents and odd casing do not create separate companies")
    void accentsAndCaseAreFolded() {
        assertThat(normalizer.normalizeCompany("Télécom Partners"))
                .isEqualTo(normalizer.normalizeCompany("TELECOM PARTNERS"));
    }

    @Test
    @DisplayName("empty and punctuation-only input produce an empty key rather than an exception")
    void emptyInput() {
        assertThat(normalizer.normalizeCompany(null)).isEmpty();
        assertThat(normalizer.normalizeCompany("   ")).isEmpty();
        assertThat(normalizer.normalizeCompany("!!!")).isEmpty();
        assertThat(normalizer.normalizePosition(null)).isEmpty();
        assertThat(normalizer.normalizePosition("---")).isEmpty();
    }

    @Test
    @DisplayName("a stated season or platform is kept as a qualifier, even though it is stripped for similarity")
    void qualifiersAreExtracted() {
        var summer = normalizer.qualifiers("Software Engineer Intern - Summer 2027");
        var fall = normalizer.qualifiers("Software Engineer Intern - Fall 2027");
        var ios = normalizer.qualifiers("Mobile Engineer Intern (iOS)");
        var android = normalizer.qualifiers("Mobile Engineer Intern (Android)");
        var plain = normalizer.qualifiers("Software Engineer Intern");

        assertThat(summer.season()).isEqualTo("summer");
        assertThat(summer.year()).isEqualTo("2027");
        assertThat(summer.conflictsWith(fall)).as("summer vs fall is two postings").isTrue();
        assertThat(ios.conflictsWith(android)).as("iOS vs Android is two postings").isTrue();
        assertThat(summer.conflictsWith(plain)).as("one side silent is not a conflict").isFalse();
        assertThat(plain.conflictsWith(null)).isFalse();
        assertThat(normalizer.qualifiers(null).season()).isNull();
    }

    @Test
    @DisplayName("tokens are the distinct words of the normalized string")
    void tokenisation() {
        assertThat(normalizer.tokens(normalizer.normalizePosition("Software Engineer Intern - Summer 2027")))
                .containsExactly("software", "engineer", "intern");
        assertThat(normalizer.tokens(null)).isEmpty();
        assertThat(normalizer.tokens("  ")).isEmpty();
    }

    @Test
    @DisplayName("a suffix word that is part of the name is kept when it leads")
    void leadingSuffixWordIsKept() {
        assertThat(normalizer.normalizeCompany("Group 1 Automotive")).isEqualTo("group 1 automotive");
    }
}
