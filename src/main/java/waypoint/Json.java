package waypoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal, dependency-free JSON parser and serializer.
 *
 * <p>Waypoint has no runtime dependencies, so the CLI and the public adapter
 * API cannot use Jackson or Gson. This class implements the JSON needed to
 * read adapter inputs ({@code events.jsonl}, {@code ledger.jsonl},
 * {@code model.jsonl}, {@code meta.json}) and to render
 * {@code check --format json} output.</p>
 *
 * <p>Supported grammar is RFC 8259 JSON: objects, arrays, strings, numbers,
 * booleans, and {@code null}. Notable behaviour:</p>
 * <ul>
 *   <li>Object member order is preserved (insertion order) on parse and on
 *       serialization, so the JSON output is deterministic.</li>
 *   <li>Numbers keep their original lexeme; {@link Num#asBigDecimal()} never
 *       loses precision and {@link Num#asLong()} rejects overflow explicitly
 *       instead of silently truncating.</li>
 *   <li>Malformed input always throws {@link ParseException} (never a raw
 *       {@code NullPointerException}, {@code ArrayIndexOutOfBoundsException},
 *       {@code NumberFormatException}, or {@code StackOverflowError}); the
 *       exception carries 1-based line/column information and the character
 *       offset.</li>
 *   <li>Nesting depth is bounded ({@value #MAX_DEPTH}) so hostile input cannot
 *       blow the stack.</li>
 *   <li>Duplicate object keys follow last-writer-wins (the last value is kept,
 *       the original insertion position is preserved).</li>
 *   <li>A leading UTF-8 BOM is accepted and skipped.</li>
 * </ul>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Json.Obj o = Json.parseObject("{\"type\":\"STEP_STARTED\",\"f1\":\"root/charge\"}");
 * String type = o.string("type", null);          // "STEP_STARTED"
 * String f1   = o.string("f1", null);
 * String json = Json.stringify(o);               // compact, deterministic
 * }</pre>
 *
 * <p>This class is not thread-safe; instances are immutable after
 * construction (with the exception of the builder-style {@code put}/{@code add}
 * methods on freshly created objects/arrays).</p>
 */
public final class Json {

    private Json() {}

    /** Maximum supported nesting depth for arrays/objects. */
    public static final int MAX_DEPTH = 256;

    /** The six JSON value kinds. */
    public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    // ------------------------------------------------------------------
    // Exceptions
    // ------------------------------------------------------------------

    /** Base type for every JSON-related error thrown by this class. */
    public static class JsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public JsonException(String message) { super(message); }
        public JsonException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Malformed JSON input. Message is formatted as
     * {@code "line L, column C (offset O): <reason>"} so that a bad adapter
     * file can be fixed without guesswork.
     */
    public static final class ParseException extends JsonException {
        private static final long serialVersionUID = 1L;
        private final int offset;
        private final int line;
        private final int column;
        private final String reason;

        public ParseException(String reason, int offset, int line, int column) {
            super("line " + line + ", column " + column + " (offset " + offset + "): " + reason);
            this.reason = Objects.requireNonNull(reason, "reason");
            this.offset = offset;
            this.line = line;
            this.column = column;
        }

        /** Character offset (0-based) at which parsing failed. */
        public int offset() { return offset; }
        /** 1-based line number at which parsing failed. */
        public int line() { return line; }
        /** 1-based column number at which parsing failed. */
        public int column() { return column; }
        /** Failure reason without the position prefix. */
        public String reason() { return reason; }
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /** One immutable-after-construction JSON value. */
    public abstract static class Value {
        Value() {}

        /** Discriminator for this value. */
        public abstract Kind kind();

        public final boolean isObject() { return kind() == Kind.OBJECT; }
        public final boolean isArray() { return kind() == Kind.ARRAY; }
        public final boolean isString() { return kind() == Kind.STRING; }
        public final boolean isNumber() { return kind() == Kind.NUMBER; }
        public final boolean isBoolean() { return kind() == Kind.BOOLEAN; }
        public final boolean isNull() { return kind() == Kind.NULL; }

        /** This value as an object. @throws JsonException if a different kind */
        public final Obj asObject() { return expect(this, Obj.class, "OBJECT"); }
        /** This value as an array. @throws JsonException if a different kind */
        public final Arr asArray() { return expect(this, Arr.class, "ARRAY"); }
        /** This value as a string. @throws JsonException if a different kind */
        public final Str asStringValue() { return expect(this, Str.class, "STRING"); }
        /** This value as a number. @throws JsonException if a different kind */
        public final Num asNumber() { return expect(this, Num.class, "NUMBER"); }

        /** @throws JsonException if this is not a string */
        public final String asString() { return asStringValue().value; }
        /** @throws JsonException if this is not a number or out of {@code long} range */
        public long asLong() { return asNumber().asLong(); }
        /** @throws JsonException if this is not a number or out of {@code int} range */
        public int asInt() { return asNumber().asInt(); }
        /** @throws JsonException if this is not a number */
        public double asDouble() { return asNumber().asDouble(); }
        /** @throws JsonException if this is not a number */
        public BigDecimal asBigDecimal() { return asNumber().asBigDecimal(); }
        /** @throws JsonException if this is not a boolean */
        public final boolean asBoolean() { return expect(this, Bool.class, "BOOLEAN").value; }

        /** Compact serialization; equivalent to {@link Json#stringify(Value)}. */
        @Override public final String toString() { return stringify(this); }

        private static <T extends Value> T expect(Value v, Class<T> want, String name) {
            if (!want.isInstance(v)) {
                throw new JsonException("expected " + name + " but found " + v.kind());
            }
            return want.cast(v);
        }
    }

    /** JSON object; iteration order is insertion order. */
    public static final class Obj extends Value {
        private final LinkedHashMap<String, Value> fields = new LinkedHashMap<>();

        public Obj() {}
        /** Copy constructor (shallow: values are immutable). */
        public Obj(Obj other) { fields.putAll(other.fields); }

        @Override public Kind kind() { return Kind.OBJECT; }

        public int size() { return fields.size(); }
        public boolean isEmpty() { return fields.isEmpty(); }
        public boolean has(String key) { return fields.containsKey(key); }
        /** @return the value, or {@code null} if the member is absent */
        public Value get(String key) { return fields.get(key); }

        /** @return member value, or {@code null} if absent or JSON {@code null} */
        public Value getNonNull(String key) {
            Value v = fields.get(key);
            return v == null || v.isNull() ? null : v;
        }

        public Obj put(String key, Value value) {
            fields.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }
        public Obj put(String key, String value) {
            return put(key, value == null ? Null.INSTANCE : new Str(value));
        }
        public Obj put(String key, long value) { return put(key, new Num(Long.toString(value))); }
        public Obj put(String key, boolean value) { return put(key, Bool.of(value)); }
        public Obj putNull(String key) { return put(key, Null.INSTANCE); }

        /** @return unmodifiable view in insertion order */
        public Map<String, Value> fields() { return Collections.unmodifiableMap(fields); }
        public List<String> keys() { return List.copyOf(fields.keySet()); }

        // ---- typed convenience accessors (defaults for absent/null) ----

        public String string(String key, String defaultValue) {
            Value v = getNonNull(key);
            if (v == null) return defaultValue;
            return v.asString();
        }

        public long integer(String key, long defaultValue) {
            Value v = getNonNull(key);
            if (v == null) return defaultValue;
            return v.asLong();
        }

        public int integer(String key, int defaultValue) {
            long v = integer(key, (long) defaultValue);
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new JsonException("field \"" + key + "\" value " + v + " does not fit in int");
            }
            return (int) v;
        }

        public boolean bool(String key, boolean defaultValue) {
            Value v = getNonNull(key);
            if (v == null) return defaultValue;
            return v.asBoolean();
        }

        /** @return the array, or {@code null} if absent / JSON null */
        public Arr array(String key) {
            Value v = getNonNull(key);
            return v == null ? null : v.asArray();
        }

        /** @return the object, or {@code null} if absent / JSON null */
        public Obj object(String key) {
            Value v = getNonNull(key);
            return v == null ? null : v.asObject();
        }

        /** @return string list; empty if absent / null; error if not array of strings */
        public List<String> stringList(String key) {
            Arr a = array(key);
            if (a == null) return List.of();
            List<String> out = new ArrayList<>(a.size());
            for (int i = 0; i < a.size(); i++) {
                out.add(a.get(i).asString());
            }
            return Collections.unmodifiableList(out);
        }

        /** @return members as a {@code Map<String,Value>} (insertion order) */
        public Map<String, Value> asMap() { return fields(); }
    }

    /** JSON array. */
    public static final class Arr extends Value {
        private final List<Value> items = new ArrayList<>();

        public Arr() {}
        /** Copy constructor (shallow: values are immutable). */
        public Arr(Arr other) { items.addAll(other.items); }

        @Override public Kind kind() { return Kind.ARRAY; }
        public int size() { return items.size(); }
        public boolean isEmpty() { return items.isEmpty(); }
        public Value get(int index) { return items.get(index); }

        public Arr add(Value value) { items.add(Objects.requireNonNull(value, "value")); return this; }
        public Arr add(String value) { return add(value == null ? Null.INSTANCE : new Str(value)); }
        public Arr add(long value) { return add(new Num(Long.toString(value))); }
        public Arr add(boolean value) { return add(Bool.of(value)); }

        /** @return unmodifiable view */
        public List<Value> items() { return Collections.unmodifiableList(items); }
    }

    /** JSON string. */
    public static final class Str extends Value {
        public final String value;
        public Str(String value) { this.value = Objects.requireNonNull(value, "value"); }
        @Override public Kind kind() { return Kind.STRING; }
    }

    /** JSON number, stored as its original lexeme so no precision is lost. */
    public static final class Num extends Value {
        private final String raw;

        public Num(String raw) {
            this.raw = validateNumber(Objects.requireNonNull(raw, "raw"));
        }

        @Override public Kind kind() { return Kind.NUMBER; }

        /** Original JSON lexeme, e.g. {@code "1e-7"} or {@code "-0"}. */
        public String raw() { return raw; }

        public BigDecimal asBigDecimal() {
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number \"" + raw + "\"", e);
            }
        }

        public long asLong() {
            try {
                return new BigDecimal(raw).longValueExact();
            } catch (ArithmeticException e) {
                throw new JsonException("number \"" + raw + "\" is not a 64-bit integer");
            }
        }

        public int asInt() {
            long v = asLong();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new JsonException("number \"" + raw + "\" does not fit in int");
            }
            return (int) v;
        }

        public double asDouble() {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number \"" + raw + "\"", e);
            }
        }

        /** RFC 8259 number syntax check; also used by the parser. */
        static String validateNumber(String raw) {
            int i = 0, n = raw.length();
            if (n == 0) throw new JsonException("empty number");
            if (raw.charAt(i) == '-') i++;
            if (i >= n) throw new JsonException("invalid number \"" + raw + "\"");
            char c = raw.charAt(i);
            if (c == '0') {
                i++;
                if (i < n && raw.charAt(i) >= '0' && raw.charAt(i) <= '9') {
                    throw new JsonException("invalid number \"" + raw + "\": leading zero");
                }
            } else if (c >= '1' && c <= '9') {
                while (i < n && raw.charAt(i) >= '0' && raw.charAt(i) <= '9') i++;
            } else {
                throw new JsonException("invalid number \"" + raw + "\"");
            }
            if (i < n && raw.charAt(i) == '.') {
                i++;
                int start = i;
                while (i < n && raw.charAt(i) >= '0' && raw.charAt(i) <= '9') i++;
                if (i == start) throw new JsonException("invalid number \"" + raw + "\"");
            }
            if (i < n && (raw.charAt(i) == 'e' || raw.charAt(i) == 'E')) {
                i++;
                if (i < n && (raw.charAt(i) == '+' || raw.charAt(i) == '-')) i++;
                int start = i;
                while (i < n && raw.charAt(i) >= '0' && raw.charAt(i) <= '9') i++;
                if (i == start) throw new JsonException("invalid number \"" + raw + "\"");
            }
            if (i != n) throw new JsonException("invalid number \"" + raw + "\"");
            return raw;
        }
    }

    /** JSON boolean. */
    public static final class Bool extends Value {
        public static final Bool TRUE = new Bool(true);
        public static final Bool FALSE = new Bool(false);
        public final boolean value;
        private Bool(boolean value) { this.value = value; }
        public static Bool of(boolean b) { return b ? TRUE : FALSE; }
        @Override public Kind kind() { return Kind.BOOLEAN; }
    }

    /** JSON {@code null}. */
    public static final class Null extends Value {
        public static final Null INSTANCE = new Null();
        private Null() {}
        @Override public Kind kind() { return Kind.NULL; }
    }

    // ------------------------------------------------------------------
    // Constructors / factories
    // ------------------------------------------------------------------

    public static Value of(String value) { return value == null ? Null.INSTANCE : new Str(value); }
    public static Value of(long value) { return new Num(Long.toString(value)); }
    public static Value of(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new JsonException("JSON cannot represent " + value);
        }
        return new Num(Double.toString(value));
    }
    public static Value of(boolean value) { return Bool.of(value); }
    public static Value ofNullable(Value value) { return value == null ? Null.INSTANCE : value; }

    public static Obj object() { return new Obj(); }
    public static Arr array() { return new Arr(); }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Parse one complete JSON document held in {@code text}. */
    public static Value parse(String text) {
        Objects.requireNonNull(text, "text");
        return new Parser(text).parseDocument();
    }

    /** Parse one complete JSON document from a UTF-8 source. */
    public static Value parse(Reader reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) >= 0) sb.append(buf, 0, n);
        return parse(sb.toString());
    }

    /** Parse one complete JSON document from a UTF-8 stream (stream is not closed). */
    public static Value parse(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Parse one complete UTF-8 JSON document from a file. */
    public static Value parse(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(r);
        }
    }

    /** Parse and require a JSON object. */
    public static Obj parseObject(String text) { return parse(text).asObject(); }

    /** Parse and require a JSON array. */
    public static Arr parseArray(String text) { return parse(text).asArray(); }

    private static final class Parser {
        private final String s;
        private final int n;
        private int pos;
        private int depth;

        Parser(String s) { this.s = s; this.n = s.length(); }

        Value parseDocument() {
            skipBom();
            skipWhitespace();
            if (pos >= n) throw error("empty input: expected a JSON value");
            Value v = parseValue();
            skipWhitespace();
            if (pos < n) throw error("unexpected trailing content '" + s.charAt(pos) + "'");
            return v;
        }

        private void skipBom() {
            if (pos < n && s.charAt(pos) == '\uFEFF') pos++;
        }

        private Value parseValue() {
            if (pos >= n) throw error("unexpected end of input: expected a JSON value");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new Str(parseString());
                case 't' -> { expectLiteral("true"); yield Bool.TRUE; }
                case 'f' -> { expectLiteral("false"); yield Bool.FALSE; }
                case 'n' -> { expectLiteral("null"); yield Null.INSTANCE; }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    throw error("unexpected character '" + printable(c) + "': expected a JSON value");
                }
            };
        }

        private Obj parseObject() {
            enter();
            pos++; // {
            Obj obj = new Obj();
            skipWhitespace();
            if (pos < n && s.charAt(pos) == '}') { pos++; depth--; return obj; }
            while (true) {
                skipWhitespace();
                if (pos >= n) throw error("unterminated object: expected '\"'");
                if (s.charAt(pos) != '"') {
                    throw error("object keys must be strings, found '" + printable(s.charAt(pos)) + "'");
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= n || s.charAt(pos) != ':') throw error("expected ':' after object key \"" + key + "\"");
                pos++;
                skipWhitespace();
                Value value = parseValue();
                obj.put(key, value);
                skipWhitespace();
                if (pos >= n) throw error("unterminated object: expected ',' or '}'");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; depth--; return obj; }
                throw error("expected ',' or '}' but found '" + printable(c) + "'");
            }
        }

        private Arr parseArray() {
            enter();
            pos++; // [
            Arr arr = new Arr();
            skipWhitespace();
            if (pos < n && s.charAt(pos) == ']') { pos++; depth--; return arr; }
            while (true) {
                skipWhitespace();
                arr.add(parseValue());
                skipWhitespace();
                if (pos >= n) throw error("unterminated array: expected ',' or ']'");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; depth--; return arr; }
                throw error("expected ',' or ']' but found '" + printable(c) + "'");
            }
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw error("nesting too deep (max " + MAX_DEPTH + " levels)");
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= n) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= n) throw error("unterminated escape sequence");
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
                        case 'u' -> sb.append(parseHex4());
                        default -> throw error("invalid escape sequence '\\" + printable(e) + "'");
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character U+" + String.format("%04X", (int) c)
                            + " in string (must be escaped)");
                } else {
                    sb.append(c);
                }
            }
        }

        private char parseHex4() {
            if (pos + 4 > n) throw error("truncated unicode escape: expected 4 hex digits");
            int v = 0;
            for (int k = 0; k < 4; k++) {
                char c = s.charAt(pos + k);
                int d = Character.digit(c, 16);
                if (d < 0) throw error("invalid hex digit '" + printable(c) + "' in unicode escape");
                v = (v << 4) | d;
            }
            pos += 4;
            return (char) v;
        }

        private Num parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            if (pos >= n) throw error("invalid number: expected a digit after '-'");
            char c = s.charAt(pos);
            if (c == '0') {
                pos++;
                if (pos < n && isDigit(s.charAt(pos))) throw error("invalid number: leading zero");
            } else if (c >= '1' && c <= '9') {
                while (pos < n && isDigit(s.charAt(pos))) pos++;
            } else {
                throw error("invalid number: expected a digit, found '" + printable(c) + "'");
            }
            if (pos < n && s.charAt(pos) == '.') {
                pos++;
                int frac = pos;
                while (pos < n && isDigit(s.charAt(pos))) pos++;
                if (pos == frac) throw error("invalid number: expected a digit after '.'");
            }
            if (pos < n && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < n && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                int exp = pos;
                while (pos < n && isDigit(s.charAt(pos))) pos++;
                if (pos == exp) throw error("invalid number: expected a digit in exponent");
            }
            return new Num(s.substring(start, pos));
        }

        private void expectLiteral(String literal) {
            if (pos + literal.length() > n || !s.startsWith(literal, pos)) {
                throw error("invalid literal: expected '" + literal + "'");
            }
            pos += literal.length();
        }

        private void skipWhitespace() {
            while (pos < n) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private ParseException error(String reason) {
            int line = 1;
            int lineStart = 0;
            for (int i = 0; i < pos && i < n; i++) {
                if (s.charAt(i) == '\n') { line++; lineStart = i + 1; }
            }
            int column = pos - lineStart + 1;
            return new ParseException(reason, pos, line, column);
        }

        private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

        private static String printable(char c) {
            if (c < 0x20 || c == 0x7F) return "U+" + String.format("%04X", (int) c);
            return String.valueOf(c);
        }
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    /** Serialize compactly (no whitespace). */
    public static String stringify(Value value) {
        Objects.requireNonNull(value, "value");
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb, -1, 0);
        return sb.toString();
    }

    /** Serialize with two-space indentation and a trailing newline. */
    public static String pretty(Value value) {
        Objects.requireNonNull(value, "value");
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb, 2, 0);
        sb.append('\n');
        return sb.toString();
    }

    /** Write compact JSON to {@code out}. */
    public static void write(Value value, Appendable out) throws IOException {
        Objects.requireNonNull(out, "out");
        out.append(stringify(value));
    }

    private static void writeValue(Value v, StringBuilder sb, int indent, int level) {
        switch (v.kind()) {
            case NULL -> sb.append("null");
            case BOOLEAN -> sb.append(v.asBoolean() ? "true" : "false");
            case NUMBER -> sb.append(v.asNumber().raw());
            case STRING -> writeString(v.asString(), sb);
            case ARRAY -> {
                Arr a = v.asArray();
                if (a.isEmpty()) { sb.append("[]"); return; }
                sb.append('[');
                for (int i = 0; i < a.size(); i++) {
                    if (i > 0) sb.append(',');
                    newline(sb, indent, level + 1);
                    writeValue(a.get(i), sb, indent, level + 1);
                }
                newline(sb, indent, level);
                sb.append(']');
            }
            case OBJECT -> {
                Obj o = v.asObject();
                if (o.isEmpty()) { sb.append("{}"); return; }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Value> e : o.fields().entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    newline(sb, indent, level + 1);
                    writeString(e.getKey(), sb);
                    sb.append(indent < 0 ? ":" : ": ");
                    writeValue(e.getValue(), sb, indent, level + 1);
                }
                newline(sb, indent, level);
                sb.append('}');
            }
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) sb.append(' ');
    }

    /**
     * Write a JSON string literal, escaping every character that must be
     * escaped plus U+2028/U+2029 and unpaired surrogates (so the output is
     * always valid UTF-8 and safe to embed in JavaScript).
     */
    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        appendUnicodeEscape(sb, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                            sb.append(c).append(value.charAt(++i));
                        } else {
                            appendUnicodeEscape(sb, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        appendUnicodeEscape(sb, c); // unpaired low surrogate
                    } else if (c == '\u2028' || c == '\u2029') {
                        appendUnicodeEscape(sb, c);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder sb, char c) {
        sb.append("\\u");
        String hex = Integer.toHexString(c);
        for (int k = hex.length(); k < 4; k++) sb.append('0');
        sb.append(hex);
    }
}
