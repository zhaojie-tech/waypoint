package waypoint;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
// Iteration order feeds the gate report text: keep LinkedHashMap, not Map.copyOf.

/** Event model, canonical encoding, state hashing, and the externally observed
 *  ground truth (effect ledger, model-call spy, metering ledger). */
public final class Model {

    private Model() {}

    // ---------- events ----------

    public enum Type {
        RUN_STARTED(1), STEP_STARTED(2), STEP_COMPLETED(3), STEP_UNKNOWN(4),
        CHUNK(5), CANCELLED(6), COMPACTION(7), CHECKPOINT(8), LEASED(9), RUN_COMPLETED(10);

        final int tag;
        Type(int tag) { this.tag = tag; }
        static Type byTag(int t) { for (Type v : values()) if (v.tag == t) return v; throw new IllegalArgumentException("tag " + t); }
    }

    /** Fields are stringly typed on purpose (payload discipline: explicit
     *  schemaVersion + text encoding; no Java native serialization). */
    public static final class Event {
        public final Type type;
        public final String f1, f2, f3, f4;

        public Event(Type type, String f1, String f2, String f3, String f4) {
            this.type = type; this.f1 = f1; this.f2 = f2; this.f3 = f3; this.f4 = f4;
        }

        public byte[] encode() {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            try {
                out.writeByte(1); // schemaVersion
                out.writeByte(type.tag);
                writeS(out, f1); writeS(out, f2); writeS(out, f3); writeS(out, f4);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            return bos.toByteArray();
        }

        public static Event decode(byte[] raw) {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            try {
                int v = in.readUnsignedByte();
                if (v != 1) throw new IOException("schema " + v);
                Type t = Type.byTag(in.readUnsignedByte());
                return new Event(t, readS(in), readS(in), readS(in), readS(in));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }

        private static void writeS(DataOutputStream out, String s) throws IOException {
            byte[] b = (s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
            out.writeInt(b.length);
            out.write(b);
        }
        private static String readS(DataInputStream in) throws IOException {
            int n = in.readInt();
            byte[] b = new byte[n];
            in.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        /** Canonical form used by hashing: never depends on map order. */
        public String canonical() {
            return type + "\u0001" + n(f1) + "\u0001" + n(f2) + "\u0001" + n(f3) + "\u0001" + n(f4);
        }
        private static String n(String s) { return s == null ? "" : s; }
    }

    // ---------- canonical state + hash ----------

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Canonical state hash: sorted by step path, covers status+result, chunk
     *  count, summary. NEVER depends on insertion order or wall clock. */
    public static String stateHash(Map<String, String> stepStatus, Map<String, Integer> chunkCounts) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(stepStatus).entrySet()) {
            lines.add("S|" + e.getKey() + "|" + e.getValue());
        }
        for (Map.Entry<String, Integer> e : new TreeMap<>(chunkCounts).entrySet()) {
            lines.add("C|" + e.getKey() + "|" + e.getValue());
        }
        return sha256(String.join("\n", lines));
    }

    // ---------- external ground truth ----------

    /** One observed application of a side effect to "the outside world".
     *  `executor` is who performed it: "runner" | "resumer1" | "resumer2" |
     *  "external" (the frozen C5 observation point). */
    public static final class ApplyRecord {
        public final String key;
        public final long startedRecordIndex; // -1 if STEP_STARTED not yet appended
        public final long syncedFrontierAtApply; // Wal.frontier() at apply time
        public final String executor;
        public ApplyRecord(String key, long startedRecordIndex, long syncedFrontierAtApply) {
            this(key, startedRecordIndex, syncedFrontierAtApply, "runner");
        }
        public ApplyRecord(String key, long startedRecordIndex, long syncedFrontierAtApply, String executor) {
            this.key = key; this.startedRecordIndex = startedRecordIndex;
            this.syncedFrontierAtApply = syncedFrontierAtApply;
            this.executor = (executor == null ? "runner" : executor);
        }
    }

    /** External ledger: how many times each side effect actually took effect.
     *  This is the ground truth the checker audits (C2/C5). */
    public static final class ExternalLedger {
        private final Map<String, Integer> applied = new LinkedHashMap<>();
        private final List<ApplyRecord> records = new ArrayList<>();

        /** Backwards-compatible 3-arg form; executor defaults to "runner". */
        public void apply(String key, long startedRecordIndex, long syncedFrontierAtApply) {
            apply(key, startedRecordIndex, syncedFrontierAtApply, "runner");
        }

        public void apply(String key, long startedRecordIndex, long syncedFrontierAtApply, String executor) {
            applied.merge(key, 1, Integer::sum);
            records.add(new ApplyRecord(key, startedRecordIndex, syncedFrontierAtApply, executor));
        }
        public boolean isApplied(String key) { return applied.getOrDefault(key, 0) > 0; }
        public Map<String, Integer> appliedCounts() { return Collections.unmodifiableMap(new LinkedHashMap<>(applied)); }
        public List<ApplyRecord> records() { return List.copyOf(records); }
    }

    /** Model-call spy: how many times each (op, subject) was invoked. */
    public static final class ModelSpy {
        private final Map<String, Integer> calls = new LinkedHashMap<>();
        public void call(String op, String subject) { calls.merge(op + ":" + subject, 1, Integer::sum); }
        public int count(String op, String subject) { return calls.getOrDefault(op + ":" + subject, 0); }
        public Map<String, Integer> all() { return Map.copyOf(calls); }
    }

    /** S3 metering ledger: one entry per metered model call (and, for a naive
     *  implementation, per replayed chunk. M13 injects this).
     *  External truth: it survives crashes, like the applied-effect ledger. */
    public static final class MeteringLedger {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final List<String> entries = new ArrayList<>();

        public void apply(String key) {
            counts.merge(key, 1, Integer::sum);
            entries.add(key);
        }
        public int size() { return entries.size(); }
        public List<String> entries() { return List.copyOf(entries); }
        public Map<String, Integer> counts() { return Collections.unmodifiableMap(new LinkedHashMap<>(counts)); }
    }
}
