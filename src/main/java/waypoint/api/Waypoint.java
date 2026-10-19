package waypoint.api;

import waypoint.Checker;
import waypoint.Json;
import waypoint.Model;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Public facade for consuming Waypoint's contract checker from your own code.
 *
 * <p>Waypoint audits one run: a durable event log (what the runtime recorded),
 * an external apply ledger (what actually took effect in the outside world), an
 * optional model-call spy, and optional metadata describing what was already
 * durable when recovery started. This facade turns those four inputs into a
 * {@link Checker.RunResult}, runs {@link Checker#check(Checker.RunResult)}, and
 * formats the findings as human-readable text or machine-readable JSON.</p>
 *
 * <p>The runtime jar has <b>zero third-party dependencies</b>; the only inputs
 * are {@link Path}s or {@link InputStream}s of JSON/JSONL files. The file
 * schemas are documented in {@code docs/adapter-guide.md}; the examples under
 * {@code examples/external-log/} are runnable end to end.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * import waypoint.api.Waypoint;
 *
 * // 1) Load an adapter run from files ...
 * Waypoint.Report report = Waypoint.analyze(
 *         Path.of("events.jsonl"),
 *         Path.of("ledger.jsonl"),
 *         Path.of("model.jsonl"),   // may be null
 *         Path.of("meta.json"));    // may be null
 *
 * // ... or from streams (e.g. buffers produced by your framework adapter).
 * // 2) Inspect the result programmatically.
 * if (!report.clean()) {
 *     for (waypoint.Checker.Finding f : report.findings()) {
 *         System.err.println(f.contract + " :: " + f.detail);
 *     }
 * }
 * // 3) Or hand the rendered report to a human / CI.
 * System.out.println(report.text());   // human-readable
 * System.out.println(report.json());   // stable machine-readable JSON
 *
 * // Lower-level composition is available too:
 * Checker.RunResult run = Waypoint.loadResult(events, ledger, model, meta);
 * List<Checker.Finding> findings = Waypoint.check(run);
 * String json = Waypoint.formatJson(run, findings);
 * }</pre>
 *
 * <h2>Exit-code mapping (same as the CLI)</h2>
 * <ul>
 *   <li>{@link #analyze} returns a {@link Report}; it never exits the JVM.</li>
 *   <li>No findings &rarr; the CLI exits {@code 0}; findings &rarr; {@code 1};
 *       malformed input or I/O failure &rarr; {@code 2}.</li>
 * </ul>
 *
 * <p>This class is stateless and thread-safe.</p>
 */
public final class Waypoint {

    /** Version of the Waypoint jar/API, kept in sync with {@code pom.xml}. */
    public static final String VERSION = "0.1.1";

    private Waypoint() {}

    /**
     * Malformed adapter input: invalid JSON, an unknown event type, a missing
     * required field, an unknown field, or an out-of-range value. Message
     * includes the source file and, where possible, the line number.
     *
     * <p>This is an {@link IllegalArgumentException} (bad caller data), not an
     * I/O error; I/O failures are reported as {@link IOException}.</p>
     */
    public static final class FormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public FormatException(String message) { super(message); }
        public FormatException(String message, Throwable cause) { super(message, cause); }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Load a run from adapter files.
     *
     * @param events required {@code events.jsonl} / JSON array of event objects
     * @param ledger required {@code ledger.jsonl} / JSON array of apply records
     * @param model  optional {@code model.jsonl} / JSON array of model-call records (may be {@code null})
     * @param meta   optional {@code meta.json} object (may be {@code null})
     * @return a populated {@link Checker.RunResult}
     * @throws NoSuchFileException if a provided path does not exist
     * @throws IOException         if a file cannot be read
     * @throws FormatException if the content is not valid adapter input
     */
    public static Checker.RunResult loadResult(Path events, Path ledger, Path model, Path meta) throws IOException {
        requireExisting(events, "events");
        requireExisting(ledger, "ledger");
        try (InputStream e = Files.newInputStream(events);
             InputStream l = Files.newInputStream(ledger);
             InputStream m = model == null ? null : Files.newInputStream(requireExisting(model, "model"));
             InputStream x = meta == null ? null : Files.newInputStream(requireExisting(meta, "meta"))) {
            return InputFiles.load(e, events.toString(), l, ledger.toString(),
                    m, model == null ? "model" : model.toString(),
                    x, meta == null ? "meta" : meta.toString());
        }
    }

    /**
     * Convenience overload: events + ledger only (all other observations absent).
     *
     * @throws IOException                 if a file cannot be read
     * @throws FormatException if the content is not valid adapter input
     */
    public static Checker.RunResult loadResult(Path events, Path ledger) throws IOException {
        return loadResult(events, ledger, null, null);
    }

    /**
     * Load a run from streams. Streams are read to EOF and are <b>not</b>
     * closed (the caller owns them). {@code events} and {@code ledger} must be
     * non-null; {@code model} and {@code meta} may be {@code null}.
     *
     * @throws IOException                 on read failure
     * @throws FormatException if the content is not valid adapter input
     */
    public static Checker.RunResult loadResult(InputStream events, InputStream ledger,
                                               InputStream model, InputStream meta) throws IOException {
        if (events == null) throw new IllegalArgumentException("events stream must not be null");
        if (ledger == null) throw new IllegalArgumentException("ledger stream must not be null");
        return InputFiles.load(events, ledger, model, meta);
    }

    private static Path requireExisting(Path path, String what) throws IOException {
        if (path == null) throw new IllegalArgumentException(what + " path must not be null");
        if (!Files.exists(path)) {
            throw new NoSuchFileException(path.toString(), null, "adapter input file not found (" + what + ")");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException(what + " input is not a regular file: " + path);
        }
        return path;
    }

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    /**
     * Run the contract checker over an already-loaded run.
     *
     * <p>The checker reads the durable event log and the external ledger/spy
     * rather than the runtime's own claims. It may add contract ids to
     * {@link Checker.RunResult#unobservable} when the adapter did not supply
     * enough evidence to evaluate an assertion.</p>
     *
     * @return an unmodifiable list of findings; empty means clean
     */
    public static List<Checker.Finding> check(Checker.RunResult run) {
        return Collections.unmodifiableList(new ArrayList<>(Checker.check(run)));
    }

    /**
     * Load and check in one call.
     *
     * @throws IOException                 if a file cannot be read
     * @throws FormatException  if the content is not valid adapter input
     */
    public static Report analyze(Path events, Path ledger, Path model, Path meta) throws IOException {
        Checker.RunResult run = loadResult(events, ledger, model, meta);
        return new Report(run, check(run));
    }

    /** Convenience overload: events + ledger only. */
    public static Report analyze(Path events, Path ledger) throws IOException {
        Checker.RunResult run = loadResult(events, ledger);
        return new Report(run, check(run));
    }

    /**
     * Load from streams and check in one call. Streams are not closed.
     *
     * @throws IOException                on read failure
     * @throws FormatException if the content is not valid adapter input
     */
    public static Report analyze(InputStream events, InputStream ledger,
                                 InputStream model, InputStream meta) throws IOException {
        Checker.RunResult run = loadResult(events, ledger, model, meta);
        return new Report(run, check(run));
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /**
     * Render findings as human-readable text (the same text the CLI prints for
     * {@code --format text}). Deterministic: no timestamps, locale-independent.
     */
    public static String formatText(Checker.RunResult run, List<Checker.Finding> findings) {
        StringBuilder sb = new StringBuilder();
        boolean clean = findings.isEmpty();
        sb.append(clean ? "CLEAN" : "FINDINGS")
          .append(clean ? "  (0 contract violations)" : "  (" + findings.size() + " contract violation"
                  + (findings.size() == 1 ? "" : "s") + ")")
          .append('\n');
        Set<String> unobservable = run.unobservable == null ? Set.of() : run.unobservable;
        sb.append("scenario: ").append(run.scenario == null || run.scenario.isEmpty() ? "-" : run.scenario).append('\n');
        sb.append("events: ").append(run.events == null ? 0 : run.events.size())
          .append("  ledger-records: ").append(run.ext == null ? 0 : run.ext.records().size())
          .append("  model-calls: ").append(run.model == null ? 0 : countModelCalls(run.model))
          .append('\n');
        if (!unobservable.isEmpty()) {
            sb.append("unobservable: ").append(String.join(", ", sorted(unobservable))).append('\n');
        }
        Set<String> partial = run.partial == null ? Set.of() : run.partial;
        if (!partial.isEmpty()) {
            sb.append("partially observable: ").append(String.join(", ", sorted(partial)))
              .append(" (some rules had no evidence; see docs/adapter-guide.md)").append('\n');
        }
        if (!run.protocolObservable) {
            sb.append("protocol-order assertions: declared unobservable by adapter (C2 ordering skipped)\n");
        }
        int i = 1;
        for (Checker.Finding f : findings) {
            sb.append("  [").append(i++).append("] ").append(f.contract).append(" :: ").append(f.detail).append('\n');
        }
        return sb.toString();
    }

    /**
     * Render findings as machine-readable JSON (the same JSON the CLI prints for
     * {@code --format json}). Shape:
     * <pre>{@code
     * {"version":"0.1.1","scenario":"...","findings":[{"contract":"C2","detail":"..."}],
     *  "summary":{"findings":1,"clean":false,"contracts":["C2"],
     *             "unobservable":[],"partial":[],"protocolObservable":true}}
     * }</pre>
     * Object/array order is insertion order, so output is byte-stable across
     * runs and JVMs. Consumers should ignore unknown fields for forward
     * compatibility.
     */
    public static String formatJson(Checker.RunResult run, List<Checker.Finding> findings) {
        Json.Arr fs = Json.array();
        Set<String> contracts = new LinkedHashSet<>();
        for (Checker.Finding f : findings) {
            fs.add(Json.object()
                    .put("contract", f.contract)
                    .put("detail", f.detail == null ? "" : f.detail));
            contracts.add(f.contract);
        }
        Json.Arr unobservable = Json.array();
        for (String u : sorted(run.unobservable == null ? Set.of() : run.unobservable)) unobservable.add(Json.of(u));
        Json.Arr partial = Json.array();
        for (String p : sorted(run.partial == null ? Set.of() : run.partial)) partial.add(Json.of(p));
        Json.Obj summary = Json.object()
                .put("findings", findings.size())
                .put("clean", findings.isEmpty())
                .put("contracts", contractsArray(contracts))
                .put("unobservable", unobservable)
                .put("partial", partial)
                .put("protocolObservable", run.protocolObservable);
        Json.Obj root = Json.object()
                .put("version", VERSION)
                .put("scenario", run.scenario == null ? "" : run.scenario)
                .put("findings", fs)
                .put("summary", summary);
        return Json.stringify(root);
    }

    /** Print text output to a stream and return whether the run was clean. */
    public static boolean printText(Checker.RunResult run, List<Checker.Finding> findings, PrintStream out) {
        out.print(formatText(run, findings));
        out.flush();
        return findings.isEmpty();
    }

    /** Print JSON output to a stream and return whether the run was clean. */
    public static boolean printJson(Checker.RunResult run, List<Checker.Finding> findings, PrintStream out) {
        out.println(formatJson(run, findings));
        out.flush();
        return findings.isEmpty();
    }

    private static int countModelCalls(Model.ModelSpy spy) {
        int n = 0;
        for (int v : spy.all().values()) n += v;
        return n;
    }

    private static Json.Arr contractsArray(Set<String> contracts) {
        Json.Arr arr = Json.array();
        for (String c : contracts) arr.add(Json.of(c));
        return arr;
    }

    private static List<String> sorted(Set<String> values) {
        List<String> out = new ArrayList<>(values);
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    /**
     * A checked run: the populated {@link Checker.RunResult} plus its findings.
     * Immutable and safe to hand to other components.
     */
    public record Report(Checker.RunResult run, List<Checker.Finding> findings) {

        /** Defensive copy: findings are never mutated after construction. */
        public Report {
            findings = List.copyOf(findings);
        }

        /** @return true if the checker reported no contract violations */
        public boolean clean() { return findings.isEmpty(); }

        /** @return human-readable rendering (see {@link Waypoint#formatText}) */
        public String text() { return formatText(run, findings); }

        /** @return machine-readable rendering (see {@link Waypoint#formatJson}) */
        public String json() { return formatJson(run, findings); }
    }
}
