package waypoint;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash-model semantics: recovery discards the unsynced tail, torn-tail repair
 * truncates the durable region and rebases record indexes, recovery is
 * idempotent (RUN_COMPLETED written once), and the replay path calls no model
 * and writes no new metering.
 */
class BedCrashTest {

    private static List<Model.Event> parse(WaypointLog.MemoryDisk d) {
        List<Model.Event> out = new ArrayList<>();
        for (byte[] raw : new WaypointLog.Wal(d).durableParsed()) out.add(Model.Event.decode(raw));
        return out;
    }

    @Test
    void crashAtIndex7DiscardsUnsyncedCompletionAndDoesNotReviveOkRecord() {
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        Model.ExternalLedger ext = new Model.ExternalLedger();
        Model.ModelSpy spy = new Model.ModelSpy();
        Model.MeteringLedger metering = new Model.MeteringLedger();
        Bed.Config cfg = Bed.Config.healthy(Bed.Workflow.W1_ORDER);
        cfg.crashAtOp = 7; // right before the sync that would make charge COMPLETED durable
        try { new Bed(cfg, disk, ext, spy, metering).recordRun(); } catch (Bed.Crash expected) { }

        assertTrue(parse(disk).stream().noneMatch(e -> e.type == Model.Type.STEP_COMPLETED),
                "the unsynced STEP_COMPLETED must not be durable before recovery");

        Bed.Config rc = cfg.copy();
        rc.crashAtOp = -1;
        new Bed(rc, disk, ext, spy, metering).recover(Bed.RecoverMode.FULL);

        List<Model.Event> after = parse(disk);
        assertEquals(1, after.stream().filter(e -> e.type == Model.Type.RUN_COMPLETED).count(),
                "RUN_COMPLETED must be written exactly once");
        Map<String, Integer> completions = new HashMap<>();
        for (Model.Event e : after) {
            if (e.type == Model.Type.STEP_COMPLETED) completions.merge(e.f1, 1, Integer::sum);
        }
        completions.forEach((path, n) -> assertEquals(1, n, "duplicate STEP_COMPLETED for " + path));
        assertFalse(after.stream().anyMatch(e -> "ok:k_charge".equals(e.f2)),
                "the unsynced completion record must not be revived");
        assertTrue(after.stream().anyMatch(e -> "ok:k_charge:reconciled".equals(e.f2)),
                "recovery reconciles via the external ledger");
        assertEquals(Map.of("k_charge", 1, "k_notify", 1, "k_report", 1), ext.appliedCounts());
    }

    @Test
    void recoveryIsIdempotent() {
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        Model.ExternalLedger ext = new Model.ExternalLedger();
        Model.ModelSpy spy = new Model.ModelSpy();
        Model.MeteringLedger metering = new Model.MeteringLedger();
        Bed.Config cfg = Bed.Config.healthy(Bed.Workflow.W1_ORDER);
        cfg.crashAtOp = 5;
        try { new Bed(cfg, disk, ext, spy, metering).recordRun(); } catch (Bed.Crash expected) { }

        Bed.Config rc = cfg.copy();
        rc.crashAtOp = -1;
        Bed.RecoverOutcome first = new Bed(rc, disk, ext, spy, metering).recover(Bed.RecoverMode.FULL);
        int len = parse(disk).size();
        assertEquals(1, parse(disk).stream().filter(e -> e.type == Model.Type.RUN_COMPLETED).count());
        for (int i = 0; i < 3; i++) new Bed(rc, disk, ext, spy, metering).recover(Bed.RecoverMode.FULL);
        assertEquals(len, parse(disk).size(), "repeated recovery must not append records");
        Bed.RecoverOutcome last = new Bed(rc, disk, ext, spy, metering).recover(Bed.RecoverMode.FULL);
        assertEquals(Calibrate.hash(first), Calibrate.hash(last), "repeated recovery must hash identically");
    }

    @Test
    void tornTailIsTruncatedAndRecordIndexesRebased() {
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        for (int i = 1; i <= 10; i++) {
            byte[] p = ("record-" + i).getBytes(StandardCharsets.UTF_8);
            WaypointLog.Wal w = new WaypointLog.Wal(disk);
            assertEquals(disk.durableCount(), w.append(p), "index == durable prefix length");
            w.sync();
        }
        disk.corruptLastDurableForSelfTest();
        WaypointLog.Wal wal = new WaypointLog.Wal(disk);
        assertEquals(9, wal.durableParsed().size());
        assertEquals(9, disk.durableCount(), "bad durable suffix must be truncated, not just ignored");
        long idx = wal.append("after".getBytes(StandardCharsets.UTF_8));
        wal.sync();
        assertEquals(9, idx, "indexes rebase onto the durable prefix");
        assertEquals(10, wal.durableParsed().size());
    }

    @Test
    void discardUnsyncedDropsBufferedTail() {
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        WaypointLog.Wal wal = new WaypointLog.Wal(disk);
        wal.append(new byte[]{1});
        wal.sync();
        wal.append(new byte[]{2}); // never synced
        assertEquals(1, disk.durableCount());
        assertEquals(1, wal.frontier()); // one buffered record is not durable
        assertEquals(2, wal.appends());  // ... but its index was handed out
        disk.discardUnsynced();
        assertEquals(1, disk.durableCount());
        WaypointLog.Wal after = new WaypointLog.Wal(disk);
        assertEquals(1, after.durableParsed().size());
        assertEquals(1, after.frontier());
    }

    @Test
    void replayStreamEmitsExactlyKWithoutModelCallsOrMetering() {
        WaypointLog.MemoryDisk disk = new WaypointLog.MemoryDisk();
        Model.ExternalLedger ext = new Model.ExternalLedger();
        Model.ModelSpy spy = new Model.ModelSpy();
        Model.MeteringLedger metering = new Model.MeteringLedger();
        Bed.Config cfg = Bed.Config.healthy(Bed.Workflow.W2_STREAM);
        new Bed(cfg, disk, ext, spy, metering).recordRun();
        int genCalls = spy.count("gen", "root/gen");
        int meteringBefore = metering.size();
        int emitted = new Bed(cfg, disk, ext, spy, metering).replayStream();
        assertEquals(cfg.cancelAt, emitted);
        assertEquals(genCalls, spy.count("gen", "root/gen"), "replay must not call the model");
        assertEquals(meteringBefore, metering.size(), "replay must not create metering entries");
    }

    @Test
    void calibrationBaselineHasNoLogIntegrityViolations() {
        for (Calibrate.SweepDef d : Calibrate.baselineScenarios()) {
            for (long k : Calibrate.withControl(d.crashPoints())) {
                Bed.Config c = d.cfg().copy();
                c.crashAtOp = k;
                Checker.RunResult rr = Calibrate.runOnce(c, d.name() + "@k=" + k);
                List<Checker.Finding> findings = Checker.check(rr);
                List<String> l1 = new ArrayList<>();
                for (Checker.Finding f : findings) if (f.contract.equals("L1")) l1.add(f.toString());
                assertTrue(l1.isEmpty(), d.name() + "@k=" + k + " L1: " + l1);
            }
        }
    }
}
