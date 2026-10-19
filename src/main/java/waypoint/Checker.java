package waypoint;

import java.util.*;
import java.util.regex.Pattern;

/** Contract checker. Takes one RunResult (durable log plus external ground
 *  truth) and returns contract findings.
 *
 *  Nine recovery contracts, plus L1 for log integrity:
 *    C1 prefix continuation      C2 effect exactly-once
 *    C3 fork determinism         C4 checkpoint validity
 *    C5 consume-once (lease)     C6 recovery determinism
 *    S1 stream/cancel boundary   S2 compaction provenance
 *    S3 metering consistency     L1 log integrity
 *
 *  Rules an adapter cannot observe are listed in
 *  {@link RunResult#unobservable}. The checker skips them and does not report
 *  them as clean.
 */
public final class Checker {

    private Checker() {}

    public static final class Finding {
        public final String contract;
        public final String detail;
        public Finding(String contract, String detail) { this.contract = contract; this.detail = detail; }
        public String toString() { return contract + " :: " + detail; }
    }

    /** Everything the checker is allowed to look at. */
    public static final class RunResult {
        public String scenario;
        public List<Model.Event> events;
        public Model.ExternalLedger ext;
        public Model.ModelSpy model;
        public Set<String> preCompletedKeys = Set.of(); // keys durable-COMPLETED at recovery start
        public Map<String, Integer> preAppliedCounts = Map.of(); // external applied counts at recovery start
        public boolean twoResumers;
        public int cancelAt = 17;
        public boolean preCompactionDurable; // compaction record durable at recovery start
        public int preSummarizeCalls;        // model summarize calls at recovery start
        public String childTrace0 = "", childTrace1 = "";
        public boolean forkScenario;
        public String stateHash = "";

        // ---- frozen observation fields (introduced in v0.1.0, unchanged here) ----
        /** C1: step paths the run is expected to complete (structural). */
        public List<String> expectedSteps = List.of();
        /** C3: adapter-observed child event streams; when non-empty the checker
         *  computes the canonical traces itself (no trust in a precomputed hash). */
        public List<Model.Event> childEvents0 = List.of();
        public List<Model.Event> childEvents1 = List.of();
        /** false: the adapter cannot observe STARTED/frontier ordering (p3). */
        public boolean protocolObservable = true;
        /** C6: state hashes across >=2 recoveries; must be all-equal. */
        public List<String> replayHashes = List.of();
        /** C6: canonical traces across scheduling/order seeds (multiset compare). */
        public List<String> orderTraces = List.of();
        /** S1: configured cancellation boundary K (-1 = not asserted). */
        public int configuredCancelAt = -1;
        /** S1: chunks emitted by the replay path (-1 = not observable). */
        public int replayChunksEmitted = -1;
        /** S3: metering entries at recovery start. */
        public int meteringPreEntries = 0;
        /** S3: metering entries at end of run. */
        public int meteringFinalEntries = 0;
        /** S3: classifications of interrupted streams, subset of
         *  {COMPLETED, ESTIMATED, UNKNOWN}. */
        public Set<String> interruptionKinds = Set.of();
        /** Contracts the adapter explicitly cannot observe. The checker skips
         *  them (and adds its own skips here) instead of reporting clean. */
        public Set<String> unobservable = Set.of();
        /** Contracts that were only PARTIALLY checked: at least one rule had
         *  evidence, at least one did not (e.g. C1's skip rule ran but the
         *  ledger-based re-execution rule had no ledger to compare against). */
        public Set<String> partial = Set.of();
    }

    private static final Pattern CHILD_KEY = Pattern.compile("#c\\d+");
    private static final Set<String> LEGAL_INTERRUPTIONS = Set.of("COMPLETED", "ESTIMATED", "UNKNOWN");

