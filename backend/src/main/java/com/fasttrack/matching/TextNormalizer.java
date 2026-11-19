package com.fasttrack.matching;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns the company and position strings that appear in email into stable keys
 * the matcher can compare.
 *
 * <p>The problem this solves: the same role shows up as
 * "Software Engineer Intern - Summer 2027 (Req #12345)",
 * "SWE Intern, Summer 2027" and "Software Engineering Intern" in three
 * different emails from "Acme Corp.", "Acme Corporation" and "ACME, Inc.".
 * Without normalization each one becomes its own application.
 *
 * <p>Everything here is deterministic and testable - no LLM is involved in
 * matching, so the same inputs always produce the same key.
 */
@Component
public class TextNormalizer {

    /** Legal and corporate suffixes that carry no identity. */
    private static final Set<String> COMPANY_SUFFIXES = Set.of(
            "inc", "incorporated", "llc", "llp", "lp", "ltd", "limited", "corp",
            "corporation", "co", "company", "plc", "gmbh", "ag", "sa", "nv", "bv",
            "ab", "oy", "as", "pte", "pty", "srl", "spa", "kk", "holdings", "group",
            "technologies", "technology", "labs", "laboratories");

    /** Words that say nothing about which role a posting is. */
    private static final Set<String> POSITION_STOPWORDS = Set.of(
            "the", "a", "an", "of", "for", "and", "or", "in", "at", "to", "with",
            "position", "opening", "opportunity", "role", "job", "req", "requisition",
            "id", "no", "number", "program", "programme");

    /** Seasons and co-op/internship cycles: "Summer 2027", "Fall 2026 Co-op". */
    private static final Pattern SEASON_YEAR = Pattern.compile(
            "\\b(spring|summer|fall|autumn|winter)\\b[\\s,/-]*((19|20)\\d{2})?", Pattern.CASE_INSENSITIVE);

    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");

    /** Requisition markers: "(Req #12345)", "- JR0012345", "[R-55]". */
    private static final Pattern REQ_MARKER = Pattern.compile(
            "\\b(req(uisition)?|job\\s*id|jr|r)\\s*[#:-]?\\s*[a-z]*\\d{2,}\\b", Pattern.CASE_INSENSITIVE);

