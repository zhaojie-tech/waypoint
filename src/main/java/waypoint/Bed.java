package waypoint;

import java.util.*;

/**
 * Calibration bed: a minimal workflow runtime used as the mutation-injection
 * target. It runs single-threaded, never reads the clock, and sends all
 * effects through an external ledger and model spy.
 *
 * Crash semantics: a Crash at operation k discards all memory of this Bed
 * instance. Recovery re-opens the WAL over the same MemoryDisk and first
 * calls {@link WaypointLog.MemoryDisk#discardUnsynced()}, so the unsynced tail
 * of the crash is gone. ExternalLedger, ModelSpy and MeteringLedger live
 * outside the Bed and survive the crash; the checker audits them as ground
 * truth.
 */
public final class Bed {

    public static final class Crash extends RuntimeException {
        public Crash() { super(null, null, false, false); }
    }

    public enum Workflow { W1_ORDER, W2_STREAM, W3_FORK }

    /** Mutant switches: each flag encodes exactly one known contract violation. */
    public static final class Config {
        public Workflow workflow = Workflow.W1_ORDER;
        public boolean m1EffectBeforeStart, m2SkipStartSync, m3RecoveryRetryNoReconcile,
                m4ReplayReexecutes, m5CompletionOrderIds, m6CheckpointFabricatesCompleted,
                m7ForkSharesCursor, m8NoLease, m9OverrunCancel, m10ReplayRegeneratesSummary,
                m11SkipUnfinishedStep, m12ReplayEmitsWrongChunks, m13MeteringOnReplay;
        public int cancelAt = 17, orderSeed = 0;
        public boolean checkpointMidStep; // W1: checkpoint between notify STARTED and its effect
        public boolean secondResumer;     // W1_RESUME2 scenario: run the 3-phase two-resumer protocol
        public long crashAtOp = -1;       // crash BEFORE performing operation #crashAtOp; -1 = never
        public int childIndex = 0;

        public static Config healthy(Workflow w) {
            Config c = new Config();
            c.workflow = w;
            return c;
        }

        public Config copy() {
            Config c = new Config();
            c.workflow = workflow; c.m1EffectBeforeStart = m1EffectBeforeStart; c.m2SkipStartSync = m2SkipStartSync;
            c.m3RecoveryRetryNoReconcile = m3RecoveryRetryNoReconcile; c.m4ReplayReexecutes = m4ReplayReexecutes;
            c.m5CompletionOrderIds = m5CompletionOrderIds; c.m6CheckpointFabricatesCompleted = m6CheckpointFabricatesCompleted;
            c.m7ForkSharesCursor = m7ForkSharesCursor; c.m8NoLease = m8NoLease; c.m9OverrunCancel = m9OverrunCancel;
            c.m10ReplayRegeneratesSummary = m10ReplayRegeneratesSummary;
            c.m11SkipUnfinishedStep = m11SkipUnfinishedStep; c.m12ReplayEmitsWrongChunks = m12ReplayEmitsWrongChunks;
            c.m13MeteringOnReplay = m13MeteringOnReplay;
            c.cancelAt = cancelAt; c.orderSeed = orderSeed;
            c.checkpointMidStep = checkpointMidStep; c.secondResumer = secondResumer; c.crashAtOp = crashAtOp; c.childIndex = childIndex;
            return c;
        }

        public String mutantName() {
            if (m1EffectBeforeStart) return "M1";
            if (m2SkipStartSync) return "M2";
            if (m3RecoveryRetryNoReconcile) return "M3";
            if (m4ReplayReexecutes) return "M4";
            if (m5CompletionOrderIds) return "M5";
            if (m6CheckpointFabricatesCompleted) return "M6";
            if (m7ForkSharesCursor) return "M7";
            if (m8NoLease) return "M8";
            if (m9OverrunCancel) return "M9";
            if (m10ReplayRegeneratesSummary) return "M10";
            if (m11SkipUnfinishedStep) return "M11";
            if (m12ReplayEmitsWrongChunks) return "M12";
            if (m13MeteringOnReplay) return "M13";
            return "HEALTHY";
        }
    }

    public enum RecoverMode { FULL, LEASE_ONLY, SECOND_RESUMER, CONTINUE }

    // external truth survives crashes
    public final Model.ExternalLedger ext;
    public final Model.ModelSpy model;
    public final Model.MeteringLedger metering;