    public static List<Finding> check(RunResult r) {
        List<Finding> out = new ArrayList<>();
        Set<String> unobs = new LinkedHashSet<>(r.unobservable);

        // An adapter that supplies no external ledger has no observations for the
        // effect-side contracts. Report them as unobservable/partial instead of
        // returning a "clean" verdict that was never checked (docs/adapter-guide.md).
        Set<String> partial = new LinkedHashSet<>(r.partial);
        boolean ledgerAbsent = r.ext.records().isEmpty() && r.ext.appliedCounts().isEmpty();
        if (ledgerAbsent) {
            unobs.add("C2"); // C2 is entirely ledger-based
            // C1 has two rules: re-execution (ledger) and skipped unfinished
            // steps (expectedSteps + event log only).
            if (r.expectedSteps.isEmpty()) {
                unobs.add("C1");
            } else {
                partial.add("C1");
            }
        }

        // --- C2: each idempotent side effect applied at most once ---
        if (observable(unobs, "C2")) {
            for (Map.Entry<String, Integer> e : r.ext.appliedCounts().entrySet()) {
                if (e.getValue() > 1) {
                    out.add(new Finding("C2", "effect " + e.getKey() + " applied " + e.getValue() + "x"));
                }
            }
        }

        // --- C2 (protocol): an effect may only fire after its STEP_STARTED is durable ---
        if (observable(unobs, "C2")) {
            if (r.protocolObservable) {
                for (Model.ApplyRecord a : r.ext.records()) {
                    if (a.startedRecordIndex < 0) {
                        out.add(new Finding("C2", "effect " + a.key + " applied with no durable STEP_STARTED"));
                    } else if (a.startedRecordIndex >= a.syncedFrontierAtApply) {
                        out.add(new Finding("C2", "effect " + a.key + " applied before STEP_STARTED was synced"
                                + " (startedIdx=" + a.startedRecordIndex + ", frontier=" + a.syncedFrontierAtApply + ")"));
                    }
                }
            } else {
                unobs.add("C2"); // ordering unobservable through this adapter
            }
        }

        // --- C1: recovery must not re-execute completed steps (rule 1) ---
        if (observable(unobs, "C1")) {
            for (Map.Entry<String, Integer> e : r.ext.appliedCounts().entrySet()) {
                int before = r.preAppliedCounts.getOrDefault(e.getKey(), 0);
                if (r.preCompletedKeys.contains(e.getKey()) && e.getValue() > before) {
                    out.add(new Finding("C1", "recovery re-executed completed step " + e.getKey()
                            + " (" + before + "->" + e.getValue() + ")"));
                }
            }
            // --- C1 rule 2: a finished run must not skip an unfinished step ---
            if (!r.expectedSteps.isEmpty() && hasRunCompleted(r.events)) {
                Set<String> completedPaths = new HashSet<>();
                for (Model.Event e : r.events) {
                    if (e.type == Model.Type.STEP_COMPLETED) completedPaths.add(e.f1);
                }
                for (String p : r.expectedSteps) {
                    if (!completedPaths.contains(p)) {
                        out.add(new Finding("C1", "skipped unfinished step " + p));
                    }
                }
            }
        }

        // --- C4: every checkpoint-claimed COMPLETED must exist in the log prefix ---
        if (observable(unobs, "C4")) {
            for (int i = 0; i < r.events.size(); i++) {
                Model.Event e = r.events.get(i);
                if (e.type == Model.Type.CHECKPOINT && e.f2 != null) {
                    for (String group : e.f2.split(";")) {
                        String g = group.trim();
                        int eq = g.indexOf('=');
                        if (eq <= 0) continue;
                        String path = g.substring(0, eq);
                        String status = g.substring(eq + 1);
                        if (status.equals("COMPLETED") && !hasStepCompletedBefore(r.events, path, i)) {
                            out.add(new Finding("C4", "checkpoint at record " + i + " marks " + path
                                    + " COMPLETED but no STEP_COMPLETED for it exists in the log prefix"));
                        }
                    }
                }
            }
        }

        // --- C5: consume-once under two resumers (executor + lease observations) ---
        if (observable(unobs, "C5")) {
            if (r.twoResumers) {
                Map<String, String> keyToPath = new HashMap<>();
                Map<String, Long> leaseIndex = new LinkedHashMap<>();
                Map<String, String> leaseOwner = new LinkedHashMap<>();
                for (int i = 0; i < r.events.size(); i++) {
                    Model.Event e = r.events.get(i);
                    if (e.type == Model.Type.STEP_STARTED) keyToPath.put(e.f2, e.f1);
                    if (e.type == Model.Type.LEASED) { leaseOwner.put(e.f1, e.f2); leaseIndex.put(e.f1, (long) i); }
                }
                // rule 1: same key applied by >=2 distinct executors
                Map<String, Set<String>> execsByKey = new LinkedHashMap<>();
                for (Model.ApplyRecord a : r.ext.records()) {
                    execsByKey.computeIfAbsent(a.key, k -> new LinkedHashSet<>()).add(a.executor);
                }
                for (Map.Entry<String, Set<String>> e : execsByKey.entrySet()) {
                    if (e.getValue().size() >= 2) {
                        out.add(new Finding("C5", "key " + e.getKey() + " consumed by " + e.getValue().size()
                                + " distinct executors " + e.getValue()));
                    }
                }
                // rule 2: an effect for a leased path executed by a non-owner
                for (Model.ApplyRecord a : r.ext.records()) {
                    String path = keyToPath.get(a.key);
                    if (path == null) continue;
                    String owner = leaseOwner.get(path);
                    Long lx = leaseIndex.get(path);
                    if (owner == null || lx == null) continue;
                    if (a.syncedFrontierAtApply > lx && !owner.equals(a.executor)) {
                        out.add(new Finding("C5", "effect for " + path + " executed by " + a.executor
                                + " while the lease was owned by " + owner));
                    }
                }
                // rule 3: no lease and no executor evidence -> unobservable, not clean
                boolean evidence = !leaseOwner.isEmpty()
                        || r.ext.records().stream().anyMatch(a -> !"runner".equals(a.executor));
                if (!evidence) unobs.add("C5");
            }
        }

        // --- C3: forks from the same checkpoint behave identically ---
        if (observable(unobs, "C3")) {
            boolean ev0 = !r.childEvents0.isEmpty(), ev1 = !r.childEvents1.isEmpty();
            boolean tr0 = !r.childTrace0.isEmpty(), tr1 = !r.childTrace1.isEmpty();
            if (ev0 && ev1) {
                if (!canonicalSorted(r.childEvents0).equals(canonicalSorted(r.childEvents1))) {
                    out.add(new Finding("C3", "fork children diverge (canonical event traces differ)"));
                }
            } else if (tr0 && tr1) {
                if (!r.childTrace0.equals(r.childTrace1)) {
                    out.add(new Finding("C3", "fork children diverge (trace hashes differ)"));
                }
            } else {
                unobs.add("C3"); // not observable: neither event streams nor trace hashes
            }
        }

        // --- C6: recovery determinism (replay + scheduling order) ---
        if (observable(unobs, "C6")) {
            boolean any = false;
            if (r.replayHashes.size() >= 2) {
                any = true;
                Set<String> distinct = new LinkedHashSet<>(r.replayHashes);
                if (distinct.size() > 1) {
                    out.add(new Finding("C6", "replay state hash not stable across "
                            + r.replayHashes.size() + " recoveries"));
                }
            }
            if (r.orderTraces.size() >= 2) {
                any = true;
                // each trace is normalized to a canonical MULTISET of lines, then
                // all traces must be equal (order seed must not change the multiset)
                Set<String> normalized = new LinkedHashSet<>();
                for (String t : r.orderTraces) normalized.add(normalizeTrace(t));
                if (normalized.size() > 1) {
                    out.add(new Finding("C6", "order-dependent canonical trace across seeds"));
                }
            }
            if (!any) unobs.add("C6");
        }

        // --- S1: stream prefix fidelity + cancellation boundary ---
        if (observable(unobs, "S1")) {
            boolean sawCancel = false;
            for (int i = 0; i < r.events.size(); i++) {
                Model.Event e = r.events.get(i);
                if (e.type == Model.Type.CANCELLED) {
                    sawCancel = true;
                    int at = Integer.parseInt(e.f2);
                    int chunks = 0;
                    for (Model.Event c : r.events) {
                        if (c.type == Model.Type.CHUNK && c.f1.equals(e.f1)) chunks++;
                    }
                    if (chunks != at) {
                        out.add(new Finding("S1", "cancel boundary " + at + " but " + chunks
                                + " chunks recorded for " + e.f1));
                    }
                    int after = 0;
                    for (int j = i + 1; j < r.events.size(); j++) {
                        Model.Event c = r.events.get(j);
                        if (c.type == Model.Type.CHUNK && c.f1.equals(e.f1)) after++;
                    }
                    if (after > 0) {
                        out.add(new Finding("S1", after + " chunks recorded after the cancellation boundary for "
                                + e.f1));
                    }
                    if (r.model.count("gen", e.f1) > 1) {
                        out.add(new Finding("S1", "generation re-invoked the model after cancel"));
                    }
                    if (r.replayChunksEmitted >= 0 && r.replayChunksEmitted != at) {
                        out.add(new Finding("S1", "replay emitted " + r.replayChunksEmitted
                                + " chunks != K=" + at));
                    }
                }
            }
            // configured-but-not-durable cancellation: only assert when the
            // cancellation became durable (otherwise it is a partial prefix)
            if (sawCancel && r.configuredCancelAt >= 0) {
                for (Model.Event e : r.events) {
                    if (e.type == Model.Type.CANCELLED) {
                        int logged = Integer.parseInt(e.f2);
                        if (logged != r.configuredCancelAt) {
                            out.add(new Finding("S1", "configured cancelAt=" + r.configuredCancelAt
                                    + " but log recorded " + logged));
                        }
                    }
                }
            }
            if (!sawCancel && r.configuredCancelAt < 0 && r.replayChunksEmitted < 0) unobs.add("S1");
        }

        // --- S2: compaction provenance. Legitimate re-summarize only happens
        //        when the summary was NOT durable at recovery start. ---
        if (observable(unobs, "S2")) {
            if (r.preCompactionDurable
                    && r.model.count("summarize", "root/compact") > r.preSummarizeCalls) {
                out.add(new Finding("S2", "summary was durable but replay re-invoked the model ("
                        + r.preSummarizeCalls + "->" + r.model.count("summarize", "root/compact") + ")"));
            }
        }

        // --- S3: metering consistency on replay ---
        if (observable(unobs, "S3")) {
            for (String kind : r.interruptionKinds) {
                if (!LEGAL_INTERRUPTIONS.contains(kind)) {
                    out.add(new Finding("S3", "illegal interruption classification '" + kind + "'"));
                }
            }
            if (r.meteringPreEntries > 0) {
                if (r.meteringFinalEntries > r.meteringPreEntries) {
                    out.add(new Finding("S3", "replay produced new metering entries ("
                            + r.meteringPreEntries + "->" + r.meteringFinalEntries + ")"));
                }
            } else {
                unobs.add("S3"); // no pre-recovery metering evidence
            }
        }

        // --- L1: auxiliary log-integrity checks (not a 10th contract) ---
        Map<String, Integer> startedCount = new HashMap<>(), completedCount = new HashMap<>();
        int runCompleted = 0;
        for (Model.Event e : r.events) {
            if (e.type == Model.Type.STEP_STARTED) startedCount.merge(e.f1, 1, Integer::sum);
            if (e.type == Model.Type.RUN_COMPLETED) runCompleted++;
            if (e.type == Model.Type.STEP_COMPLETED) {
                int prior = startedCount.getOrDefault(e.f1, 0);
                int seen = completedCount.merge(e.f1, 1, Integer::sum);
                if (prior == 0) {
                    out.add(new Finding("L1", "STEP_COMPLETED for " + e.f1
                            + " without a preceding durable STEP_STARTED"));
                }
                if (seen > 1) {
                    out.add(new Finding("L1", "duplicate STEP_COMPLETED for " + e.f1
                            + " (" + seen + " completions)"));
                }
            }
        }
        if (runCompleted > 1) {
            out.add(new Finding("L1", "RUN_COMPLETED appears " + runCompleted + " times"));
        }

        r.unobservable = Collections.unmodifiableSet(unobs);
        r.partial = Collections.unmodifiableSet(partial);
        return out;
    }

    private static boolean observable(Set<String> unobs, String contract) {
        return !unobs.contains(contract);
    }

    /** Canonical multiset form of a trace: split into lines, sort, join. */
    static String normalizeTrace(String trace) {
        if (trace == null || trace.isEmpty()) return "";
        String[] lines = trace.split("\n");
        Arrays.sort(lines);
        return String.join("\n", lines);
    }

    private static List<String> canonicalSorted(List<Model.Event> events) {
        List<String> lines = new ArrayList<>(events.size());
        for (Model.Event e : events) lines.add(CHILD_KEY.matcher(e.canonical()).replaceAll("#c"));
        Collections.sort(lines);
        return lines;
    }

    private static boolean hasRunCompleted(List<Model.Event> events) {
        for (Model.Event e : events) if (e.type == Model.Type.RUN_COMPLETED) return true;
        return false;
    }

    private static boolean hasStepCompletedBefore(List<Model.Event> events, String path, int before) {
        for (int i = 0; i < before; i++) {
            Model.Event e = events.get(i);
            if (e.type == Model.Type.STEP_COMPLETED && path.equals(e.f1)) return true;
        }
        return false;
    }
}
