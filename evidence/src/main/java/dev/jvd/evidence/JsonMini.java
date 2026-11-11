package dev.jvd.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser/writer helpers (Assumption 4: JDK-only dependencies, so
 * no Jackson/Gson). The parser is a small recursive-descent implementation
 * producing {@code Map<String,Object> / List<Object> / String / Long / Double /
 * Boolean / null}. It only needs to round-trip this project's own flat-ish
 * record schema and to fail loudly on malformed input (tamper evidence).
 */
final class JsonMini {

    private final String s;
    private int pos;

    private JsonMini(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        JsonMini p = new JsonMini(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.s.length()) throw new IllegalArgumentException("trailing JSON content at " + p.pos);
        return v;
    }

    private void ws() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private Object value() {
        if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> { expect("true"); yield Boolean.TRUE; }
            case 'f' -> { expect("false"); yield Boolean.FALSE; }
            case 'n' -> { expect("null"); yield null; }
            default -> number();
        };
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("expected " + lit + " at " + pos);
        pos += lit.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':' at " + pos);
            pos++;
            ws();
            m.put(k, value());
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
            char c = s.charAt(pos++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("expected ',' at " + (pos - 1));
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        pos++; // [
        ws();
        if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
            char c = s.charAt(pos++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("expected ',' at " + (pos - 1));
        }
    }

    private String string() {
        if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string at " + pos);
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= s.length()) break;
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private Number number() {
        int start = pos;
        while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
        String n = s.substring(start, pos);
        if (n.isEmpty()) throw new IllegalArgumentException("bad JSON value at " + start);
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("bad number " + n, ex);
        }
    }

    /** JSON string escaping used by the writer side. */
    static String esc(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 2);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
