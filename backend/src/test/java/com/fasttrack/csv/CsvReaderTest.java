package com.fasttrack.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The CSV reader has to survive whatever a spreadsheet export throws at it. */
class CsvReaderTest {

    private List<CsvReader.Row> read(String content) {
        return CsvReader.read(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a plain file is read into rows keyed by lowercased header")
    void simpleFile() {
        List<CsvReader.Row> rows = read("""
                Company,Position,Status
                Stripe,Software Engineer Intern,Applied
                Datadog,Data Engineer Intern,Rejected
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("Company")).isEqualTo("Stripe");
        assertThat(rows.get(0).get("position")).isEqualTo("Software Engineer Intern");
        assertThat(rows.get(1).get("status")).isEqualTo("Rejected");
        assertThat(rows.get(0).lineNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("quoted fields keep their commas")
    void quotedCommas() {
        List<CsvReader.Row> rows = read("""
                Company,Location
                "Alphabet, Inc.","Columbus, OH"
                """);

        assertThat(rows.get(0).get("company")).isEqualTo("Alphabet, Inc.");
        assertThat(rows.get(0).get("location")).isEqualTo("Columbus, OH");
    }

    @Test
    @DisplayName("doubled quotes inside a quoted field are unescaped")
    void escapedQuotes() {
        List<CsvReader.Row> rows = read("Company,Notes\nStripe,\"They said \"\"maybe\"\" twice\"\n");

        assertThat(rows.get(0).get("notes")).isEqualTo("They said \"maybe\" twice");
    }

    @Test
    @DisplayName("a newline inside a quoted field does not split the row")
    void embeddedNewline() {
        List<CsvReader.Row> rows = read("Company,Notes\nStripe,\"line one\nline two\"\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("notes")).isEqualTo("line one\nline two");
    }

    @Test
    @DisplayName("Windows line endings and a UTF-8 BOM are handled")
    void bomAndCrlf() {
        List<CsvReader.Row> rows = read("﻿Company,Position\r\nStripe,SWE Intern\r\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("company")).isEqualTo("Stripe");
        assertThat(rows.get(0).get("position")).isEqualTo("SWE Intern");
    }

    @Test
    @DisplayName("short rows are padded, not dropped")
    void shortRow() {
        List<CsvReader.Row> rows = read("Company,Position,Status\nStripe,SWE Intern\n");

        assertThat(rows.get(0).get("status")).isEmpty();
    }

    @Test
    @DisplayName("blank lines between rows are ignored")
    void blankLines() {
        List<CsvReader.Row> rows = read("Company,Position\nStripe,SWE\n\n\nDatadog,DE\n");

        assertThat(rows).hasSize(2);
    }

    @Test
    @DisplayName("an empty file is reported clearly instead of producing no rows silently")
    void emptyFile() {
        assertThatThrownBy(() -> read("   "))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a header-only file produces no rows and no error")
    void headerOnly() {
        assertThat(read("Company,Position\n")).isEmpty();
    }

    @Test
    @DisplayName("values are quoted on output only when they need it")
    void escaping() {
        assertThat(CsvReader.escape("plain")).isEqualTo("plain");
        assertThat(CsvReader.escape("with,comma")).isEqualTo("\"with,comma\"");
        assertThat(CsvReader.escape("with \"quotes\"")).isEqualTo("\"with \"\"quotes\"\"\"");
        assertThat(CsvReader.escape("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(CsvReader.escape(null)).isEmpty();
    }

    @Test
    @DisplayName("a single line can be parsed on its own")
    void parseSingleLine() {
        assertThat(CsvReader.parseLine("a,\"b,c\",d")).containsExactly("a", "b,c", "d");
    }
}
