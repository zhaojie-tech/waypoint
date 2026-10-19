package waypoint;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract-by-contract unit tests for {@link Checker}: every rule must fire on
 * its targeted synthetic observation, stay silent on the healthy control, and
 * mark unobservable contracts instead of silently reporting "clean".
 */
class CoreContractTest {

    private static Model.Event ev(Model.Type t, String f1, String f2) {
        return new Model.Event(t, f1, f2, null, null);
    }

    private static Checker.RunResult rr() {
        Checker.RunResult r = new Checker.RunResult();
        r.events = new ArrayList<>();
        r.ext = new Model.ExternalLedger();
        r.model = new Model.ModelSpy();
        r.preCompletedKeys = Set.of();
        r.preAppliedCounts = Map.of();
        return r;
    }

    private static Set<String> contracts(List<Checker.Finding> fs) {
        Set<String> out = new LinkedHashSet<>();
        for (Checker.Finding f : fs) out.add(f.contract);
        return out;
    }

    private static List<String> details(List<Checker.Finding> fs, String contract) {
        List<String> out = new ArrayList<>();
        for (Checker.Finding f : fs) if (f.contract.equals(contract)) out.add(f.detail);
        return out;
    }

    // ---------- C1 ----------

    @Test
    void c1DetectsSkippedUnfinishedStep() {
        Checker.RunResult r = rr();
        r.expectedSteps = List.of("root/a", "root/b");
        r.events = List.of(ev(Model.Type.RUN_STARTED, "W1", "v1"),
                ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.STEP_COMPLETED, "root/a", "ok:k_a"),
                ev(Model.Type.RUN_COMPLETED, null, null));
        List<Checker.Finding> f = Checker.check(r);
        assertEquals(List.of("skipped unfinished step root/b"), details(f, "C1"));
    }

    @Test
    void c1IsSilentWhenEveryExpectedStepCompleted() {
        Checker.RunResult r = rr();
        r.expectedSteps = List.of("root/a");
        r.events = List.of(ev(Model.Type.RUN_STARTED, "W1", "v1"),
                ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.STEP_COMPLETED, "root/a", "ok:k_a"),
                ev(Model.Type.RUN_COMPLETED, null, null));
        assertTrue(details(Checker.check(r), "C1").isEmpty());
    }

    @Test
    void c1DetectsReExecutionOfCompletedStep() {
        Checker.RunResult r = rr();
        r.preCompletedKeys = Set.of("k_a");
        r.preAppliedCounts = Map.of(); // count increases from 0 -> 1 for an already-completed key
        r.ext.apply("k_a", 1, 2);
        assertTrue(contracts(Checker.check(r)).contains("C1"));
    }

    // ---------- C2 ----------

    @Test
    void c2DetectsDuplicateExternalEffect() {
        Checker.RunResult r = rr();
        r.ext.apply("k_a", 1, 2);
        r.ext.apply("k_a", 1, 2);
        assertTrue(contracts(Checker.check(r)).contains("C2"));
    }

    @Test
    void c2ProtocolOrderingIsSkippedWhenUnobservable() {
        Checker.RunResult r = rr();
        r.ext.apply("k_a", -1, 0); // would be a C2 ordering violation
        List<Checker.Finding> visible = Checker.check(r);
        assertTrue(contracts(visible).contains("C2"));
        assertFalse(r.unobservable.contains("C2"));

        Checker.RunResult r2 = rr();
        r2.ext.apply("k_a", -1, 0);
        r2.protocolObservable = false;
        List<Checker.Finding> hidden = Checker.check(r2);
        assertFalse(contracts(hidden).contains("C2"), "ordering must not be faked as clean or violated");
        assertTrue(r2.unobservable.contains("C2"));
    }

    @Test
    void c2HonorsAdapterDeclaredUnobservable() {
        Checker.RunResult r = rr();
        r.unobservable = Set.of("C2");
        r.ext.apply("k_a", 1, 2);
        r.ext.apply("k_a", 1, 2);
        assertTrue(Checker.check(r).isEmpty(), "adapter declared C2 unobservable -> skip, not clean");
        assertTrue(r.unobservable.contains("C2"));
    }

    // ---------- C3 ----------

    @Test
    void c3ComparesChildEventStreamsWithChildKeyMasking() {
        Checker.RunResult same = rr();
        same.childEvents0 = List.of(ev(Model.Type.STEP_COMPLETED, "root/x", "ok:k_x#c0"));
        same.childEvents1 = List.of(ev(Model.Type.STEP_COMPLETED, "root/x", "ok:k_x#c1"));
        assertTrue(details(Checker.check(same), "C3").isEmpty(), "per-child key suffix is masked");

        Checker.RunResult diff = rr();
        diff.childEvents0 = List.of(ev(Model.Type.STEP_COMPLETED, "root/x", "ok:k_x#c0"));
        diff.childEvents1 = List.of(ev(Model.Type.STEP_COMPLETED, "root/y", "ok:k_y#c1"));
        assertFalse(details(Checker.check(diff), "C3").isEmpty());
    }

    @Test
    void c3WithoutAnyEvidenceIsUnobservable() {
        Checker.RunResult r = rr();
        List<Checker.Finding> f = Checker.check(r);
        assertTrue(details(f, "C3").isEmpty());
        assertTrue(r.unobservable.contains("C3"));
    }

    // ---------- C4 ----------

    @Test
    void c4ParsesEveryStatusInMultiStepCheckpoint() {
        Checker.RunResult r = rr();
        r.events = List.of(
                ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.STEP_COMPLETED, "root/a", "ok:k_a"),
                new Model.Event(Model.Type.CHECKPOINT, "w1", "root/a=COMPLETED;root/b=COMPLETED", null, null));
        List<String> d = details(Checker.check(r), "C4");
        assertEquals(1, d.size());
        assertTrue(d.get(0).contains("root/b"), d.get(0));
    }

    @Test
    void c4SilentWhenAllClaimedCompletionsAreInPrefix() {
        Checker.RunResult r = rr();
        r.events = List.of(
                ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.STEP_COMPLETED, "root/a", "ok:k_a"),
                ev(Model.Type.STEP_STARTED, "root/b", "k_b"),
                new Model.Event(Model.Type.CHECKPOINT, "w1", "root/a=COMPLETED;root/b=STARTED", null, null));
        assertTrue(details(Checker.check(r), "C4").isEmpty());
    }

    // ---------- C5 ----------

    @Test
    void c5DetectsTwoDistinctExecutorsForOneKey() {
        Checker.RunResult r = rr();
        r.twoResumers = true;
        r.events = List.of(ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.LEASED, "root/a", "resumer1"));
        r.ext.apply("k_a", 0, 1, "runner");
        r.ext.apply("k_a", 0, 1, "resumer2");
        assertFalse(details(Checker.check(r), "C5").isEmpty());
    }

    @Test
    void c5DetectsEffectByNonLeaseOwner() {
        Checker.RunResult r = rr();
        r.twoResumers = true;
        r.events = List.of(ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.LEASED, "root/a", "resumer1"));
        r.ext.apply("k_a", 0, 2, "resumer2"); // frontier 2 > lease index 1
        List<String> d = details(Checker.check(r), "C5");
        assertTrue(d.stream().anyMatch(x -> x.contains("lease was owned by resumer1")), d.toString());
    }

    @Test
    void c5WithoutLeaseOrExecutorEvidenceIsUnobservable() {
        Checker.RunResult r = rr();
        r.twoResumers = true;
        r.ext.apply("k_a", 0, 1); // default "runner" only, no LEASED event
        r.events = List.of(ev(Model.Type.STEP_STARTED, "root/a", "k_a"));
        assertTrue(details(Checker.check(r), "C5").isEmpty());
        assertTrue(r.unobservable.contains("C5"));
    }

    // ---------- C6 ----------

    @Test
    void c6DetectsUnstableReplayHash() {
        Checker.RunResult r = rr();
        r.replayHashes = List.of("h1", "h2", "h1");
        assertFalse(details(Checker.check(r), "C6").isEmpty());
    }

    @Test
    void c6DetectsOrderDependentTraceMultiset() {
        Checker.RunResult bad = rr();
        bad.orderTraces = List.of("step s1\nstep s2", "step s1\nstep s3");
        assertFalse(details(Checker.check(bad), "C6").isEmpty());

        Checker.RunResult ok = rr();
        ok.orderTraces = List.of("step s2\nstep s1", "step s1\nstep s2");
        assertTrue(details(Checker.check(ok), "C6").isEmpty(), "line order is normalized before compare");
    }

    // ---------- S1 ----------

    @Test
    void s1ChecksChunkCountConfiguredKAndReplayCount() {
        Checker.RunResult r = rr();
        r.events = List.of(
                ev(Model.Type.CHUNK, "root/gen", "1"),
                ev(Model.Type.CHUNK, "root/gen", "2"),
                ev(Model.Type.CHUNK, "root/gen", "3"),
                ev(Model.Type.CANCELLED, "root/gen", "3"));
        r.configuredCancelAt = 3;
        r.replayChunksEmitted = 3;
        assertTrue(details(Checker.check(r), "S1").isEmpty(), "healthy cancel boundary");

        r.configuredCancelAt = 5;
        assertTrue(details(Checker.check(r), "S1").stream().anyMatch(x -> x.contains("configured cancelAt=5")));

        r.configuredCancelAt = 3;
        r.replayChunksEmitted = 4;
        assertTrue(details(Checker.check(r), "S1").stream().anyMatch(x -> x.contains("replay emitted 4")));
    }

    // ---------- S2 / S3 ----------

    @Test
    void s2DetectsReSummarizeAfterDurableCompaction() {
        Checker.RunResult r = rr();
        r.preCompactionDurable = true;
        r.preSummarizeCalls = 1;
        r.model.call("summarize", "root/compact");
        r.model.call("summarize", "root/compact");
        assertFalse(details(Checker.check(r), "S2").isEmpty());
    }

    @Test
    void s3DetectsNewMeteringOnReplayAndIllegalInterruptionKind() {
        Checker.RunResult r = rr();
        r.meteringPreEntries = 2;
        r.meteringFinalEntries = 3;
        r.interruptionKinds = Set.of("COMPLETED");
        assertTrue(details(Checker.check(r), "S3").stream().anyMatch(x -> x.contains("new metering entries")));

        Checker.RunResult bad = rr();
        bad.meteringPreEntries = 1;
        bad.meteringFinalEntries = 1;
        bad.interruptionKinds = Set.of("MAYBE");
        assertTrue(details(Checker.check(bad), "S3").stream().anyMatch(x -> x.contains("illegal interruption")));

        Checker.RunResult ok = rr();
        ok.meteringPreEntries = 2;
        ok.meteringFinalEntries = 2;
        ok.interruptionKinds = Set.of("COMPLETED", "ESTIMATED", "UNKNOWN");
        assertTrue(details(Checker.check(ok), "S3").isEmpty());
    }

    @Test
    void s3InactiveWithoutMeteringEvidenceAndMarkedUnobservable() {
        Checker.RunResult r = rr();
        assertTrue(details(Checker.check(r), "S3").isEmpty());
        assertTrue(r.unobservable.contains("S3"));
    }

    // ---------- L1 ----------

    @Test
    void l1DetectsDuplicateCompletionMissingStartAndDuplicateRunCompletion() {
        Checker.RunResult r = rr();
        r.events = List.of(
                ev(Model.Type.STEP_COMPLETED, "root/a", "ok:k_a"), // no STARTED
                ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.STEP_COMPLETED, "root/a", "ok:k_a"),  // duplicate
                ev(Model.Type.RUN_COMPLETED, null, null),
                ev(Model.Type.RUN_COMPLETED, null, null));
        List<String> l1 = details(Checker.check(r), "L1");
        assertEquals(3, l1.size(), l1.toString());
    }

    // ---------- empty evidence / ordering edge cases ----------

    @Test
    void emptyLedgerWithoutExpectedStepsMakesC1AndC2Unobservable() {
        Checker.RunResult r = rr();
        r.events = List.of(ev(Model.Type.RUN_STARTED, "W1", "v1"));
        List<Checker.Finding> fs = Checker.check(r);
        assertTrue(fs.isEmpty(), fs.toString());
        assertTrue(r.unobservable.contains("C1"), r.unobservable.toString());
        assertTrue(r.unobservable.contains("C2"), r.unobservable.toString());
    }

    @Test
    void emptyLedgerKeepsC1SkipRuleActiveButMarksC1Partial() {
        Checker.RunResult r = rr();
        r.events = List.of(ev(Model.Type.RUN_STARTED, "W1", "v1"),
                ev(Model.Type.STEP_STARTED, "root/a", "k_a"),
                ev(Model.Type.STEP_COMPLETED, "root/a", "ok:k_a"),
                ev(Model.Type.RUN_COMPLETED, null, null));
        r.expectedSteps = List.of("root/a", "root/b");
        List<Checker.Finding> fs = Checker.check(r);
        assertEquals(List.of("skipped unfinished step root/b"), details(fs, "C1"), fs.toString());
        assertFalse(r.unobservable.contains("C1"), r.unobservable.toString());
        assertTrue(r.partial.contains("C1"), r.partial.toString());
        assertTrue(r.unobservable.contains("C2"), r.unobservable.toString());
    }

    @Test
    void s1DetectsChunksWrittenAfterTheCancellationBoundary() {
        Checker.RunResult r = rr();
        r.events = List.of(
                ev(Model.Type.CHUNK, "root/gen", "1"),
                ev(Model.Type.CHUNK, "root/gen", "2"),
                ev(Model.Type.CANCELLED, "root/gen", "3"),
                ev(Model.Type.CHUNK, "root/gen", "3"));
        List<String> s1 = details(Checker.check(r), "S1");
        assertTrue(s1.stream().anyMatch(x -> x.contains("after the cancellation boundary")),
                "S1 must reject chunks recorded after CANCELLED: " + s1);
    }

    // ---------- healthy integration through the real bed ----------

    @Test
    void healthyBedRunProducesNoFindings() {
        Checker.RunResult r = Calibrate.runOnce(Calibrate.w1(), "healthy-w1");
        assertTrue(Checker.check(r).isEmpty(), "healthy W1 recovery must be clean");
    }

    @Test
    void healthyBedFillsFrozenObservationsInsteadOfFakingThem() {
        Checker.RunResult r = Calibrate.runOnce(Calibrate.w1(), "obs-w1");
        assertEquals(List.of("root/charge", "root/notify", "root/report"), r.expectedSteps);
        assertEquals(3, r.replayHashes.size());
        assertEquals(1, new LinkedHashSet<>(r.replayHashes).size());
        assertTrue(Checker.check(r).isEmpty());
        assertTrue(r.unobservable.contains("C3"), "W1 has no fork evidence");
    }
}
