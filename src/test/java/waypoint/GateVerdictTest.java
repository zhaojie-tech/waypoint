package waypoint;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate-verdict tests. The PASS decision is a numeric assertion, so every
 * degradation class (determinism, torn-tail, baseline false positives, mutation
 * capture, locale) has to turn the verdict red, and the end-to-end exit code
 * has to follow the verdict.
 */
class GateVerdictTest {

    private static Calibrate.GateResult healthy() {
        Calibrate.GateResult r = new Calibrate.GateResult();
        r.detEqual = 100;
        r.detTotal = 100;
        r.tornSurvived = 9;
        r.tornPrefixIntact = true;
        r.baselineRuns = 63;
        r.baselineViolations = 0;
        r.mutantsCaptured = 13;
        r.mutantsTotal = 13;
        r.c6ReplayStable = true;
        r.c6OrderStable = true;
        r.sameSeedIdentical = true;
        r.localeIndependent = true;
        return r;
    }

    @Test
    void healthyNumbersPass() {
        assertTrue(healthy().verdict());
    }

    @Test
    void determinismDegradationFails() {
        Calibrate.GateResult r = healthy();
        r.detEqual = 33;
        assertFalse(r.verdict());
        r = healthy();
        r.detTotal = 99;
        assertFalse(r.verdict());
    }

    @Test
    void tornTailDegradationFails() {
        Calibrate.GateResult r = healthy();
        r.tornSurvived = 8; // bad first record / shorter prefix
        assertFalse(r.verdict());
        r = healthy();
        r.tornPrefixIntact = false;
        assertFalse(r.verdict());
    }

    @Test
    void baselineDegradationFails() {
        Calibrate.GateResult r = healthy();
        r.baselineViolations = 1;
        assertFalse(r.verdict());
        r = healthy();
        r.baselineRuns = 60;
        assertFalse(r.verdict());
    }

    @Test
    void mutantDegradationFails() {
        Calibrate.GateResult r = healthy();
        r.mutantsCaptured = 12;
        assertFalse(r.verdict());
        r = healthy();
        r.mutantsTotal = 10;
        assertFalse(r.verdict());
    }

    @Test
    void localeDegradationFails() {
        Calibrate.GateResult r = healthy();
        r.localeIndependent = false;
        assertFalse(r.verdict());
    }

    @Test
    void c6AndSameSeedDegradationsFail() {
        Calibrate.GateResult r = healthy();
        r.c6ReplayStable = false;
        assertFalse(r.verdict());
        r = healthy();
        r.c6OrderStable = false;
        assertFalse(r.verdict());
        r = healthy();
        r.sameSeedIdentical = false;
        assertFalse(r.verdict());
    }

    @Test
    void substringOnlyTamperingCannotPassTheVerdict() {
        // the old gate matched "… 10 contract violations (must be 0)" via
        // endsWith; a structured verdict only looks at the measured numbers.
        Calibrate.GateResult r = healthy();
        r.baselineViolations = 10;
        assertFalse(r.verdict());
    }

    @Test
    void jsonSchemaFieldNamesAreFrozen() {
        Calibrate.GateResult r = healthy();
        r.pass = true;
        r.reportSha256 = "abc123";
        String json = r.json();
        for (String needle : new String[]{
                "\"version\":\"0.1.1\"", "\"jdk\":\"", "\"determinism\":{\"equal\":100,\"total\":100}",
                "\"tornTail\":{\"survived\":9,\"prefixIntact\":true}",
                "\"baseline\":{\"runs\":63,\"violations\":0}",
                "\"mutants\":{\"captured\":13,\"total\":13}",
                "\"c6\":{\"replayStable\":true,\"orderStable\":true}",
                "\"pass\":true", "\"reportSha256\":\"abc123\""}) {
            assertTrue(json.contains(needle), "missing frozen field: " + needle + " in " + json);
        }
        assertFalse(json.contains("localeIndependent"), "frozen schema must not gain fields");
    }

    @Test
    void reportRenderingIsLocaleIndependent() {
        Calibrate.GateResult r = healthy();
        r.baselineRuns = 63;
        r.active = new int[]{63, 63, 2, 11, 8, 63, 8, 8, 11};
        r.sweepLines = List.of("[3] S_W1 runs=20 violations=0");
        r.mutantRows = List.of(new Calibrate.MutantRow("M1", "effect-before-started-sync", "C2", true, 63, "x"));
        assertTrue(Calibrate.localeIndependent(r),
                "renderBody must use Locale.ROOT everywhere (verified under ar-EG and tr-TR)");
    }

    // ---------- end-to-end: exit code follows the structured verdict ----------

    private static int runGate(File projectRoot, String fault) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        // Calibrate has zero runtime dependencies: main classes are enough.
        File classes = new File(projectRoot, "target/classes");
        String cp = classes.isDirectory() ? classes.getAbsolutePath() : System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", cp, "waypoint.Calibrate");
        pb.directory(projectRoot);
        pb.redirectErrorStream(true);
        if (fault != null) pb.environment().put("WAYPOINT_FAULT", fault);
        Process p = pb.start();
        assertTrue(p.waitFor(180, TimeUnit.SECONDS), "gate subprocess timed out");
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.exitValue() == 0 || out.contains("GATE: FAIL"), out);
        return p.exitValue();
    }

    @Test
    void gateExitCodeIsZeroOnHealthyRunAndOneOnDegradation() throws Exception {
        File root = new File(System.getProperty("user.dir"));
        assertTrue(new File(root, "src/main/java/waypoint/Calibrate.java").isFile(),
                "tests must run from the project root (surefire does)");
        Path result = root.toPath().resolve("build/gate-result.json");
        // This test deliberately runs a degraded gate, which overwrites
        // build/gate-result.json. Restore the file afterwards so `mvn test`
        // cannot leave a stale FAILING verdict behind for scripts/verify-docs.sh
        // or scripts/update-results.sh --skip-gate.
        byte[] previous = Files.isRegularFile(result) ? Files.readAllBytes(result) : null;
        try {
            assertEquals(0, runGate(root, null), "normal ./gate.sh equivalent must be RC=0");
            assertTrue(Files.isRegularFile(result), "gate must always write build/gate-result.json");
            String json = Files.readString(result);
            assertTrue(json.contains("\"pass\":true"), json);
            assertEquals(1, runGate(root, "determinism"), "degraded determinism must be RC=1");
            String degraded = Files.readString(result);
            assertTrue(degraded.contains("\"pass\":false"), degraded);
            assertTrue(degraded.contains("\"determinism\":{\"equal\":33,\"total\":100}"), degraded);
        } finally {
            if (previous != null) {
                Files.createDirectories(result.getParent());
                Files.write(result, previous);
            } else {
                Files.deleteIfExists(result);
            }
        }
    }
}
