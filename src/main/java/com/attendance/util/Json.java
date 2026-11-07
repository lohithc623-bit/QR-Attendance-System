package com.attendance.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer so the API server needs no third-party library. */
public final class Json {

    private Json() {
    }

    // ---------------- writer ----------------
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------- reader ----------------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = new Reader(text).readDocument();
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s == null ? "" : s;
        }

        Object readDocument() {
            Object v = readValue();
            skipWs();
            if (i != s.length()) throw fail("Unexpected trailing characters");
            return v;
        }

        private Object readValue() {
            skipWs();
            if (i >= s.length()) throw fail("Unexpected end of input");
            char c = s.charAt(i);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (peek() == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                map.put(key, readValue());
                skipWs();
                if (peek() == ',') { i++; continue; }
                expect('}');
                return map;
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (peek() == ']') { i++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                if (peek() == ',') { i++; continue; }
                expect(']');
                return list;
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw fail("Bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw fail("Unterminated string");
        }

        private Object readNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw fail("Unexpected character '" + s.charAt(i) + "'");
            String num = s.substring(start, i);
            try {
                return (num.contains(".") || num.contains("e") || num.contains("E"))
                        ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
            } catch (NumberFormatException e) {
                throw fail("Bad number " + num);
            }
        }

        private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

        private void expect(char c) {
            if (peek() != c) throw fail("Expected '" + c + "'");
            i++;
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private IllegalArgumentException fail(String msg) {
            return new IllegalArgumentException("Invalid JSON at position " + i + ": " + msg);
        }
    }
}
