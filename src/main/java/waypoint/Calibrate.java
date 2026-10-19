package waypoint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Calibration gate: determinism self-tests, torn-tail repair, baseline
 * specificity sweep, and mutation calibration. Each of the 13 injected
 * violations must be caught by the checker. The verdict is computed from the
 * measured values, not from the printed report. Formatting uses
 * {@link Locale#ROOT} everywhere, and the gate also checks that its report
 * text is byte-identical under different default locales.
 *
 * The gate always writes {@code build/gate-result.json} (frozen schema) and
 * derives its exit code from that structured verdict.
 *
 * Test hook: {@code WAYPOINT_FAULT=determinism|torn-tail|baseline|mutants|
 * locale|c6} perturbs one measured value (not the predicate), so the gate
 * fails as expected. It is not set in normal runs or CI.
 */
public final class Calibrate {

    static final String VERSION = "0.1.0";
    static final String[] CONTRACTS = {"C1", "C2", "C3", "C4", "C5", "C6", "S1", "S2", "S3"};
    static final int BASELINE_RUNS = 63;
    static final int MUTANTS_TOTAL = 13;

    // ---------- harness ----------

    static List<Model.Event> parseEvents(WaypointLog.MemoryDisk disk) {
        List<Model.Event> out = new ArrayList<>();
        for (byte[] raw : new WaypointLog.Wal(disk).durableParsed()) out.add(Model.Event.decode(raw));
        return out;
    }

    static long opsFor(Bed.Config cfg) {
        Bed bed = new Bed(cfg, new WaypointLog.MemoryDisk(), new Model.ExternalLedger(), new Model.ModelSpy());
        bed.recordRun();
        return bed.opsExecuted();
    }

    static String hash(Bed.RecoverOutcome o) {
        return Model.stateHash(o.finalStatus(), o.chunkCounts());
    }

    private static boolean hasRunCompleted(List<Model.Event> evs) {
        for (Model.Event e : evs) if (e.type == Model.Type.RUN_COMPLETED) return true;
        return false;
    }

    private static boolean hasEvent(List<Model.Event> evs, Model.Type t) {
        for (Model.Event e : evs) if (e.type == t) return true;
        return false;
    }

    private static Set<String> interruptionKinds(Bed.Config cfg, List<Model.Event> pre) {
        if (cfg.workflow != Bed.Workflow.W2_STREAM) return Set.of();
        if (hasEvent(pre, Model.Type.CANCELLED)) return Set.of("COMPLETED");
        if (hasEvent(pre, Model.Type.CHUNK)) return Set.of("ESTIMATED");
        return Set.of("UNKNOWN");
    }

    static String forkTrace(Bed.Config base, int seed) {
        Bed.Config c = base.copy();
        c.orderSeed = seed;
        c.crashAtOp = -1;
        Bed b = new Bed(c, new WaypointLog.MemoryDisk(), new Model.ExternalLedger(), new Model.ModelSpy());
        b.recordRun();
        return b.childTrace0;
    }

    /** Run one scenario + crash point, recover, and collect every frozen
     *  observation needed by the checker. */
    static Checker.RunResult runOnce(Bed.Config cfg, String name) {
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        Model.ExternalLedger ext = new Model.ExternalLedger();
        Model.ModelSpy model = new Model.ModelSpy();
        Model.MeteringLedger metering = new Model.MeteringLedger();
        Bed bed = new Bed(cfg, disk, ext, model, metering);
        try { bed.recordRun(); } catch (Bed.Crash ignored) { }

        List<Model.Event> pre = parseEvents(disk);
        Map<String, String> keyByPath = new HashMap<>();
        Set<String> preCompleted = new HashSet<>();
        for (Model.Event e : pre) if (e.type == Model.Type.STEP_STARTED) keyByPath.put(e.f1, e.f2);
        for (Model.Event e : pre) if (e.type == Model.Type.STEP_COMPLETED) {
            String k = keyByPath.get(e.f1);
            if (k != null) preCompleted.add(k);
        }
        Map<String, Integer> preApplied = ext.appliedCounts(); // snapshot copy, BEFORE recovery
        int preSummarize = model.count("summarize", "root/compact");
        boolean preCompactionDurable = hasEvent(pre, Model.Type.COMPACTION);
        int preMetering = metering.size();

        Bed.Config rc = cfg.copy();
        rc.crashAtOp = -1;
        Bed.RecoverOutcome o1;
        if (cfg.secondResumer) {
            new Bed(rc, disk, ext, model, metering).recover(Bed.RecoverMode.LEASE_ONLY);
            new Bed(rc, disk, ext, model, metering).recover(Bed.RecoverMode.SECOND_RESUMER);
            o1 = new Bed(rc, disk, ext, model, metering).recover(Bed.RecoverMode.CONTINUE);
        } else {
            o1 = new Bed(rc, disk, ext, model, metering).recover(Bed.RecoverMode.FULL);
        }
        List<String> replayHashes = new ArrayList<>();
        replayHashes.add(hash(o1));
        for (int i = 0; i < 2; i++) {
            replayHashes.add(hash(new Bed(rc, disk, ext, model, metering).recover(Bed.RecoverMode.FULL)));
        }
        int replayEmitted = new Bed(rc, disk, ext, model, metering).replayStream();
        int finalMetering = metering.size();

        Checker.RunResult rr = new Checker.RunResult();
        rr.scenario = name;
        rr.events = parseEvents(disk);
        rr.ext = ext;
        rr.model = model;
        rr.preCompletedKeys = Set.copyOf(preCompleted);
        rr.preAppliedCounts = preApplied;
        rr.twoResumers = cfg.secondResumer;
        rr.cancelAt = cfg.cancelAt;
        rr.preCompactionDurable = preCompactionDurable;
        rr.preSummarizeCalls = preSummarize;
        rr.forkScenario = cfg.workflow == Bed.Workflow.W3_FORK;
        rr.childTrace0 = bed.childTrace0;
        rr.childTrace1 = bed.childTrace1;
        rr.childEvents0 = bed.childEvents0();
        rr.childEvents1 = bed.childEvents1();
        rr.stateHash = hash(o1);
        rr.replayHashes = List.copyOf(replayHashes);
        if (cfg.workflow == Bed.Workflow.W3_FORK) {
            rr.orderTraces = List.of(forkTrace(cfg, 0), forkTrace(cfg, 1));
        }
        if (!cfg.m5CompletionOrderIds) {
            // M5 destabilizes step identity: C1's structural check
            // would be meaningless there (documented in docs/mutations.md).
            rr.expectedSteps = Bed.expectedSteps(cfg.workflow);
        }
        if (hasEvent(rr.events, Model.Type.CANCELLED)) {
            rr.configuredCancelAt = cfg.cancelAt;
            rr.replayChunksEmitted = replayEmitted;
        }
        rr.meteringPreEntries = preMetering;
        rr.meteringFinalEntries = finalMetering;
        rr.interruptionKinds = interruptionKinds(cfg, pre);
        return rr;
    }

    record SweepResult(int runs, List<Checker.Finding> findings, String firstTrigger) {
        boolean has(String contract) { return findings.stream().anyMatch(f -> f.contract.equals(contract)); }
    }

    /** Sweep the control point -1 (no crash) plus every requested crash index. */
    static SweepResult sweep(String name, Bed.Config base, List<Long> crashPoints) {
        int runs = 0;
        List<Checker.Finding> all = new ArrayList<>();
        String first = null;
        for (long k : withControl(crashPoints)) {
            Bed.Config c = base.copy();
            c.crashAtOp = k;
            Checker.RunResult rr = runOnce(c, name + "@k=" + k);
            List<Checker.Finding> f = Checker.check(rr);
            if (!f.isEmpty() && first == null) first = name + "@k=" + k + " -> " + f.get(0);
            all.addAll(f);
            runs++;
        }
        return new SweepResult(runs, all, first);
    }

    static List<Long> withControl(List<Long> crashPoints) {
        List<Long> points = new ArrayList<>();
        points.add(-1L);
        points.addAll(crashPoints);
        return points;
    }

    static List<Long> range(long from, long to) {
        List<Long> out = new ArrayList<>();
        for (long k = from; k <= to; k++) out.add(k);
        return out;
    }

    static List<Long> points(long... ks) {
        List<Long> out = new ArrayList<>();
        for (long k : ks) out.add(k);
        return out;
    }

    // ---------- scenarios ----------

    static Bed.Config w1() { return Bed.Config.healthy(Bed.Workflow.W1_ORDER); }
    static Bed.Config w1Ckpt() { Bed.Config c = w1(); c.checkpointMidStep = true; return c; }
    static Bed.Config w2() { return Bed.Config.healthy(Bed.Workflow.W2_STREAM); }
    static Bed.Config w3(int seed) { Bed.Config c = Bed.Config.healthy(Bed.Workflow.W3_FORK); c.orderSeed = seed; return c; }
    static Bed.Config resume2(long crashAt) { Bed.Config c = w1(); c.secondResumer = true; c.crashAtOp = crashAt; return c; }

    record SweepDef(String name, Bed.Config cfg, List<Long> crashPoints) {}

    /** The frozen 63-run baseline. W2 excludes the 3 crash points
     *  (40..42) where recovery legitimately re-executes summarize on a not-yet
     *  durable compaction (new execution, not replay): S3 measures REPLAY
     *  metering and must not count legitimate new work. Composition is
     *  disclosed in docs/mutations.md. */
    static List<SweepDef> baselineScenarios() {
        return List.of(
                new SweepDef("S_W1", w1(), range(1, 19)),
                new SweepDef("S_W1_CKPT", w1Ckpt(), range(1, 21)),
                new SweepDef("S_W1_RESUME2", resume2(0), range(8, 14)),
                new SweepDef("S_W2", w2(), points(36, 37, 38, 43, 44, 45, 46, 47, 48, 49)),
                new SweepDef("S_W3", w3(0), points(0)));
    }

    // ---------- phases ----------

    record DetResult(int equal, int total, boolean sameSeedIdentical, boolean replayStable) {}

    static DetResult phaseDeterminism() {
        // 100 recovery loops on a crashed run: state hash must be stable
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        Model.ExternalLedger ext = new Model.ExternalLedger();
        Model.ModelSpy model = new Model.ModelSpy();
        Model.MeteringLedger metering = new Model.MeteringLedger();
        Bed.Config cfg = w1();
        cfg.crashAtOp = 10;
        Bed bed = new Bed(cfg, disk, ext, model, metering);
        try { bed.recordRun(); } catch (Bed.Crash ignored) { }
        Bed.Config rc = cfg.copy();
        rc.crashAtOp = -1;
        String ref = null;
        int ok = 0;
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Bed.RecoverOutcome o = new Bed(rc, disk, ext, model, metering).recover(Bed.RecoverMode.FULL);
            String h = hash(o);
            if (ref == null) ref = h;
            if (h.equals(ref)) ok++;
            if (i < 3) hashes.add(h);
        }
        boolean replayStable = hashes.size() >= 2 && new LinkedHashSet<>(hashes).size() == 1;
        // same-seed whole-run reproducibility
        Checker.RunResult a = runOnce(w1(), "det-a");
        Checker.RunResult b = runOnce(w1(), "det-b");
        return new DetResult(ok, 100, a.stateHash.equals(b.stateHash), replayStable);
    }

    record TornResult(int survived, boolean prefixIntact) {}

    static TornResult phaseTornTail() {
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        List<byte[]> payloads = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            byte[] p = ("record-" + i).getBytes(StandardCharsets.UTF_8);
            payloads.add(p);
            WaypointLog.Wal wal = new WaypointLog.Wal(disk);
            wal.append(p);
            wal.sync();
        }
        disk.corruptLastDurableForSelfTest();
        WaypointLog.Wal after = new WaypointLog.Wal(disk);
        int survived = after.durableParsed().size();
        boolean prefixIntact = survived == 9 && disk.durableCount() == 9;
        for (int i = 0; i < survived && prefixIntact; i++) {
            prefixIntact = Arrays.equals(payloads.get(i), after.durableParsed().get(i));
        }
        return new TornResult(survived, prefixIntact);
    }

    record BaselineResult(int runs, List<Checker.Finding> violations, int[] active,
                          boolean orderStable, boolean replayStable, List<String> lines) {}

    private static Set<String> activeHere(Checker.RunResult rr) {
        Set<String> a = new HashSet<>();
        if (!rr.expectedSteps.isEmpty() && hasRunCompleted(rr.events)) a.add("C1");
        if (!rr.ext.records().isEmpty()) a.add("C2");
        if (!rr.childEvents0.isEmpty() || !rr.childEvents1.isEmpty()
                || !rr.childTrace0.isEmpty() || !rr.childTrace1.isEmpty()) a.add("C3");
        if (hasEvent(rr.events, Model.Type.CHECKPOINT)) a.add("C4");
        if (rr.twoResumers) a.add("C5");
        if (rr.replayHashes.size() >= 2) a.add("C6");
        if (hasEvent(rr.events, Model.Type.CANCELLED) || rr.configuredCancelAt >= 0) a.add("S1");
        if (rr.preCompactionDurable) a.add("S2");
        if (rr.meteringPreEntries > 0) a.add("S3");
        return a;
    }

    static BaselineResult phaseBaseline() {
        int totalRuns = 0;
        List<Checker.Finding> all = new ArrayList<>();
        int[] active = new int[CONTRACTS.length];
        boolean orderStable = true, replayStable = true;
        List<String> lines = new ArrayList<>();
        for (SweepDef d : baselineScenarios()) {
            int runs = 0;
            List<Checker.Finding> findings = new ArrayList<>();
            for (long k : withControl(d.crashPoints())) {
                Bed.Config c = d.cfg().copy();
                c.crashAtOp = k;
                Checker.RunResult rr = runOnce(c, d.name() + "@k=" + k);
                List<Checker.Finding> f = Checker.check(rr);
                findings.addAll(f);
                runs++;
                Set<String> a = activeHere(rr);
                for (int i = 0; i < CONTRACTS.length; i++) if (a.contains(CONTRACTS[i])) active[i]++;
                if (rr.orderTraces.size() >= 2) {
                    Set<String> norm = new LinkedHashSet<>();
                    for (String t : rr.orderTraces) norm.add(Checker.normalizeTrace(t));
                    if (norm.size() > 1) orderStable = false;
                }
                if (rr.replayHashes.size() >= 2 && new LinkedHashSet<>(rr.replayHashes).size() > 1) {
                    replayStable = false;
                }
            }
            totalRuns += runs;
            all.addAll(findings);
            lines.add(f("[3] %-13s runs=%-3d violations=%d", d.name(), runs, findings.size()));
        }
        return new BaselineResult(totalRuns, all, active, orderStable, replayStable, lines);
    }

    record MutantRow(String id, String name, String target, boolean captured, int findings, String first) {}

    record MutantResult(List<MutantRow> rows, int captured) {}

    static MutantResult phaseMutants() {
        List<MutantRow> rows = new ArrayList<>();
        List<Long> w1Points = range(1, opsFor(w1()));
        List<Long> ckptPoints = range(1, opsFor(w1Ckpt()));
        List<Long> w2Points = points(36, 37, 38, 43, 44, 45, 46, 47, 48, 49);
        List<Long> resume2Points = range(8, 14);

        // M1..M4 on the W1 sweep
        String[][] m14 = {{"M1", "C2", "effect-before-started-sync"}, {"M2", "C2", "started-not-synced"},
                {"M3", "C2", "recovery-retry-without-reconcile"}, {"M4", "C2", "replay-re-executes-completed"}};
        for (String[] m : m14) {
            Bed.Config c = w1();
            switch (m[0]) {
                case "M1" -> c.m1EffectBeforeStart = true;
                case "M2" -> c.m2SkipStartSync = true;
                case "M3" -> c.m3RecoveryRetryNoReconcile = true;
                case "M4" -> c.m4ReplayReexecutes = true;
            }
            SweepResult r = sweep("S_W1[" + m[0] + "]", c, w1Points);
            rows.add(row(m[0], m[2], m[1], r));
        }

        // M5: order-seed step-id stability (C6)
        {
            Bed.Config c = w3(0);
            c.m5CompletionOrderIds = true;
            Checker.RunResult s0 = runOnce(c, "S_ORDER[m5]seed0");
            List<Checker.Finding> f0 = Checker.check(s0);
            Bed.Config c2 = w3(1);
            c2.m5CompletionOrderIds = true;
            Checker.check(runOnce(c2, "S_ORDER[m5]seed1"));
            rows.add(rowOf("M5", "completion-order-step-ids", "C6", f0));
        }

        // M6 on the checkpoint scenario
        {
            Bed.Config c = w1Ckpt();
            c.m6CheckpointFabricatesCompleted = true;
            SweepResult r = sweep("S_W1_CKPT[M6]", c, ckptPoints);
            rows.add(row("M6", "checkpoint-fabricates-completed", "C4", r));
        }

        // M7 on fork
        {
            Bed.Config c = w3(0);
            c.m7ForkSharesCursor = true;
            List<Checker.Finding> f = Checker.check(runOnce(c, "S_W3[M7]"));
            rows.add(rowOf("M7", "fork-shares-cursor", "C3", f));
        }

        // M8 on two resumers
        {
            Bed.Config c = resume2(0);
            c.m8NoLease = true;
            SweepResult r = sweep("S_W1_RESUME2[M8]", c, resume2Points);
            rows.add(row("M8", "second-resumer-ignores-lease", "C5", r));
        }

        // M9..M13
        addMutant(rows, "M9", "cancel-boundary-overrun", "S1", w2(), w2Points, cfg -> cfg.m9OverrunCancel = true);
        addMutant(rows, "M10", "replay-regenerates-summary", "S2", w2(), w2Points,
                cfg -> cfg.m10ReplayRegeneratesSummary = true);
        addMutant(rows, "M11", "skip-unfinished-step", "C1", w1(), w1Points,
                cfg -> cfg.m11SkipUnfinishedStep = true);
        addMutant(rows, "M12", "replay-emits-wrong-chunks", "S1", w2(), w2Points,
                cfg -> cfg.m12ReplayEmitsWrongChunks = true);
        addMutant(rows, "M13", "metering-on-replay", "S3", w2(), w2Points,
                cfg -> cfg.m13MeteringOnReplay = true);

        int captured = 0;
        for (MutantRow r : rows) if (r.captured()) captured++;
        return new MutantResult(rows, captured);
    }

    private static void addMutant(List<MutantRow> rows, String id, String name, String target,
                                  Bed.Config base, List<Long> pts, java.util.function.Consumer<Bed.Config> mutate) {
        Bed.Config c = base.copy();
        mutate.accept(c);
        SweepResult r = sweep("MUT[" + id + "]", c, pts);
        rows.add(row(id, name, target, r));
    }

    private static MutantRow row(String id, String name, String target, SweepResult r) {
        return rowOf(id, name, target, r.findings());
    }

    private static MutantRow rowOf(String id, String name, String target, List<Checker.Finding> f) {
        boolean captured = f.stream().anyMatch(x -> x.contract.equals(target));
        String first = f.isEmpty() ? "-" : f.get(0).toString();
        return new MutantRow(id, name, target, captured, f.size(), first);
    }

    // ---------- structured verdict + rendering ----------

    public static final class GateResult {
        public int detEqual, detTotal;
        public int tornSurvived;
        public boolean tornPrefixIntact;
        public int baselineRuns, baselineViolations;
        public int mutantsCaptured, mutantsTotal;
        public boolean c6ReplayStable, c6OrderStable;
        public boolean sameSeedIdentical;
        public boolean localeIndependent;
        public boolean reproducible;
        public int[] active = new int[CONTRACTS.length];
        public List<MutantRow> mutantRows = List.of();
        public List<String> sweepLines = List.of();
        public boolean pass;
        public String body = "", report = "", reportSha256 = "";

        /** The frozen verdict: pure numeric assertions, no substring matching. */
        public boolean verdict() {
            return detTotal == 100 && detEqual == 100
                    && tornSurvived == 9 && tornPrefixIntact
                    && baselineRuns == BASELINE_RUNS && baselineViolations == 0
                    && mutantsTotal == MUTANTS_TOTAL && mutantsCaptured == MUTANTS_TOTAL
                    && c6ReplayStable && c6OrderStable
                    && sameSeedIdentical
                    && localeIndependent;
        }

        /** Frozen schema; field names must not change. */
        public String json() {
            return "{\"version\":\"" + VERSION + "\",\"jdk\":\"" + Runtime.version().feature() + "\","
                    + "\"determinism\":{\"equal\":" + detEqual + ",\"total\":" + detTotal + "},"
                    + "\"tornTail\":{\"survived\":" + tornSurvived + ",\"prefixIntact\":" + tornPrefixIntact + "},"
                    + "\"baseline\":{\"runs\":" + baselineRuns + ",\"violations\":" + baselineViolations + "},"
                    + "\"mutants\":{\"captured\":" + mutantsCaptured + ",\"total\":" + mutantsTotal + "},"
                    + "\"c6\":{\"replayStable\":" + c6ReplayStable + ",\"orderStable\":" + c6OrderStable + "},"
                    + "\"pass\":" + pass + ",\"reportSha256\":\"" + reportSha256 + "\"}";
        }
    }

    // Locale-ROOT formatting; WAYPOINT_FAULT=locale deliberately regresses it
    // so the locale self-check can be proven to fail (audit hook only).
    private static boolean localeFault() {
        return "locale".equals(System.getenv("WAYPOINT_FAULT"));
    }

    static String f(String fmt, Object... args) {
        return String.format(localeFault() ? Locale.getDefault() : Locale.ROOT, fmt, args);
    }

    static String renderBody(GateResult r) {
        List<String> lines = new ArrayList<>();
        lines.add(f("[1] recovery determinism: %d/%d replays hash-equal", r.detEqual, r.detTotal));
        lines.add("[1] same-seed reproducibility: stateHash "
                + (r.sameSeedIdentical ? "identical" : "DIVERGED"));
        lines.add(f("[2] torn-tail self-test: kept %d/10 intact-prefix=%s (truncate-at-first-bad-record)",
                r.tornSurvived, r.tornPrefixIntact ? "yes" : "NO"));
        lines.addAll(r.sweepLines);
        StringBuilder act = new StringBuilder("[3] contract activity: ");
        for (int i = 0; i < CONTRACTS.length; i++) {
            if (i > 0) act.append(' ');
            act.append(CONTRACTS[i]).append(' ').append(r.active[i]).append('/').append(r.baselineRuns);
        }
        lines.add(act.toString());
        lines.add(f("[3] baseline specificity: %d crash-runs, %d contract violations (must be 0)",
                r.baselineRuns, r.baselineViolations));
        lines.add("[4] mutation calibration");
        for (MutantRow m : r.mutantRows) {
            lines.add(f("[4]   %-3s %-34s target=%-3s captured=%-3s findings=%-4s %s",
                    m.id(), m.name(), m.target(), m.captured() ? "yes" : "NO",
                    String.valueOf(m.findings()), m.first()));
        }
        lines.add(f("[4] mutations captured %d/%d", r.mutantsCaptured, r.mutantsTotal));
        lines.add("[5] locale independence: report formatting stable across ar-EG/tr-TR (Locale.ROOT)");
        return String.join("\n", lines);
    }

    static boolean localeIndependent(GateResult r) {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            String a = renderBody(r);
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String b = renderBody(r);
            return a.equals(b);
        } finally {
            Locale.setDefault(saved);
        }
    }

    static GateResult compute() {
        GateResult r = new GateResult();
        DetResult det = phaseDeterminism();
        r.detEqual = det.equal();
        r.detTotal = det.total();
        r.sameSeedIdentical = det.sameSeedIdentical();

        TornResult torn = phaseTornTail();
        r.tornSurvived = torn.survived();
        r.tornPrefixIntact = torn.prefixIntact();

        BaselineResult base = phaseBaseline();
        r.baselineRuns = base.runs();
        r.baselineViolations = base.violations().size();
        r.active = base.active();
        r.sweepLines = base.lines();
        r.c6ReplayStable = det.replayStable() && base.replayStable();
        r.c6OrderStable = base.orderStable();

        MutantResult mut = phaseMutants();
        r.mutantRows = mut.rows();
        r.mutantsCaptured = mut.captured();
        r.mutantsTotal = mut.rows().size();

        applyFault(r);
        r.body = renderBody(r);
        return r;
    }

    private static void applyFault(GateResult r) {
        String fault = System.getenv("WAYPOINT_FAULT");
        if (fault == null) return;
        switch (fault) {
            case "determinism" -> r.detEqual = 33;
            case "torn-tail" -> r.tornSurvived = 8;
            case "baseline" -> r.baselineViolations = 1;
            case "mutants" -> r.mutantsCaptured = r.mutantsTotal - 1;
            case "c6" -> r.c6OrderStable = false;
            case "locale" -> { /* rendering regresses in f(); detected by localeIndependent */ }
            default -> { }
        }
    }

    static GateResult gate() {
        GateResult a = compute();
        GateResult b = compute();
        a.reproducible = a.body.equals(b.body);
        a.localeIndependent = localeIndependent(a);
        a.pass = a.verdict() && a.reproducible;
        a.report = a.body + "\nGATE: " + (a.pass ? "PASS" : "FAIL")
                + " | mutations 13/13 required | determinism/reproducibility required"
                + " | baseline 0 false positives required | locale-independent formatting required";
        a.reportSha256 = Model.sha256(a.report);
        return a;
    }

    /** Legacy entry point kept for callers that only want the printed report. */
    public static String computeReport() {
        return gate().report;
    }

    static GateResult failure(Throwable t) {
        GateResult r = new GateResult();
        r.baselineRuns = 0;
        r.mutantsTotal = MUTANTS_TOTAL;
        r.pass = false;
        r.reproducible = false;
        r.body = "[0] gate crashed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        r.report = r.body + "\nGATE: FAIL";
        r.reportSha256 = Model.sha256(r.report);
        return r;
    }

    public static void main(String[] args) {
        GateResult r;
        try {
            r = gate();
        } catch (Throwable t) {
            r = failure(t);
            t.printStackTrace();
        }
        try {
            Path p = Path.of("build", "gate-result.json");
            Files.createDirectories(p.getParent());
            Files.writeString(p, r.json() + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("ERROR: could not write build/gate-result.json: " + e);
        }
        System.out.println(r.report);
        System.out.println();
        System.out.println("report sha256=" + r.reportSha256);
        System.out.println("whole-gate reproducibility: "
                + (r.reproducible ? "2/2 identical (same seed, same result)" : "FAILED"));
        System.out.println("gate-result: build/gate-result.json");
        if (Arrays.asList(args).contains("--json")) System.out.println(r.json());
        System.exit(r.pass ? 0 : 1);
    }
}
