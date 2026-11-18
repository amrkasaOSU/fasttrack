package com.fasttrack.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small RFC 4180 reader: quoted fields, embedded commas, embedded newlines
 * and doubled quotes.
 *
 * <p>Written by hand rather than pulling in a CSV library: the format needed
 * here is tiny, and a hand-rolled reader keeps the dependency list short and
 * the failure modes obvious. It is covered by its own unit tests.
 */
public final class CsvReader {

    private CsvReader() {
    }

    /** One parsed row: header name (lowercased, trimmed) to value. */
    public record Row(int lineNumber, Map<String, String> values) {

        public String get(String column) {
            String value = values.get(column.toLowerCase());
            return value == null ? "" : value.trim();
        }

        public boolean isEmpty() {
            return values.values().stream().allMatch(v -> v == null || v.isBlank());
        }
    }

    public static List<String> parseLine(String line) {
        return parseFields(line);
    }

    /**
     * Reads the whole stream. The first non-empty line is the header.
     *
     * @throws CsvFormatException when the file has no header or is unreadable
     */
    public static List<Row> read(InputStream input) {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String raw = reader.lines().reduce((a, b) -> a + "\n" + b).orElse("");
            if (raw.isBlank()) {
                throw new CsvFormatException("The file is empty.");
            }
            // Strip a UTF-8 BOM, which Excel writes.
            if (raw.charAt(0) == '﻿') {
                raw = raw.substring(1);
            }

            List<List<String>> records = parseRecords(raw);
            if (records.isEmpty()) {
                throw new CsvFormatException("The file has no rows.");
            }

            List<String> header = records.get(0).stream()
                    .map(h -> h == null ? "" : h.trim().toLowerCase())
                    .toList();

            for (int i = 1; i < records.size(); i++) {
                List<String> record = records.get(i);
                Map<String, String> values = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    values.put(header.get(c), c < record.size() ? record.get(c) : "");
                }
                // +1 because the header is line 1 and lines are 1-based.
                rows.add(new Row(i + 1, values));
            }
        } catch (IOException e) {
            throw new CsvFormatException("The file could not be read: " + e.getMessage());
        }
        return rows;
    }

    private static List<List<String>> parseRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        List<String> current = new ArrayList<>();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> { /* ignored; \n ends the record */ }
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    if (!(current.size() == 1 && current.get(0).isBlank())) {
                        records.add(current);
                    }
                    current = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }
        current.add(field.toString());
        if (!(current.size() == 1 && current.get(0).isBlank())) {
            records.add(current);
        }
        return records;
    }

    private static List<String> parseFields(String line) {
        List<List<String>> records = parseRecords(line);
        return records.isEmpty() ? List.of() : records.get(0);
    }

    /** Quotes a value for output only when it needs it. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }
}
