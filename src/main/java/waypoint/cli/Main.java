package waypoint.cli;

import waypoint.Calibrate;
import waypoint.api.Waypoint;
import waypoint.api.Waypoint.FormatException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Command-line entry point for the Waypoint contract checker.
 *
 * <pre>
 * waypoint check --events FILE --ledger FILE [--model FILE] [--meta FILE] [--format text|json]
 * waypoint calibrate
 * waypoint version
 * waypoint help [check|calibrate]
 * </pre>
 *
 * <p>Exit codes are the contract with CI systems:</p>
 * <ul>
 *   <li>{@code 0} — clean: the checker reported no findings</li>
 *   <li>{@code 1} — findings: at least one contract violation was reported</li>
 *   <li>{@code 2} — usage or I/O error; no verdict was produced</li>
 * </ul>
 *
 * <p>{@link #run(String[], PrintStream, PrintStream)} is public and does not
 * call {@link System#exit} so it can be driven from tests and other Java code;
 * {@link #main(String[])} is the thin process wrapper.</p>
 */
public final class Main {

    /** Fallback version when the jar manifest is unavailable (e.g. tests). */
    public static final String FALLBACK_VERSION = Waypoint.VERSION;

    private Main() {}

    /** Process entry point: delegates to {@link #run} and exits with its code. */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Execute one CLI invocation.
     *
     * @param args command-line arguments ({@code null} is treated as empty)
     * @param out  stdout sink
     * @param err  stderr sink
     * @return process exit code: 0 clean, 1 findings, 2 usage/I/O error
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        String[] argv = args == null ? new String[0] : args;
        if (argv.length == 0) {
            printGeneralHelp(out);
            err.println("waypoint: missing command (expected: check, calibrate, version, or help)");
            return 2; // invoking with no command is a usage error
        }
        String command = argv[0];
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        switch (command) {
            case "help", "-h", "--help":
                return help(rest, out, err);
            case "version", "-V", "--version":
                out.println("waypoint " + version());
                return 0;
            case "check":
                return check(rest, out, err);
            case "calibrate":
                return calibrate(rest);
            default:
                return usageError(err, "unknown command \"" + command + "\"");
        }
    }

    // ------------------------------------------------------------------ help

    private static int help(String[] rest, PrintStream out, PrintStream err) {
        if (rest.length == 0) {
            printGeneralHelp(out);
            return 0;
        }
        return switch (rest[0]) {
            case "check", "calibrate", "version", "help" -> {
                if (rest[0].equals("check")) printCheckHelp(out);
                else if (rest[0].equals("calibrate")) printCalibrateHelp(out);
                else printGeneralHelp(out);
                yield 0;
            }
            default -> usageError(err, "unknown help topic \"" + rest[0] + "\"");
        };
    }

    private static void printGeneralHelp(PrintStream out) {
        out.println("""
                waypoint — recovery-contract checker for agent / workflow runtimes

                usage:
                  waypoint check --events FILE --ledger FILE [--model FILE] [--meta FILE] [--format text|json]
                  waypoint calibrate
                  waypoint version
                  waypoint help [check|calibrate]

                exit codes:
                  0  clean   — no contract findings
                  1  findings— at least one contract violation
                  2  error   — usage or I/O error; no verdict was produced

                Run 'waypoint help check' for the adapter file formats.""");
    }

    private static void printCheckHelp(PrintStream out) {
        out.println("""
                waypoint check — audit one run against the recovery contracts

                usage:
                  waypoint check --events FILE --ledger FILE [--model FILE] [--meta FILE] [--format text|json]

                inputs (UTF-8; JSONL — one object per line — or a JSON array):
                  --events  FILE   durable event log (required). Each record: {"type":"STEP_STARTED","f1":...}
                  --ledger  FILE   external apply ledger (required). Each record:
                                   {"key":"k","startedRecordIndex":1,"syncedFrontierAtApply":2,"executor":"runner"}
                  --model   FILE   model-call spy (optional). Each record: {"op":"gen","subject":"root/gen"}
                  --meta    FILE   RunResult metadata object (optional). Example keys: expectedSteps,
                                   preCompletedKeys, preAppliedCounts, configuredCancelAt,
                                   replayChunksEmitted, meteringPreEntries, meteringFinalEntries,
                                   interruptionKinds, unobservable, protocolObservable

                options:
                  --format  text | json   output format (default: text)
                  -h, --help              show this help

                exit codes: 0 clean, 1 findings, 2 usage/I-O error.

                See docs/adapter-guide.md and examples/external-log/ for a runnable setup.""");
    }

    private static void printCalibrateHelp(PrintStream out) {
        out.println("""
                waypoint calibrate — run the deterministic mutation-calibration gate

                Equivalent to running waypoint.Calibrate: recovery determinism, torn-tail
                self-test, baseline specificity sweep, and mutation calibration. Prints the
                gate report and exits 0 only when every structured assertion passes (1 on a
                regression). This is the repository's own gate; 'check' audits external runs.""");
    }

    // ----------------------------------------------------------------- check

    private static int check(String[] args, PrintStream out, PrintStream err) {
        Path events = null, ledger = null, model = null, meta = null;
        String format = "text";
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                printCheckHelp(out);
                return 0;
            }
            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }
            switch (name) {
                case "--events", "--ledger", "--model", "--meta", "--format" -> {
                    String value = inline;
                    if (value == null) {
                        if (i + 1 >= args.length) {
                            return usageError(err, "option " + name + " requires a value");
                        }
                        value = args[++i];
                    }
                    if (value.isEmpty()) return usageError(err, "option " + name + " requires a non-empty value");
                    Path p = Path.of(value);
                    switch (name) {
                        case "--events" -> events = p;
                        case "--ledger" -> ledger = p;
                        case "--model" -> model = p;
                        case "--meta" -> meta = p;
                        case "--format" -> format = value;
                        default -> throw new AssertionError(name);
                    }
                }
                default -> {
                    if (arg.startsWith("-")) return usageError(err, "unknown option \"" + arg + "\"");
                    return usageError(err, "unexpected argument \"" + arg + "\"");
                }
            }
        }
        if (!format.equals("text") && !format.equals("json")) {
            return usageError(err, "invalid --format value \"" + format + "\" (expected text or json)");
        }
        if (events == null) return usageError(err, "missing required option --events");
        if (ledger == null) return usageError(err, "missing required option --ledger");

        Waypoint.Report report;
        try {
            report = Waypoint.analyze(events, ledger, model, meta);
        } catch (NoSuchFileException e) {
            return ioError(err, "file not found: " + e.getFile());
        } catch (FormatException e) {
            return ioError(err, e.getMessage());
        } catch (IOException e) {
            return ioError(err, "I/O error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // Includes waypoint.Json.JsonException escaping from a hand-built input.
            return ioError(err, e.getMessage());
        }

        if (format.equals("json")) {
            out.println(report.json());
        } else {
            out.print(report.text());
        }
        out.flush();
        return report.clean() ? 0 : 1;
    }

    // ------------------------------------------------------------- calibrate

    private static int calibrate(String[] args) {
        // Forward verbatim: Calibrate owns its argument handling and exit code.
        Calibrate.main(args);
        return 0; // only reached if Calibrate.main ever stops calling System.exit
    }

    // --------------------------------------------------------------- helpers

    private static String version() {
        String v = Main.class.getPackage() == null ? null : Main.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? FALLBACK_VERSION : v;
    }

    private static int usageError(PrintStream err, String message) {
        err.println("waypoint: " + message);
        err.println("run 'waypoint help' for usage");
        return 2;
    }

    private static int ioError(PrintStream err, String message) {
        err.println("waypoint: " + message);
        return 2;
    }
}