    private final Config cfg;
    private final WaypointLog.MemoryDisk disk;
    private final WaypointLog.Wal wal;
    private final Map<String, Long> startedIdx = new HashMap<>();
    private final Map<String, String> runStatus = new TreeMap<>();
    private long opCount = 0;
    private int completionCounter = 0;
    private String executor = "runner";
    private List<Model.Event> childEvents0 = List.of(), childEvents1 = List.of();

    public String childTrace0 = "", childTrace1 = "";

    public Bed(Config cfg, WaypointLog.MemoryDisk disk, Model.ExternalLedger ext, Model.ModelSpy model) {
        this(cfg, disk, ext, model, new Model.MeteringLedger());
    }

    public Bed(Config cfg, WaypointLog.MemoryDisk disk, Model.ExternalLedger ext, Model.ModelSpy model,
               Model.MeteringLedger metering) {
        this.cfg = cfg;
        this.disk = disk;
        this.ext = ext;
        this.model = model;
        this.metering = metering;
        this.wal = new WaypointLog.Wal(disk);
    }

    public long opsExecuted() { return opCount; }

    /** Steps that must be durable-COMPLETED when the run reaches RUN_COMPLETED
     *  (structural paths, before any C6 id-renaming mutation). */
    public static List<String> expectedSteps(Workflow w) {
        return switch (w) {
            case W1_ORDER -> List.of("root/charge", "root/notify", "root/report");
            case W2_STREAM -> List.of("root/apply");
            case W3_FORK -> List.of("root/base");
        };
    }

    public List<String> expectedSteps() { return expectedSteps(cfg.workflow); }

    public List<Model.Event> childEvents0() { return childEvents0; }
    public List<Model.Event> childEvents1() { return childEvents1; }

    // ---------- operation accounting / crash injection ----------

    private void op() {
        opCount++;
        if (opCount == cfg.crashAtOp) throw new Crash();
    }

    private long appendOnly(Model.Type t, String f1, String f2, String f3, String f4) {
        op();
        return wal.append(new Model.Event(t, f1, f2, f3, f4).encode());
    }

    private void syncOp() { op(); wal.sync(); }

    private void appendSync(Model.Type t, String f1, String f2, String f3, String f4) {
        appendOnly(t, f1, f2, f3, f4);
        syncOp();
    }

    private void applyEffect(String path, String key) {
        op();
        ext.apply(key, startedIdx.getOrDefault(key, -1L), wal.frontier(), executor);
    }

    private void modelCall(String opName, String subject) {
        op();
        model.call(opName, subject);
        metering.apply(opName + ":" + subject);
    }

    // ---------- step ids ----------

    private String id(String structuralPath) {
        return cfg.m5CompletionOrderIds ? "s" + (++completionCounter) : structuralPath;
    }

    // ---------- recording ----------

    public void recordRun() {
        appendSync(Model.Type.RUN_STARTED, cfg.workflow.name(), "v1", null, null);
        switch (cfg.workflow) {
            case W1_ORDER -> {
                doStep("root/charge", "k_charge", false);
                doStep("root/notify", "k_notify", cfg.checkpointMidStep);
                doStep("root/report", "k_report", false);
            }
            case W2_STREAM -> { doGen(); doCompact(); doStep("root/apply", "k_apply", false); }
            case W3_FORK -> {
                doStep("root/base", "k_base", false);
                runForkChildren();
            }
        }
        appendSync(Model.Type.RUN_COMPLETED, null, null, null, null);
    }

    /** One plain effect step. checkpointAt = take a checkpoint right after
     *  this step's STARTED is durable, before its effect (M6 scenario). */
    private void doStep(String structuralPath, String key, boolean checkpointAt) {
        String path = id(structuralPath);
        long idx;
        if (cfg.m1EffectBeforeStart) {
            applyEffect(path, key);
            idx = appendOnly(Model.Type.STEP_STARTED, path, key, null, null);
            startedIdx.put(key, idx);
            syncOp();
        } else {
            idx = appendOnly(Model.Type.STEP_STARTED, path, key, null, null);
            startedIdx.put(key, idx);
            if (!cfg.m2SkipStartSync) syncOp();
        }
        runStatus.put(path, "STARTED");
        if (checkpointAt) takeCheckpoint(path);
        applyEffect(path, key);
        appendSync(Model.Type.STEP_COMPLETED, path, "ok:" + key, null, null);
        runStatus.put(path, "COMPLETED");
    }

