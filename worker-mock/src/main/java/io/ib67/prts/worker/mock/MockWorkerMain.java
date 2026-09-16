package io.ib67.prts.worker.mock;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a mock worker as a process of its own, for driving a control plane that is already up —
 * dev mode, a staging deployment, or a test that wants the worker outside its own JVM.
 *
 * <pre>{@code
 * mock-worker --url http://localhost:8080 --token allo --name w1 --script succeed
 * }</pre>
 *
 * <p>Every message in either direction is printed, prefixed with {@code <} for what the control
 * plane sent and {@code >} for what the worker answers.
 */
public final class MockWorkerMain {

    private MockWorkerMain() {
    }

    public static void main(String[] args) {
        Map<String, String> flags;
        MockWorker worker;
        int limit;
        try {
            flags = parse(args);
            if (flags.containsKey("help")) {
                usage();
                return;
            }
            // Built inside the guard too: a missing or malformed flag is a usage error, not a stack
            // trace. By the time this returns, the worker has registered.
            limit = integer(flags, "jobs", 0);
            worker = build(flags);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        var stop = new Thread(worker::abort);
        Runtime.getRuntime().addShutdownHook(stop);
        try {
            worker.onSent(message -> System.out.println("> " + message));
            worker.onInbound(message -> System.out.println("< " + message));
            waitForJobs(worker, limit);
        } finally {
            worker.close();
            removeHook(stop);
        }
    }

    private static MockWorker build(Map<String, String> flags) {
        if (!flags.containsKey("url")) {
            throw new IllegalArgumentException("--url is required");
        }
        var builder = MockWorker.builder(
                        URI.create(flags.get("url")),
                        flags.getOrDefault("token", "allo"))
                .name(flags.getOrDefault("name", "mock-worker"))
                .resources(
                        integer(flags, "cpus", 8),
                        integer(flags, "mem", 8192),
                        integer(flags, "disk", 102400))
                .onJob(script(flags.getOrDefault("script", "succeed")));
        if (flags.containsKey("id")) {
            builder.workerId(UUID.fromString(flags.get("id")));
        }
        if (flags.containsKey("agent")) {
            builder.agent(AgentBehaviour.echoing());
        }
        var worker = builder.start();
        System.out.println("-- registered as " + worker.workerId() + " (\"" + worker.name() + "\")");
        return worker;
    }

    private static JobScript script(String name) {
        return switch (name) {
            // started() has already reported RUNNING, so these finish the job outright rather than
            // going through success(), which would report RUNNING a second time.
            case "succeed" -> JobScript.started()
                    .andThen(JobScript.log("the mock worker is building"))
                    .andThen(JobRun::succeeded);
            case "fail" -> JobScript.failure("the mock worker was told to fail");
            case "hang" -> JobScript.busy();
            case "upload" -> JobScript.started()
                    .andThen(JobScript.log("the mock worker is uploading"))
                    .andThen(JobScript.upload("mock-worker.txt", "uploaded by the mock worker"))
                    .andThen(JobRun::succeeded);
            case "silent" -> JobScript.silent();
            default -> throw new IllegalArgumentException(
                    "--script must be one of succeed, fail, hang, upload, silent (got " + name + ")");
        };
    }

    /** With no {@code --jobs}, this worker runs until it is killed. */
    private static void waitForJobs(MockWorker worker, int limit) {
        if (limit <= 0) {
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        var finished = new HashSet<UUID>();
        var deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (finished.size() < limit) {
            if (System.nanoTime() > deadline) {
                System.err.println("-- gave up waiting for " + limit + " job(s)");
                System.exit(1);
            }
            for (var job : worker.jobs()) {
                if (job.state().isTerminal()) {
                    finished.add(job.jobId());
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("-- " + limit + " job(s) finished");
    }

    /** Flags that stand alone; every other flag consumes the argument after it. */
    private static final Set<String> SWITCHES = Set.of("agent", "help");

    private static final Set<String> VALUED =
            Set.of("url", "token", "name", "id", "cpus", "mem", "disk", "script", "jobs");

    private static Map<String, String> parse(String[] args) {
        var flags = new LinkedHashMap<String, String>();
        for (var i = 0; i < args.length; i++) {
            var arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            var name = arg.substring(2);
            // A mistyped flag must not be swallowed: it would leave the default in force and the
            // caller wondering why nothing changed.
            if (SWITCHES.contains(name)) {
                flags.put(name, "true");
                continue;
            }
            if (!VALUED.contains(name)) {
                throw new IllegalArgumentException("unknown flag: --" + name);
            }
            if (i + 1 == args.length) {
                throw new IllegalArgumentException("--" + name + " needs a value");
            }
            flags.put(name, args[++i]);
        }
        return flags;
    }

    private static int integer(Map<String, String> flags, String name, int fallback) {
        var value = flags.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number, got " + value);
        }
    }

    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // Already shutting down.
        }
    }

    private static void usage() {
        System.out.println("""
                Runs a mock prts worker against a running control plane.

                  --url <url>        control plane base URL, e.g. http://localhost:8080 (required)
                  --token <secret>   the shared worker secret (default: allo, the dev value)
                  --name <name>      the name this worker registers under (default: mock-worker)
                  --id <uuid>        the worker id to assert (default: a fresh one)
                  --cpus <n>         reported CPUs (default: 8)
                  --mem <n>          reported memory units (default: 8192)
                  --disk <n>         reported disk units (default: 102400)
                  --script <name>    succeed | fail | hang | upload | silent (default: succeed)
                  --agent            let every job attach an echoing agent
                  --jobs <n>         exit once n jobs have finished (default: run until killed)
                  --help             this text""");
    }
}
