package io.ib67.prts.dev;

import io.ib67.prts.agent.worker.WorkerConfig;
import io.ib67.prts.worker.mock.AgentBehaviour;
import io.ib67.prts.worker.mock.JobRun;
import io.ib67.prts.worker.mock.JobScript;
import io.ib67.prts.worker.mock.MockWorker;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Connects a mock worker to dev mode's own control plane, so a job created from the UI is placed,
 * run and finished instead of queueing forever. The mock lives in {@code worker-mock/}, on the dev
 * classpath only.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class MockWorkerRunner {
    private static final Logger LOG = Logger.getLogger(MockWorkerRunner.class);

    /** A label with which a job picks its own script, overriding the configured default. */
    private static final String LABEL = "prts.mock";

    private static final int ATTEMPTS = 30;
    private static final Duration RETRY = Duration.ofSeconds(1);
    private static final Duration STEP = Duration.ofSeconds(1);
    /** The upload sweeper looks every two seconds, and only while the job is still open. */
    private static final Duration SWEEP = Duration.ofSeconds(5);

    @Inject
    MockWorkerConfig config;
    @Inject
    WorkerConfig workerConfig;

    private volatile MockWorker worker;
    private volatile boolean stopped;

    void start(@Observes StartupEvent event) {
        if (!config.enabled()) {
            return;
        }
        var thread = new Thread(this::connect, "prts-dev-mock-worker");
        thread.setDaemon(true);
        thread.start();
    }

    void stop(@Observes ShutdownEvent event) {
        stopped = true;
        var running = worker;
        if (running != null) {
            running.close();
        }
    }

    /**
     * The HTTP port is not open yet when startup fires, and after a live reload the control plane
     * still holds the session the previous run left behind — both refuse a worker, and both pass.
     */
    private void connect() {
        RuntimeException refused = null;
        for (var attempt = 0; attempt < ATTEMPTS && !stopped; attempt++) {
            try {
                var started = open();
                if (stopped) {
                    started.close();
                    return;
                }
                worker = started;
                LOG.infof("the dev mock worker registered as %s (\"%s\")",
                        started.workerId(), started.name());
                return;
            } catch (RuntimeException e) {
                refused = e;
                sleep(RETRY);
            }
        }
        if (!stopped) {
            LOG.warnf(refused, "dev mode has no mock worker: %s would not take one", config.url());
        }
    }

    private MockWorker open() {
        var candidate = MockWorker.builder(config.url(), workerConfig.secret())
                .workerId(config.id())
                .name(config.name())
                .resources(config.resources().cpus(), config.resources().memory(), config.resources().disk())
                .agent(AgentBehaviour.echoing())
                .onJob(this::scriptFor)
                .connect();
        try {
            return candidate.register();
        } catch (RuntimeException e) {
            // connect() opened a socket; a refused registration must not leave it behind.
            candidate.close();
            throw e;
        }
    }

    private JobScript scriptFor(JobRun job) {
        return script(job.spec().labels().getOrDefault(LABEL, config.script()));
    }

    private JobScript script(String name) {
        return switch (name) {
            case "demo" -> demo();
            case "succeed" -> JobScript.success();
            case "fail" -> JobScript.failure("the dev mock worker was told to fail");
            // Runs until the UI cancels it.
            case "hang" -> JobScript.busy();
            case "agent" -> JobScript.started().andThen(JobScript.attachedAgent());
            default -> {
                LOG.warnf("no mock worker script is named %s; running the demo one", name);
                yield demo();
            }
        };
    }

    /** Long enough to watch happen: a couple of log lines, an artifact, then a clean finish. */
    private static JobScript demo() {
        return JobScript.started()
                .andThen(JobScript.doing(job -> job.log("pulling " + job.spec().image())))
                .andThen(JobScript.sleep(STEP))
                .andThen(JobScript.doing(job -> job.log("+ " + String.join(" ", job.spec().command()))))
                .andThen(JobScript.sleep(STEP))
                .andThen(JobScript.log("exited with code 0"))
                .andThen(JobScript.upload("mock-worker.log", "run by the dev mock worker\n"))
                // Nothing is recorded until the sweeper finds the object, and it only promotes an
                // upload while the job is still open — so the wait before SUCCESS is the point.
                .andThen(JobScript.sleep(SWEEP))
                .andThen(JobRun::succeeded);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
