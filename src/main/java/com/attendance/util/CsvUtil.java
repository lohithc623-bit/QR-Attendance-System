package com.attendance.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Minimal RFC-4180 CSV writer (quotes fields, doubles embedded quotes, neutralises spreadsheet formulas). */
public final class CsvUtil {

    private CsvUtil() {
    }

    public static String toCsv(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, header);
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        return sb.toString();
    }

    public static void write(Path file, List<String> header, List<List<String>> rows) throws IOException {
        // UTF-8 BOM so Excel opens non-ASCII names correctly.
        Files.write(file, ("\uFEFF" + toCsv(header, rows)).getBytes(StandardCharsets.UTF_8));
    }

    private static void appendRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(String value) {
        String v = value == null ? "" : value;
        // Stop "=cmd|..." style formula injection when the CSV is opened in Excel.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?%?")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
