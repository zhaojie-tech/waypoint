package waypoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the zero-dependency JSON implementation used by the CLI
 *  and the public adapter API. */
class JsonTest {

    // ---------------------------------------------------------------- helpers

    private static Json.ParseException parseError(String text) {
        try {
            Json.parse(text);
        } catch (Json.ParseException e) {
            return e;
        } catch (RuntimeException e) {
            fail("malformed JSON threw a raw " + e.getClass().getName() + " instead of ParseException: " + e);
        }
        fail("expected ParseException for: " + text);
        return null; // unreachable
    }

    private static void assertParseError(String text) {
        Json.ParseException e = parseError(text);
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("line "), "message should carry a line number: " + e.getMessage());
        assertTrue(e.getMessage().contains("column "), "message should carry a column: " + e.getMessage());
        assertTrue(e.offset() >= 0);
        assertTrue(e.line() >= 1);
        assertTrue(e.column() >= 1);
    }

    // ---------------------------------------------------------------- valid

    @Test
    void parsesScalars() {
        assertEquals(Json.Kind.NULL, Json.parse("null").kind());
        assertEquals(Json.Kind.BOOLEAN, Json.parse("true").kind());
        assertTrue(Json.parse("true").asBoolean());
        assertFalse(Json.parse("false").asBoolean());
        assertEquals(Json.Kind.STRING, Json.parse("\"hi\"").kind());
        assertEquals("hi", Json.parse("\"hi\"").asString());
        assertEquals(0L, Json.parse("0").asLong());
        assertEquals(-17L, Json.parse("-17").asLong());
        assertEquals(42L, Json.parse("42").asLong());
        assertEquals(2.5, Json.parse("2.5").asDouble(), 0.0);
        assertEquals(-300.0, Json.parse("-3e2").asDouble(), 0.0);
        assertEquals(0.0001, Json.parse("1E-4").asDouble(), 0.0);
    }

    @Test
    void parsesObjectAndPreservesKeyOrder() {
        Json.Obj o = Json.parseObject("{\"type\":\"STEP_STARTED\",\"f1\":\"root/charge\",\"f2\":null}");
        assertEquals(List.of("type", "f1", "f2"), o.keys());
        assertEquals("STEP_STARTED", o.string("type", null));
        assertEquals("root/charge", o.string("f1", null));
        assertTrue(o.has("f2"));
        assertNull(o.getNonNull("f2"));
        assertNull(o.string("f2", null));
        assertEquals("fallback", o.string("missing", "fallback"));
    }

    @Test
    void parsesNestedStructures() {
        Json.Value v = Json.parse("""
                {"a":[1,[2,[3,{"b":true}]],{"c":null}],"emptyObj":{},"emptyArr":[]}""");
        Json.Obj root = v.asObject();
        Json.Arr a = root.array("a");
        assertEquals(3, a.size());
        assertEquals(3, a.get(1).asArray().get(1).asArray().get(0).asInt());
        assertTrue(a.get(1).asArray().get(1).asArray().get(1).asObject().bool("b", false));
        assertEquals(0, root.object("emptyObj").size());
        assertEquals(0, root.array("emptyArr").size());
    }

    @Test
    void decodesAllEscapes() {
        Json.Str s = Json.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u0041 \\u00e9 \\u4e2d\"").asStringValue();
        assertEquals("\" \\ / \b \f \n \r \t A é 中", s.value);
    }

    @Test
    void decodesSurrogatePairEscape() {
        String parsed = Json.parse("\"\\ud83d\\ude00\"").asString();
        assertEquals("\uD83D\uDE00", parsed);
        assertEquals("😀", parsed);
    }

    @Test
    void acceptsWhitespaceAndLeadingBom() {
        assertEquals(1, Json.parse(" \t\r\n 1 \n").asInt());
        assertEquals(1, Json.parse("\uFEFF{\"a\":1}").asObject().integer("a", 0));
    }

    @Test
    void acceptsEmptyContainers() {
        assertEquals(0, Json.parseObject("{}").size());
        assertEquals(0, Json.parseArray("[]").size());
    }

    @Test
    void duplicateKeysLastWins() {
        Json.Obj o = Json.parseObject("{\"a\":1,\"a\":2}");
        assertEquals(1, o.size());
        assertEquals(2, o.integer("a", 0));
    }

    // -------------------------------------------------------------- numeric

    @Test
    void largeNumbersPreservePrecision() {
        String big = "1234567890123456789012345678901234567890";
        Json.Num n = Json.parse(big).asNumber();
        assertEquals(big, n.raw());
        assertEquals(new BigDecimal(big), n.asBigDecimal());
        assertEquals(new BigDecimal(big).add(BigDecimal.ONE), n.asBigDecimal().add(BigDecimal.ONE));
        // exponent forms stay exact as BigDecimal but may exceed double precision
        assertEquals(new BigDecimal("1E+400"), Json.parse("1e400").asBigDecimal());
        // long boundaries
        assertEquals(Long.MAX_VALUE, Json.parse(String.valueOf(Long.MAX_VALUE)).asLong());
        assertEquals(Long.MIN_VALUE, Json.parse(String.valueOf(Long.MIN_VALUE)).asLong());
    }

    @Test
    void numberAccessorsRejectOverflowAndFractions() {
        assertEquals(3, Json.parse("3.0").asInt());
        assertThrows(Json.JsonException.class, () -> Json.parse("3.5").asInt());
        assertThrows(Json.JsonException.class, () -> Json.parse("1e100").asLong());
        assertThrows(Json.JsonException.class, () -> Json.parse("2147483648").asInt());
        assertEquals(Integer.MAX_VALUE, Json.parse("2147483647").asInt());
    }

    @Test
    void numberSyntaxIsValidated() {
        for (String bad : new String[]{"01", "-01", "1.", ".5", "+1", "1e", "1e+", "1e-", "-", "--1",
                "0x10", "1_000", "Infinity", "NaN", "1..2"}) {
            assertParseError(bad);
        }
    }

    // ------------------------------------------------------------- malformed

    @Test
    void rejectsMalformedDocuments() {
        String[] bad = {
                "", "   ", "\n\t", "{", "}", "[", "]", "{\"a\"}", "{\"a\":}", "{\"a\":1,}",
                "{a:1}", "[1,]", "[1 2]", "[,1]", "{\"a\":1 \"b\":2}", "{\"a\" 1}", "1 2",
                "tru", "nul", "False", "\"abc", "\"a\\q\"", "\"a\nb\"", "\"\\u12\"", "\"\\uZZZZ\"",
                "\"\\\"", "-", "1.", "01", "[\"a\",]", "{\"a\":tru}", "/*comment*/1",
        };
        for (String text : bad) {
            assertParseError(text);
        }
    }

    @Test
    void malformedInputNeverThrowsRawRuntimeException() {
        String[] hostile = {
                "", "{", "[", "\"", "\\", "{\"a\":[1,{\"b\":", "[[[[[[[[[[", "]]]]]]",
                "\u0000", "{\"a\"", "\"\\u", "1e", "-.", "--", "{,}", "[,]", "nullx",
                "{\"a\":1}}", "[]]", "\"\\ud800", "\uD800", "{\"a\":\"\u0000\"}",
        };
        for (String text : hostile) {
            try {
                Json.parse(text);
            } catch (Json.ParseException expected) {
                // ok: the only acceptable failure mode for malformed input
            } catch (RuntimeException raw) {
                fail("raw " + raw.getClass().getName() + " for input " + text + ": " + raw.getMessage());
            }
        }
    }

    @Test
    void rejectsTrailingContent() {
        assertParseError("null x");
        assertParseError("{} {}");
        assertParseError("[1]]");
    }

    @Test
    void rejectsExcessiveNestingWithParseException() {
        StringBuilder deep = new StringBuilder();
        deep.append("[".repeat(Json.MAX_DEPTH + 5)).append("]".repeat(Json.MAX_DEPTH + 5));
        Json.ParseException e = parseError(deep.toString());
        assertTrue(e.reason().contains("nesting too deep"), e.reason());
    }

    @Test
    void errorPositionsPointAtTheProblem() {
        Json.ParseException e = parseError("{\n  \"a\": 1,\n  \"b\": oops\n}");
        assertEquals(3, e.line());
        assertTrue(e.column() >= 7, "column " + e.column());
        assertTrue(e.getMessage().contains("line 3"));
        assertTrue(e.getMessage().contains("column"));
    }

    // --------------------------------------------------------- serialization

    @Test
    void stringifyEscapesStrings() {
        Json.Obj o = Json.object()
                .put("quote", "a\"b")
                .put("backslash", "a\\b")
                .put("controls", "\b\f\n\r\t")
                .put("unicode", "é中😀")
                .put("lineSep", "\u2028\u2029");
        String json = Json.stringify(o);
        assertEquals("{\"quote\":\"a\\\"b\",\"backslash\":\"a\\\\b\","
                + "\"controls\":\"\\b\\f\\n\\r\\t\",\"unicode\":\"é中😀\","
                + "\"lineSep\":\"\\u2028\\u2029\"}", json);
        // round-trips
        assertEquals(o.string("quote", null), Json.parse(json).asObject().string("quote", null));
        assertEquals(o.string("controls", null), Json.parse(json).asObject().string("controls", null));
    }

    @Test
    void stringifyEscapesLoneSurrogatesAndKeepsValidPairs() {
        String lone = "x" + '\uD800' + "y";
        String json = Json.stringify(Json.of(lone));
        assertEquals("\"x\\ud800y\"", json);
        assertEquals(lone, Json.parse(json).asString());
        assertEquals("\"😀\"", Json.stringify(Json.of("😀")));
    }

    @Test
    void stringifyPreservesInsertionOrderAndIsDeterministic() {
        Json.Obj o = Json.object().put("z", 1).put("a", 2).put("m", 3);
        assertEquals("{\"z\":1,\"a\":2,\"m\":3}", Json.stringify(o));
        assertEquals(Json.stringify(o), Json.stringify(o));
    }

    @Test
    void prettyOutputIsIndented() {
        Json.Obj o = Json.object().put("a", Json.array().add(1).add(2)).put("b", Json.object().put("c", true));
        assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {\n    \"c\": true\n  }\n}\n",
                Json.pretty(o));
    }

    @Test
    void serializationRoundTripsComplexDocument() {
        String source = "{\"a\":[1,2.5,-3e2,true,false,null,\"x\\n\\u00e9\"],"
                + "\"b\":{\"c\":123456789012345678901234567890,\"d\":[]}}";
        Json.Value v = Json.parse(source);
        Json.Value again = Json.parse(Json.stringify(v));
        assertEquals(Json.stringify(v), Json.stringify(again));
        assertEquals("{\"a\":[1,2.5,-3e2,true,false,null,\"x\\né\"],"
                + "\"b\":{\"c\":123456789012345678901234567890,\"d\":[]}}", Json.stringify(again));
    }

    // ------------------------------------------------------------ UTF-8 I/O

    @Test
    void parsesUtf8FromStreamAndReader() throws IOException {
        String json = "{\"message\":\"恢复前缀 · 😀\"}";
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        assertEquals("恢复前缀 · 😀",
                Json.parse(new ByteArrayInputStream(utf8)).asObject().string("message", null));
        assertEquals("恢复前缀 · 😀",
                Json.parse(new StringReader(json)).asObject().string("message", null));
    }

    @Test
    void parsesUtf8FromPath(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("meta.json");
        String json = "{\"scenario\":\"示例/clean\",\"count\":3}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
        Json.Obj o = Json.parse(file).asObject();
        assertEquals("示例/clean", o.string("scenario", null));
        assertEquals(3, o.integer("count", 0));
    }

    @Test
    void serializedUtf8IsValidWhenWrittenToDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.json");
        String json = Json.stringify(Json.object().put("text", "中文😀\u2028"));
        Files.writeString(file, json, StandardCharsets.UTF_8);
        assertEquals("中文😀\u2028", Json.parse(file).asObject().string("text", null));
    }

    // ------------------------------------------------------ typed accessors

    @Test
    void typedAccessorsFailWithClearErrors() {
        Json.Value number = Json.parse("1");
        Json.JsonException e = assertThrows(Json.JsonException.class, number::asString);
        assertTrue(e.getMessage().contains("expected STRING"));
        assertThrows(Json.JsonException.class, () -> Json.parse("\"s\"").asObject());
        assertThrows(Json.JsonException.class, () -> Json.parse("{}").asArray());
        assertThrows(Json.JsonException.class, () -> Json.parse("null").asBoolean());
        assertThrows(Json.JsonException.class, () -> Json.object().put("n", "x").integer("n", 0));
    }

    @Test
    void missingOrNullFieldsUseDefaults() {
        Json.Obj o = Json.parseObject("{\"nullField\":null}");
        assertNull(o.object("nullField"));
        assertNull(o.array("nullField"));
        assertNull(o.getNonNull("nullField"));
        assertEquals("d", o.string("absent", "d"));
        assertEquals(7, o.integer("absent", 7));
        assertEquals(-1, o.integer("nullField", -1));
        assertFalse(o.bool("absent", false));
        assertTrue(o.stringList("absent").isEmpty());
    }

    @Test
    void stringListAccessorWorks() {
        Json.Obj o = Json.parseObject("{\"steps\":[\"root/charge\",\"root/report\"]}");
        assertEquals(List.of("root/charge", "root/report"), o.stringList("steps"));
        Json.Obj bad = Json.parseObject("{\"steps\":[1]}");
        assertThrows(Json.JsonException.class, () -> bad.stringList("steps"));
    }

    @Test
    void factoriesProduceExpectedKinds() {
        assertEquals(Json.Kind.NULL, Json.ofNullable(null).kind());
        assertEquals(Json.Kind.NULL, Json.of((String) null).kind());
        assertEquals(Json.Kind.STRING, Json.of("x").kind());
        assertEquals("{\"a\":[true,2]}", Json.stringify(
                Json.object().put("a", Json.array().add(true).add(2))));
        assertThrows(Json.JsonException.class, () -> Json.of(Double.NaN));
        assertThrows(Json.JsonException.class, () -> Json.of(Double.POSITIVE_INFINITY));
    }
}
