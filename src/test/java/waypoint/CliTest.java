package waypoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** CLI behaviour: exit-code contract (0 clean / 1 findings / 2 usage-or-I/O),
 *  text and JSON rendering, and clear errors for bad input. Uses temp files
 *  only; no network access. */
class CliTest {

    // ---------------------------------------------------------------- fixtures

    /** A small, internally consistent ORDER-workflow run: no findings expected. */
    private static final String CLEAN_EVENTS = """
            {"type":"RUN_STARTED","f1":"ORDER","f2":"v1"}
            {"type":"STEP_STARTED","f1":"root/charge","f2":"k_charge"}
            {"type":"STEP_COMPLETED","f1":"root/charge","f2":"ok:k_charge"}
            {"type":"CHECKPOINT","f1":"w1","f2":"root/charge=COMPLETED"}
            {"type":"STEP_STARTED","f1":"root/report","f2":"k_report"}
            {"type":"STEP_COMPLETED","f1":"root/report","f2":"ok:k_report"}
            {"type":"RUN_COMPLETED"}
            """;

    private static final String CLEAN_LEDGER = """
            {"key":"k_charge","startedRecordIndex":1,"syncedFrontierAtApply":2,"executor":"runner"}
            {"key":"k_report","startedRecordIndex":4,"syncedFrontierAtApply":5,"executor":"runner"}
            """;

    private static final String CLEAN_META = """
            {
              "scenario": "cli-test/clean",
              "expectedSteps": ["root/charge", "root/report"],
              "preCompletedKeys": ["k_charge"],
              "preAppliedCounts": {"k_charge": 1},
              "twoResumers": false,
              "forkScenario": false,
              "protocolObservable": true,
              "preCompactionDurable": false,
              "preSummarizeCalls": 0,
              "configuredCancelAt": -1,
              "replayChunksEmitted": -1,
              "meteringPreEntries": 0,
              "meteringFinalEntries": 0,
              "interruptionKinds": [],
              "unobservable": [],
              "replayHashes": ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                               "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],
              "orderTraces": []
            }
            """;

    /** Same log, but the charge effect took effect twice: C2. */
    private static final String VIOLATION_LEDGER = """
            {"key":"k_charge","startedRecordIndex":1,"syncedFrontierAtApply":2,"executor":"runner"}
            {"key":"k_charge","startedRecordIndex":1,"syncedFrontierAtApply":2,"executor":"resumer1"}
            {"key":"k_report","startedRecordIndex":4,"syncedFrontierAtApply":5,"executor":"runner"}
            """;

    private static final String VIOLATION_META = """
            {
              "scenario": "cli-test/violation",
              "expectedSteps": ["root/charge", "root/report"],
              "preCompletedKeys": [],
              "preAppliedCounts": {},
              "twoResumers": false,
              "protocolObservable": true,
              "preCompactionDurable": false,
              "configuredCancelAt": -1,
              "replayChunksEmitted": -1,
              "meteringPreEntries": 0,
              "meteringFinalEntries": 0,
              "interruptionKinds": [],
              "unobservable": []
            }
            """;

    private record Cli(int code, String out, String err) {}