    /** "Co-op", "co op", "coop" - all the same thing as an internship here. */
    private static final Pattern COOP = Pattern.compile("\\bco[\\s./-]?op(s)?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Only brackets whose contents are decoration get removed. "(Summer
     * 2027)" and "(Req #12345)" say nothing about which role this is;
     * "(iOS)" does, and stripping it merged two real postings in the matcher
     * evaluation.
     */
    private static final Pattern BRACKETED_DECORATION = Pattern.compile(
            "[\\(\\[\\{]\\s*(?:(?:spring|summer|fall|autumn|winter)[^\\)\\]\\}]*"
            + "|(?:19|20)\\d{2}[^\\)\\]\\}]*"
            + "|req[^\\)\\]\\}]*|job\\s*id[^\\)\\]\\}]*|r?\\d{4,}[^\\)\\]\\}]*)"
            + "[\\)\\]\\}]", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Company key: lowercase, accent-free, legal suffixes and punctuation
     * removed. "ACME, Inc." and "Acme Corporation" both become "acme".
     */
    public String normalizeCompany(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = basicClean(raw);
        text = BRACKETED_DECORATION.matcher(text).replaceAll(" ");
        // Strip a trailing domain if the company arrived as an email domain.
        text = text.replaceAll("\\.(com|io|co|org|net|ai|dev)\\b", " ");
        text = NON_ALNUM.matcher(text).replaceAll(" ");

        List<String> words = Arrays.stream(WHITESPACE.split(text.trim()))
                .filter(word -> !word.isBlank())
                .toList();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            boolean isSuffix = COMPANY_SUFFIXES.contains(word);
            // "Technologies" is only noise at the end: "Palo Alto Networks" keeps its words.
            if (isSuffix && i > 0) {
                continue;
            }
            if (i == 0 && word.equals("the") && words.size() > 1) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word);
        }
        return sb.toString().trim();
    }

    /**
     * Position key: lowercase, season/year, requisition markers, bracketed
     * asides and punctuation removed, stopwords dropped.
     * "Software Engineer Intern - Summer 2027 (Req #12345)" becomes
     * "software engineer intern".
     */
    public String normalizePosition(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = basicClean(raw);
        text = BRACKETED_DECORATION.matcher(text).replaceAll(" ");
        text = COOP.matcher(text).replaceAll(" intern ");
        // Spellings people genuinely use interchangeably.
        text = text.replaceAll("\\bback[\\s-]end\\b", "backend")
                   .replaceAll("\\bfront[\\s-]end\\b", "frontend")
                   .replaceAll("\\bfull[\\s-]stack\\b", "fullstack")
                   .replaceAll("\\bmachine[\\s-]learning\\b", "machinelearning");
        text = REQ_MARKER.matcher(text).replaceAll(" ");
        text = SEASON_YEAR.matcher(text).replaceAll(" ");
        text = YEAR.matcher(text).replaceAll(" ");
        text = NON_ALNUM.matcher(text).replaceAll(" ");

        StringBuilder sb = new StringBuilder();
        for (String word : WHITESPACE.split(text.trim())) {
            if (word.isBlank() || POSITION_STOPWORDS.contains(word)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(canonicalWord(word));
        }
        return sb.toString().trim();
    }

    /**
     * Details that are stripped for similarity but still distinguish two
     * postings when both titles state them and they disagree: the season a
     * posting is for, and the platform it targets.
     *
     * @param season  "summer", "fall", ... or null
     * @param year    four-digit year, or null
     * @param platform "ios", "android", "web", or null
     */
    public record Qualifiers(String season, String year, String platform) {

        /** True when both sides state the same kind of qualifier and differ. */
        public boolean conflictsWith(Qualifiers other) {
            if (other == null) {
                return false;
            }
            boolean seasonConflict = season != null && other.season != null && !season.equals(other.season);
            boolean yearConflict = year != null && other.year != null && !year.equals(other.year);
            boolean platformConflict = platform != null && other.platform != null
                    && !platform.equals(other.platform);
            return seasonConflict || yearConflict || platformConflict;
        }
    }

    private static final Pattern SEASON_WORD = Pattern.compile(
            "\\b(spring|summer|fall|autumn|winter)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLATFORM_WORD = Pattern.compile(
            "\\b(ios|android|web|mobile web|backend|frontend)\\b", Pattern.CASE_INSENSITIVE);

    /** Reads the distinguishing details out of a raw, un-normalized title. */
    public Qualifiers qualifiers(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return new Qualifiers(null, null, null);
        }
        String text = rawTitle.toLowerCase(Locale.ROOT);

        var seasonMatcher = SEASON_WORD.matcher(text);
        String season = seasonMatcher.find() ? seasonMatcher.group(1) : null;
        if ("autumn".equals(season)) {
            season = "fall";
        }

        var yearMatcher = YEAR.matcher(text);
        String year = yearMatcher.find() ? yearMatcher.group() : null;

        var platformMatcher = PLATFORM_WORD.matcher(text);
        String platform = null;
        while (platformMatcher.find()) {
            String candidate = platformMatcher.group(1).replace(" ", "");
            // Only the ones that really split a posting in two.
            if (candidate.equals("ios") || candidate.equals("android")) {
                platform = candidate;
                break;
            }
        }

        return new Qualifiers(season, year, platform);
    }

    /** The distinct words of a normalized string, in order of first appearance. */
    public Set<String> tokens(String normalized) {
        Set<String> tokens = new LinkedHashSet<>();
        if (normalized == null || normalized.isBlank()) {
            return tokens;
        }
        for (String token : WHITESPACE.split(normalized.trim())) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Common abbreviations folded to one spelling, so "SWE Intern" and
     * "Software Engineer Intern" share tokens.
     */
    private String canonicalWord(String word) {
        return switch (word) {
            case "swe", "sde" -> "software";
            case "eng", "engr", "engineering" -> "engineer";
            case "dev", "developer" -> "engineer";
            case "intern", "interns", "internship" -> "intern";
            case "sr", "senior" -> "senior";
            case "jr", "junior" -> "junior";
            case "mgr" -> "manager";
            case "ml" -> "machinelearning";
            case "ai" -> "artificial";
            case "ux" -> "design";
            case "qa" -> "quality";
            case "it" -> "technology";
            default -> word;
        };
    }

    private String basicClean(String raw) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replace('/', ' ')
                .replace('|', ' ');
        // Anything after a long dash is usually a location or a req id.
        int dash = text.indexOf(" - ");
        if (dash > 0) {
            String tail = text.substring(dash + 3);
            if (REQ_MARKER.matcher(tail).find() || SEASON_YEAR.matcher(tail).find()) {
                text = text.substring(0, dash);
            }
        }
        return text;
    }
}