    /** Checkpoint blob: `path=status;path=status` over all steps known so far.
     *  M6 fabricates the in-flight step as COMPLETED. */
    private void takeCheckpoint(String inFlightPath) {
        StringBuilder snap = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(runStatus).entrySet()) {
            if (snap.length() > 0) snap.append(';');
            String status = e.getValue();
            if (cfg.m6CheckpointFabricatesCompleted && e.getKey().equals(inFlightPath)) {
                status = "COMPLETED";
            }
            snap.append(e.getKey()).append('=').append(status.startsWith("COMPLETED") ? "COMPLETED" : status);
        }
        appendSync(Model.Type.CHECKPOINT, "w1", snap.toString(), null, null);
    }

    private void doGen() {
        String path = "root/gen";
        modelCall("gen", path);
        int upTo = cfg.m9OverrunCancel ? cfg.cancelAt + 1 : cfg.cancelAt;
        for (int i = 1; i <= upTo; i++) {
            appendSync(Model.Type.CHUNK, path, String.valueOf(i), "chunk-" + i, null);
        }
        appendSync(Model.Type.CANCELLED, path, String.valueOf(cfg.cancelAt), null, null);
    }

    private void doCompact() {
        String path = "root/compact";
        modelCall("summarize", path);
        String summary = Model.sha256("transcript:" + path);
        appendSync(Model.Type.COMPACTION, path, summary, "promptHash:" + path, "mock-model-v1");
    }

    private void runForkChildren() {
        List<String> order = (cfg.orderSeed % 2 == 0)
                ? List.of("root/branch-x", "root/branch-y")
                : List.of("root/branch-y", "root/branch-x");
        for (int c = 0; c < 2; c++) {
            // M7: fork children SHARE the parent's log cursor (no isolation);
            // healthy: each child gets its own snapshot copy of the checkpoint.
            WaypointLog.MemoryDisk cd = cfg.m7ForkSharesCursor ? disk : snapshotCopy();
            Bed child = new Bed(branchConfig(cfg, c), cd, ext, model, metering);
            child.recordBranch(order);
            List<Model.Event> events = child.durableEvents();
            if (c == 0) { childEvents0 = events; childTrace0 = child.canonicalTrace(); }
            else { childEvents1 = events; childTrace1 = child.canonicalTrace(); }
        }
    }

    private static Config branchConfig(Config parent, int childIndex) {
        Config c = parent.copy();
        c.childIndex = childIndex;
        c.crashAtOp = -1; // children run to completion; crash sweep covers the parent only
        return c;
    }

    private void recordBranch(List<String> order) {
        for (String p : order) {
            String path = id(p);
            String key = "k_" + p.substring(p.lastIndexOf('/') + 1) + "#c" + cfg.childIndex;
            long idx = appendOnly(Model.Type.STEP_STARTED, path, key, null, null);
            startedIdx.put(key, idx);
            syncOp();
            applyEffect(path, key);
            appendSync(Model.Type.STEP_COMPLETED, path, "ok:" + key, null, null);
        }
        appendSync(Model.Type.RUN_COMPLETED, null, null, null, null);
    }

    private List<Model.Event> durableEvents() {
        List<Model.Event> out = new ArrayList<>();
        for (byte[] raw : wal.durableParsed()) out.add(Model.Event.decode(raw));
        return out;
    }

    private String canonicalTrace() {
        List<String> lines = new ArrayList<>();
        for (byte[] raw : wal.durableParsed()) {
            // C3 compares children structurally: per-child key suffixes are masked
            String c = Model.Event.decode(raw).canonical().replaceAll("#c\\d+", "#c");
            lines.add(c);
        }
        Collections.sort(lines);
        return Model.sha256(String.join("\n", lines));
    }

    private WaypointLog.MemoryDisk snapshotCopy() {
        WaypointLog.MemoryDisk copy = new WaypointLog.MemoryDisk();
        copy.seedDurable(disk.durableRecords());
        return copy;
    }

    // ---------- replay (S1) ----------

    /** Replay the durable gen chunk prefix: no model call, no log write.
     *  Returns the number of chunks emitted. M12 injects a wrong count;
     *  M13 injects a new metering entry per replayed chunk. */
    public int replayStream() {
        int emitted = 0;
        for (byte[] raw : wal.durableParsed()) {
            Model.Event e = Model.Event.decode(raw);
            if (e.type == Model.Type.CHUNK) {
                emitted++;
                if (cfg.m13MeteringOnReplay) metering.apply("replay:" + e.f1 + ":" + e.f2);
            }
        }
        if (cfg.m12ReplayEmitsWrongChunks && emitted > 0) emitted++;
        return emitted;
    }

    // ---------- recovery ----------

    public record RecoverOutcome(Map<String, String> finalStatus, Map<String, Integer> chunkCounts) {}

    /** Recover from the durable log. Returns observable outcome. */
    public RecoverOutcome recover(RecoverMode mode) {
        disk.discardUnsynced(); // process crash: the unsynced tail is gone
        wal.reload();
        executor = (mode == RecoverMode.SECOND_RESUMER) ? "resumer2" : "resumer1";

        List<Model.Event> evs = parseDurable();
        startedIdx.clear();
        Map<String, String> status = new HashMap<>(); // path -> STARTED|COMPLETED:...|UNKNOWN
        Map<String, String> keyByPath = new HashMap<>();
        Set<String> completedKeys = new HashSet<>();
        Map<String, String> leaseOwner = new LinkedHashMap<>();
        String genPath = null, genCancelAt = null;
        int genChunks = 0;
        boolean compactionDurable = false;
        boolean runCompletedDurable = false;

        for (int i = 0; i < evs.size(); i++) {
            Model.Event e = evs.get(i);
            switch (e.type) {
                case STEP_STARTED -> { startedIdx.put(e.f2, (long) i); status.put(e.f1, "STARTED"); keyByPath.put(e.f1, e.f2); }
                case STEP_COMPLETED -> {
                    status.put(e.f1, "COMPLETED:" + e.f2);
                    String k = keyByPath.get(e.f1);
                    if (k != null) completedKeys.add(k);
                }
                case STEP_UNKNOWN -> status.put(e.f1, "UNKNOWN");
                case CHUNK -> { genPath = e.f1; genChunks = Math.max(genChunks, Integer.parseInt(e.f2)); }
                case CANCELLED -> { genPath = e.f1; genCancelAt = e.f2; }
                case COMPACTION -> compactionDurable = true;
                case LEASED -> leaseOwner.put(e.f1, e.f2);
                case RUN_COMPLETED -> runCompletedDurable = true;
                default -> { }
            }
        }

        boolean skipUnfinished = cfg.m11SkipUnfinishedStep; // M11: straight to RUN_COMPLETED

        // M4: replay re-executes effects of already-completed steps
        if (!skipUnfinished && cfg.m4ReplayReexecutes && mode != RecoverMode.LEASE_ONLY) {
            for (Model.Event e : evs) {
                if (e.type == Model.Type.STEP_COMPLETED) {
                    String k = keyByPath.get(e.f1);
                    if (k != null) applyEffect(e.f1, k);
                }
            }
        }

        // S2 ground rule: if the compaction record is already durable, replay
        // must not re-call the model. M10 does exactly that.
        if (!skipUnfinished && compactionDurable && cfg.m10ReplayRegeneratesSummary && mode != RecoverMode.LEASE_ONLY) {
            modelCall("summarize", "root/compact");
        }

        // Resolve pending STARTED steps (crash between STARTED and COMPLETED).
        if (!skipUnfinished) {
            for (Map.Entry<String, String> en : new TreeMap<>(status).entrySet()) {
                if (!"STARTED".equals(en.getValue())) continue;
                String p = en.getKey();
                String k = keyByPath.getOrDefault(p, p);
                if (completedKeys.contains(k)) continue;

                if (mode == RecoverMode.SECOND_RESUMER) {
                    String owner = leaseOwner.get(p);
                    if (owner == null) continue;          // no lease: wait for the first resumer
                    if (!cfg.m8NoLease) continue;         // healthy: honor the lease, do not execute
                    // M8: a second resumer ignores another resumer's lease
                    applyEffect(p, k);
                    appendSync(Model.Type.STEP_COMPLETED, p, "ok:" + k + ":resumer2", null, null);
                    continue;
                }
                if (mode == RecoverMode.LEASE_ONLY) {
                    appendSync(Model.Type.LEASED, p, "resumer1", null, null);
                    continue; // pause; harness hands over to the second resumer
                }
                // FULL / CONTINUE: reconcile protocol
                if (cfg.m3RecoveryRetryNoReconcile) {
                    applyEffect(p, k); // M3: retry without consulting reconcile
                } else if (ext.isApplied(k)) {
                    appendSync(Model.Type.STEP_COMPLETED, p, "ok:" + k + ":reconciled", null, null);
                    continue;
                } else {
                    applyEffect(p, k); // reconcile said NOT_DONE -> legitimate re-execution
                }
                appendSync(Model.Type.STEP_COMPLETED, p, "ok:" + k, null, null);
            }
        }

        // Execute workflow steps that never got started (crash before their
        // STARTED, or a STARTED record lost to a skipped sync). The terminal
        // set is REBUILT from the log: pending resolution above may have just
        // completed steps via reconcile.
        if (!skipUnfinished && (mode == RecoverMode.FULL || mode == RecoverMode.CONTINUE)) {
            Set<String> completedNow = new HashSet<>();
            Map<String, String> kb = new HashMap<>();
            for (Model.Event e : parseDurable()) {
                if (e.type == Model.Type.STEP_STARTED) kb.put(e.f1, e.f2);
            }
            for (Model.Event e : parseDurable()) {
                if (e.type == Model.Type.STEP_COMPLETED) {
                    String k = kb.get(e.f1);
                    if (k != null) completedNow.add(k);
                }
            }
            for (String[] s : remainingSteps(completedNow)) {
                String structural = s[0], key = s[1];
                String path = id(structural);
                long idx = appendOnly(Model.Type.STEP_STARTED, path, key, null, null);
                startedIdx.put(key, idx);
                syncOp();
                applyEffect(path, key);
                appendSync(Model.Type.STEP_COMPLETED, path, "ok:" + key, null, null);
                status.put(path, "COMPLETED:" + key);
            }
            // W2: gen terminal but compaction never ran -> run it now
            if (cfg.workflow == Workflow.W2_STREAM && genCancelAt != null && !compactionDurable) {
                doCompact();
                status.put("root/compact", "COMPLETED:summarize");
            }
        }

        // Completion is idempotent: append RUN_COMPLETED at most once per log.
        if ((mode == RecoverMode.FULL || mode == RecoverMode.CONTINUE) && !runCompletedDurable) {
            appendSync(Model.Type.RUN_COMPLETED, null, null, null, null);
        }

        // Outcome is ALWAYS rebuilt from the durable log (never from in-memory
        // maps), so repeated recoveries hash identically.
        List<Model.Event> fin = parseDurable();
        Map<String, String> finStatus = new TreeMap<>();
        Map<String, Integer> finChunks = new HashMap<>();
        String gp = null;
        int gc = 0;
        for (Model.Event e : fin) {
            switch (e.type) {
                case STEP_STARTED -> finStatus.put(e.f1, "STARTED");
                case STEP_COMPLETED -> finStatus.put(e.f1, "COMPLETED:" + e.f2);
                case STEP_UNKNOWN -> finStatus.put(e.f1, "UNKNOWN");
                case CHUNK -> { gp = e.f1; gc = Math.max(gc, Integer.parseInt(e.f2)); }
                case CANCELLED -> gp = e.f1;
                default -> { }
            }
        }
        if (gp != null) finChunks.put(gp, gc);
        return new RecoverOutcome(Collections.unmodifiableMap(finStatus), Collections.unmodifiableMap(finChunks));
    }

    private List<Model.Event> parseDurable() {
        List<Model.Event> out = new ArrayList<>();
        for (byte[] raw : wal.durableParsed()) out.add(Model.Event.decode(raw));
        return out;
    }

    /** Workflow steps (structural path, key) whose key has no durable COMPLETED. */
    private List<String[]> remainingSteps(Set<String> completedKeys) {
        List<String[]> all = switch (cfg.workflow) {
            case W1_ORDER -> List.of(new String[]{"root/charge", "k_charge"},
                    new String[]{"root/notify", "k_notify"}, new String[]{"root/report", "k_report"});
            case W2_STREAM -> List.<String[]>of(new String[]{"root/apply", "k_apply"});
            case W3_FORK -> List.of();
        };
        List<String[]> out = new ArrayList<>();
        for (String[] s : all) if (!completedKeys.contains(s[1])) out.add(s);
        return out;
    }
}