    private static Cli run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = waypoint.cli.Main.run(args, o, e);
        }
        return new Cli(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private static Path[] cleanFiles(Path dir) throws IOException {
        return new Path[]{
                write(dir, "events.jsonl", CLEAN_EVENTS),
                write(dir, "ledger.jsonl", CLEAN_LEDGER),
                write(dir, "meta.json", CLEAN_META)};
    }

    // ------------------------------------------------------------- behaviour

    @Test
    void versionExitsZero() {
        Cli r = run("version");
        assertEquals(0, r.code());
        assertTrue(r.out().startsWith("waypoint "), r.out());
        assertTrue(r.out().trim().matches("waypoint \\d+\\.\\d+\\.\\d+"), r.out());
        assertEquals("", r.err());
    }

    @Test
    void helpExitsZero() {
        Cli r = run("help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("waypoint check --events FILE --ledger FILE"), r.out());
        assertTrue(r.out().contains("exit codes"), r.out());
        assertTrue(r.out().contains("0  clean"), r.out());
    }

    @Test
    void helpCheckTopicDocumentsOptionsAndExitCodes() {
        Cli r = run("help", "check");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("--events"), r.out());
        assertTrue(r.out().contains("--ledger"), r.out());
        assertTrue(r.out().contains("--format"), r.out());
        assertTrue(r.out().contains("exit codes: 0 clean, 1 findings, 2 usage/I-O error."), r.out());
    }

    @Test
    void noArgumentsIsAUsageError() {
        Cli r = run();
        assertEquals(2, r.code());
        assertTrue(r.out().contains("usage:"), r.out());
        assertTrue(r.err().contains("missing command"), r.err());
    }

    @Test
    void cleanRunTextExitsZero(@TempDir Path dir) throws IOException {
        Path[] f = cleanFiles(dir);
        Cli r = run("check", "--events", f[0].toString(), "--ledger", f[1].toString(), "--meta", f[2].toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().startsWith("CLEAN"), r.out());
        assertTrue(r.out().contains("0 contract violations"), r.out());
        assertTrue(r.out().contains("scenario: cli-test/clean"), r.out());
        assertTrue(r.out().contains("events: 7"), r.out());
        assertTrue(r.out().contains("ledger-records: 2"), r.out());
        assertEquals("", r.err());
    }

    @Test
    void cleanRunJsonExitsZeroAndHasSummary(@TempDir Path dir) throws IOException {
        Path[] f = cleanFiles(dir);
        Cli r = run("check", "--events", f[0].toString(), "--ledger", f[1].toString(),
                "--meta", f[2].toString(), "--format", "json");
        assertEquals(0, r.code(), r.err());
        Json.Obj json = Json.parseObject(r.out());
        assertEquals("0.1.1", json.string("version", null));
        assertEquals("cli-test/clean", json.string("scenario", null));
        assertEquals(0, json.array("findings").size());
        Json.Obj summary = json.object("summary");
        assertTrue(summary.bool("clean", false));
        assertEquals(0, summary.integer("findings", -1));
        assertEquals(0, summary.array("contracts").size());
        assertTrue(summary.bool("protocolObservable", false));
        assertEquals("", r.err());
    }

    @Test
    void violationTextExitsOne(@TempDir Path dir) throws IOException {
        Path events = write(dir, "events.jsonl", CLEAN_EVENTS);
        Path ledger = write(dir, "ledger.jsonl", VIOLATION_LEDGER);
        Path meta = write(dir, "meta.json", VIOLATION_META);
        Cli r = run("check", "--events", events.toString(), "--ledger", ledger.toString(),
                "--meta", meta.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.out().startsWith("FINDINGS"), r.out());
        assertTrue(r.out().contains("C2 :: "), r.out());
        assertTrue(r.out().contains("k_charge"), r.out());
        assertTrue(r.out().contains("contract violation"), r.out());
    }

    @Test
    void violationJsonCarriesFindingsAndSummary(@TempDir Path dir) throws IOException {
        Path events = write(dir, "events.jsonl", CLEAN_EVENTS);
        Path ledger = write(dir, "ledger.jsonl", VIOLATION_LEDGER);
        Path meta = write(dir, "meta.json", VIOLATION_META);
        Cli r = run("check", "--events=" + events, "--ledger=" + ledger, "--meta=" + meta,
                "--format=json");
        assertEquals(1, r.code(), r.err());
        Json.Obj json = Json.parseObject(r.out());
        Json.Arr findings = json.array("findings");
        assertTrue(findings.size() >= 1, r.out());
        Json.Obj first = findings.get(0).asObject();
        assertEquals("C2", first.string("contract", null));
        assertTrue(first.string("detail", "").contains("k_charge"), r.out());
        Json.Obj summary = json.object("summary");
        assertEquals(findings.size(), summary.integer("findings", -1));
        assertFalse(summary.bool("clean", true));
        assertTrue(summary.array("contracts").items().stream()
                .anyMatch(v -> v.asString().equals("C2")), r.out());
    }

    // ----------------------------------------------------------- error paths

    @Test
    void missingRequiredOptionsExitTwo() {
        Cli r = run("check", "--ledger", "ledger.jsonl");
        assertEquals(2, r.code());
        assertEquals("", r.out());
        assertTrue(r.err().contains("--events"), r.err());
        Cli r2 = run("check", "--events", "events.jsonl");
        assertEquals(2, r2.code());
        assertTrue(r2.err().contains("--ledger"), r2.err());
    }

    @Test
    void unknownOptionAndCommandExitTwo(@TempDir Path dir) throws IOException {
        Path[] f = cleanFiles(dir);
        Cli r = run("check", "--events", f[0].toString(), "--ledger", f[1].toString(), "--bogus");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("unknown option \"--bogus\""), r.err());
        Cli c = run("frobnicate");
        assertEquals(2, c.code());
        assertTrue(c.err().contains("unknown command \"frobnicate\""), c.err());
    }

    @Test
    void invalidFormatValueExitsTwo(@TempDir Path dir) throws IOException {
        Path[] f = cleanFiles(dir);
        Cli r = run("check", "--events", f[0].toString(), "--ledger", f[1].toString(),
                "--format", "yaml");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("--format"), r.err());
        assertTrue(r.err().contains("text or json"), r.err());
    }

    @Test
    void missingInputFileExitsTwoWithClearMessage() {
        Cli r = run("check", "--events", "/nonexistent/events.jsonl", "--ledger", "/nonexistent/ledger.jsonl");
        assertEquals(2, r.code());
        assertEquals("", r.out());
        assertTrue(r.err().contains("file not found"), r.err());
        assertTrue(r.err().contains("events.jsonl"), r.err());
    }

    @Test
    void malformedJsonExitsTwoWithFileAndLine(@TempDir Path dir) throws IOException {
        Path events = write(dir, "events.jsonl", "{\"type\":\"RUN_STARTED\"\n");
        Path ledger = write(dir, "ledger.jsonl", "");
        Cli r = run("check", "--events", events.toString(), "--ledger", ledger.toString());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("events.jsonl:1"), r.err());
        assertTrue(r.err().contains("line 1"), r.err());
    }

    @Test
    void unknownEventTypeExitsTwo(@TempDir Path dir) throws IOException {
        Path events = write(dir, "events.jsonl", "{\"type\":\"STEP_EXPLODED\"}\n");
        Path ledger = write(dir, "ledger.jsonl", "");
        Cli r = run("check", "--events", events.toString(), "--ledger", ledger.toString());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("unknown event type \"STEP_EXPLODED\""), r.err());
    }

    @Test
    void unknownFieldIsRejectedNotSilentlyIgnored(@TempDir Path dir) throws IOException {
        Path events = write(dir, "events.jsonl", "{\"type\":\"RUN_STARTED\",\"f5\":\"typo\"}\n");
        Path ledger = write(dir, "ledger.jsonl", "");
        Cli r = run("check", "--events", events.toString(), "--ledger", ledger.toString());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("unknown field \"f5\""), r.err());

        Path meta = write(dir, "meta.json", "{\"expectedStep\":[\"root/x\"]}");
        Path events2 = write(dir, "events2.jsonl", CLEAN_EVENTS);
        Path ledger2 = write(dir, "ledger2.jsonl", CLEAN_LEDGER);
        Cli m = run("check", "--events", events2.toString(), "--ledger", ledger2.toString(),
                "--meta", meta.toString());
        assertEquals(2, m.code());
        assertTrue(m.err().contains("unknown field \"expectedStep\""), m.err());
    }

    @Test
    void optionalModelAndMetaMayBeOmitted(@TempDir Path dir) throws IOException {
        Path[] f = cleanFiles(dir);
        Cli r = run("check", "--events", f[0].toString(), "--ledger", f[1].toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("events: 7"), r.out());
    }

    @Test
    void eventsFileMayBeAJsonArray(@TempDir Path dir) throws IOException {
        String array = "[" + CLEAN_EVENTS.strip().lines()
                .map(String::strip)
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";
        Path events = write(dir, "events.json", array);
        Path ledger = write(dir, "ledger.jsonl", CLEAN_LEDGER);
        Cli r = run("check", "--events", events.toString(), "--ledger", ledger.toString(),
                "--format", "json");
        assertEquals(0, r.code(), r.err());
        assertEquals(0, Json.parseObject(r.out()).array("findings").size());
    }

    @Test
    void helpFlagInsideCheckExitsZero(@TempDir Path dir) {
        Cli r = run("check", "--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("--events"), r.out());
    }

    // -------------------------------------------------------- examples check

    @Test
    void shippedExamplesProduceDocumentedVerdicts() {
        Path base = Path.of("examples", "external-log");
        assertTrue(Files.isDirectory(base), "examples/external-log must exist (cwd="
                + Path.of(".").toAbsolutePath() + ")");
        Cli clean = run("check",
                "--events", base.resolve("clean/events.jsonl").toString(),
                "--ledger", base.resolve("clean/ledger.jsonl").toString(),
                "--meta", base.resolve("clean/meta.json").toString(),
                "--format", "json");
        assertEquals(0, clean.code(), "clean example must exit 0; stderr=" + clean.err());
        Json.Obj cleanJson = Json.parseObject(clean.out());
        assertTrue(cleanJson.object("summary").bool("clean", false), clean.out());

        Cli violation = run("check",
                "--events", base.resolve("violation/events.jsonl").toString(),
                "--ledger", base.resolve("violation/ledger.jsonl").toString(),
                "--meta", base.resolve("violation/meta.json").toString(),
                "--format", "json");
        assertEquals(1, violation.code(), "violation example must exit 1; stderr=" + violation.err());
        Json.Obj violationJson = Json.parseObject(violation.out());
        Json.Arr findings = violationJson.array("findings");
        assertTrue(findings.size() >= 1, violation.out());
        assertTrue(findings.items().stream()
                .anyMatch(v -> v.asObject().string("contract", "").equals("C2")), violation.out());
    }
}
