package waypoint.api;

import waypoint.Checker;
import waypoint.api.Waypoint.FormatException;
import waypoint.Json;
import waypoint.Model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the adapter file formats described in {@code docs/adapter-guide.md}
 * into a {@link Checker.RunResult}.
 *
 * <p>Formats (all UTF-8, zero-dependency JSON):</p>
 * <ul>
 *   <li>{@code events.jsonl}: one event object per line, or a JSON array of
 *       event objects. Required keys: {@code type}; optional {@code f1..f4}.</li>
 *   <li>{@code ledger.jsonl}: one apply record per line, or a JSON array.
 *       Required: {@code key}, {@code startedRecordIndex},
 *       {@code syncedFrontierAtApply}; optional {@code executor}.</li>
 *   <li>{@code model.jsonl}: one model-call record per line, or a JSON array.
 *       Required: {@code op}, {@code subject}.</li>
 *   <li>{@code meta.json}: a single JSON object with RunResult scalars such as
 *       {@code expectedSteps}, {@code preCompletedKeys},
 *       {@code preAppliedCounts}, {@code unobservable}.</li>
 * </ul>
 *
 * <p>Unknown fields are rejected (rather than silently ignored) so an adapter
 * cannot believe it supplied an observation the checker never read.</p>
 */
final class InputFiles {

    private InputFiles() {}

    // ---------------------------------------------------------------- entry

    static Checker.RunResult load(InputStream events, InputStream ledger, InputStream model, InputStream meta)
            throws IOException {
        return load(events, "events", ledger, "ledger", model, "model", meta, "meta");
    }

    static Checker.RunResult load(InputStream events, String eventsName,
                                  InputStream ledger, String ledgerName,
                                  InputStream model, String modelName,
                                  InputStream meta, String metaName) throws IOException {
        Checker.RunResult rr = new Checker.RunResult();
        rr.events = parseEvents(eventsName, readAll(events));
        rr.ext = parseLedger(ledgerName, ledger == null ? "" : readAll(ledger));
        rr.model = new Model.ModelSpy();
        if (model != null) applyModel(rr.model, modelName, readAll(model));
        if (meta != null) applyMeta(rr, metaName, readAll(meta));
        return rr;
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // ---------------------------------------------------------------- events

    static List<Model.Event> parseEvents(String source, String text) {
        List<Json.Obj> records = records(source, text);
        List<Model.Event> out = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            out.add(parseEvent(records.get(i), source + " record " + i));
        }
        return Collections.unmodifiableList(out);
    }

    private static Model.Event parseEvent(Json.Obj o, String where) {
        rejectUnknown(o, Set.of("type", "f1", "f2", "f3", "f4"), where);
        String type = requiredString(o, "type", where);
        Model.Type t;
        try {
            t = Model.Type.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new FormatException(where + ": unknown event type \"" + type
                    + "\" (expected one of " + Arrays.toString(Model.Type.values()) + ")");
        }
        return new Model.Event(t,
                optionalString(o, "f1", where),
                optionalString(o, "f2", where),
                optionalString(o, "f3", where),
                optionalString(o, "f4", where));
    }

    static List<Model.Event> parseEventArray(String source, Json.Arr arr) {
        List<Model.Event> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Json.Value v = arr.get(i);
            if (!v.isObject()) throw new FormatException(source + "[" + i + "]: expected a JSON object");
            out.add(parseEvent(v.asObject(), source + "[" + i + "]"));
        }
        return Collections.unmodifiableList(out);
    }

    // ---------------------------------------------------------------- ledger

    static Model.ExternalLedger parseLedger(String source, String text) {
        Model.ExternalLedger ledger = new Model.ExternalLedger();
        List<Json.Obj> records = records(source, text);
        for (int i = 0; i < records.size(); i++) {
            Json.Obj o = records.get(i);
            String where = source + " record " + i;
            rejectUnknown(o, Set.of("key", "startedRecordIndex", "syncedFrontierAtApply", "executor"), where);
            String key = requiredString(o, "key", where);
            if (key.isEmpty()) throw new FormatException(where + ": \"key\" must not be empty");
            long started = requiredLong(o, "startedRecordIndex", where);
            long frontier = requiredLong(o, "syncedFrontierAtApply", where);
            String executor = optionalString(o, "executor", where);
            if (executor == null) executor = "runner";
            if (executor.isEmpty()) throw new FormatException(where + ": \"executor\" must not be empty");
            if (started < -1) {
                throw new FormatException(where + ": \"startedRecordIndex\" must be >= -1 ("
                        + started + "); -1 means no durable STEP_STARTED was observed");
            }
            if (frontier < 0) {
                throw new FormatException(where + ": \"syncedFrontierAtApply\" must be >= 0 (" + frontier + ")");
            }
            ledger.apply(key, started, frontier, executor);
        }
        return ledger;
    }

    // ----------------------------------------------------------------- model

    static void applyModel(Model.ModelSpy spy, String source, String text) {
        List<Json.Obj> records = records(source, text);
        for (int i = 0; i < records.size(); i++) {
            Json.Obj o = records.get(i);
            String where = source + " record " + i;
            rejectUnknown(o, Set.of("op", "subject"), where);
            String op = requiredString(o, "op", where);
            String subject = requiredString(o, "subject", where);
            if (op.isEmpty() || subject.isEmpty()) {
                throw new FormatException(where + ": \"op\" and \"subject\" must not be empty");
            }
            spy.call(op, subject);
        }
    }

    // ------------------------------------------------------------------ meta

    static final Set<String> META_KEYS = Set.of(
            // RunResult fields with their frozen names
            "scenario", "expectedSteps", "childEvents0", "childEvents1", "protocolObservable",
            "replayHashes", "orderTraces", "configuredCancelAt", "replayChunksEmitted",
            "meteringPreEntries", "meteringFinalEntries", "interruptionKinds", "unobservable",
            "preCompletedKeys", "preAppliedCounts", "twoResumers", "preCompactionDurable",
            "preSummarizeCalls", "forkScenario",
            // fields kept from the original crate-level RunResult
            "cancelAt", "childTrace0", "childTrace1", "stateHash");

    static void applyMeta(Checker.RunResult rr, String source, String text) {
        String t = stripBom(text).strip();
        if (t.isEmpty()) return;
        Json.Obj meta;
        try {
            meta = Json.parse(t).asObject();
        } catch (Json.ParseException e) {
            throw new FormatException(source + ": " + e.getMessage());
        } catch (Json.JsonException e) {
            throw new FormatException(source + ": " + e.getMessage());
        }
        rejectUnknown(meta, META_KEYS, source);

        if (meta.has("scenario")) rr.scenario = requiredString(meta, "scenario", source);
        rr.expectedSteps = stringList(meta, "expectedSteps", source);
        if (meta.has("childEvents0")) rr.childEvents0 = eventArray(meta, "childEvents0", source);
        if (meta.has("childEvents1")) rr.childEvents1 = eventArray(meta, "childEvents1", source);
        if (meta.has("protocolObservable")) rr.protocolObservable = requiredBool(meta, "protocolObservable", source);
        rr.replayHashes = stringList(meta, "replayHashes", source);
        rr.orderTraces = stringList(meta, "orderTraces", source);
        rr.configuredCancelAt = intField(meta, "configuredCancelAt", rr.configuredCancelAt, source);
        rr.replayChunksEmitted = intField(meta, "replayChunksEmitted", rr.replayChunksEmitted, source);
        rr.meteringPreEntries = intField(meta, "meteringPreEntries", rr.meteringPreEntries, source);
        rr.meteringFinalEntries = intField(meta, "meteringFinalEntries", rr.meteringFinalEntries, source);
        rr.interruptionKinds = stringSet(meta, "interruptionKinds", source);
        rr.unobservable = stringSet(meta, "unobservable", source);
        rr.preCompletedKeys = stringSet(meta, "preCompletedKeys", source);
        if (meta.has("preAppliedCounts")) rr.preAppliedCounts = countMap(meta, "preAppliedCounts", source);
        if (meta.has("twoResumers")) rr.twoResumers = requiredBool(meta, "twoResumers", source);
        if (meta.has("preCompactionDurable")) rr.preCompactionDurable = requiredBool(meta, "preCompactionDurable", source);
        rr.preSummarizeCalls = intField(meta, "preSummarizeCalls", rr.preSummarizeCalls, source);
        if (meta.has("forkScenario")) rr.forkScenario = requiredBool(meta, "forkScenario", source);
        if (meta.has("cancelAt")) rr.cancelAt = intField(meta, "cancelAt", rr.cancelAt, source);
        if (meta.has("childTrace0")) rr.childTrace0 = requiredString(meta, "childTrace0", source);
        if (meta.has("childTrace1")) rr.childTrace1 = requiredString(meta, "childTrace1", source);
        if (meta.has("stateHash")) rr.stateHash = requiredString(meta, "stateHash", source);
    }

    private static List<Model.Event> eventArray(Json.Obj meta, String key, String source) {
        Json.Value v = meta.get(key);
        if (v == null || v.isNull()) return List.of();
        return parseEventArray(source + " field \"" + key + "\"", v.asArray());
    }

    private static List<String> stringList(Json.Obj meta, String key, String source) {
        try {
            return meta.stringList(key);
        } catch (Json.JsonException e) {
            throw new FormatException(source + ": field \"" + key + "\": " + e.getMessage());
        }
    }

    private static Set<String> stringSet(Json.Obj meta, String key, String source) {
        List<String> list = stringList(meta, key, source);
        return Collections.unmodifiableSet(new LinkedHashSet<>(list));
    }

    private static Map<String, Integer> countMap(Json.Obj meta, String key, String source) {
        Json.Obj o;
        try {
            o = meta.object(key);
        } catch (Json.JsonException e) {
            throw new FormatException(source + ": field \"" + key + "\": " + e.getMessage());
        }
        if (o == null) return Map.of();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Json.Value> e : o.fields().entrySet()) {
            Json.Value v = e.getValue();
            int count;
            try {
                count = v.asInt();
            } catch (Json.JsonException ex) {
                throw new FormatException(source + ": field \"" + key + "." + e.getKey()
                        + "\": expected a non-negative integer");
            }
            if (count < 0) {
                throw new FormatException(source + ": field \"" + key + "." + e.getKey()
                        + "\": expected a non-negative integer, got " + count);
            }
            out.put(e.getKey(), count);
        }
        return Collections.unmodifiableMap(out);
    }

    private static int intField(Json.Obj o, String key, int def, String source) {
        try {
            return o.integer(key, def);
        } catch (Json.JsonException e) {
            throw new FormatException(source + ": field \"" + key + "\": " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * Parse a JSONL file (one object per non-blank line) or a JSON array of
     * objects. Empty input yields an empty list.
     */
    static List<Json.Obj> records(String source, String text) {
        String body = stripBom(text);
        String trimmed = body.strip();
        if (trimmed.isEmpty()) return List.of();
        List<Json.Obj> out = new ArrayList<>();
        if (trimmed.charAt(0) == '[') {
            Json.Arr arr;
            try {
                arr = Json.parse(trimmed).asArray();
            } catch (Json.ParseException e) {
                throw new FormatException(source + ": " + e.getMessage());
            } catch (Json.JsonException e) {
                throw new FormatException(source + ": " + e.getMessage());
            }
            for (int i = 0; i < arr.size(); i++) {
                Json.Value v = arr.get(i);
                if (!v.isObject()) {
                    throw new FormatException(source + ": array element " + i + " must be a JSON object, found "
                            + v.kind());
                }
                out.add(v.asObject());
            }
            return out;
        }
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isBlank()) continue;
            Json.Value v;
            try {
                v = Json.parse(line);
            } catch (Json.ParseException e) {
                throw new FormatException(source + ":" + (i + 1) + ": " + e.getMessage()
                        + " (each record must be a single-line JSON object)");
            }
            if (!v.isObject()) {
                throw new FormatException(source + ":" + (i + 1) + ": expected a JSON object, found " + v.kind());
            }
            out.add(v.asObject());
        }
        return out;
    }

    static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static void rejectUnknown(Json.Obj o, Set<String> allowed, String where) {
        for (String key : o.keys()) {
            if (!allowed.contains(key)) {
                List<String> sorted = new ArrayList<>(allowed);
                Collections.sort(sorted);
                throw new FormatException(where + ": unknown field \"" + key
                        + "\" (allowed: " + String.join(", ", sorted) + ")");
            }
        }
    }

    private static String requiredString(Json.Obj o, String key, String where) {
        Json.Value v = o.get(key);
        if (v == null) throw new FormatException(where + ": missing required field \"" + key + "\"");
        try {
            return v.asString();
        } catch (Json.JsonException e) {
            throw new FormatException(where + ": field \"" + key + "\": " + e.getMessage());
        }
    }

    private static String optionalString(Json.Obj o, String key, String where) {
        Json.Value v = o.getNonNull(key);
        if (v == null) return null;
        try {
            return v.asString();
        } catch (Json.JsonException e) {
            throw new FormatException(where + ": field \"" + key + "\": " + e.getMessage());
        }
    }

    private static long requiredLong(Json.Obj o, String key, String where) {
        Json.Value v = o.get(key);
        if (v == null) throw new FormatException(where + ": missing required field \"" + key + "\"");
        try {
            return v.asLong();
        } catch (Json.JsonException e) {
            throw new FormatException(where + ": field \"" + key + "\": " + e.getMessage());
        }
    }

    private static boolean requiredBool(Json.Obj o, String key, String where) {
        Json.Value v = o.get(key);
        if (v == null) throw new FormatException(where + ": missing required field \"" + key + "\"");
        try {
            return v.asBoolean();
        } catch (Json.JsonException e) {
            throw new FormatException(where + ": field \"" + key + "\": " + e.getMessage());
        }
    }

}
