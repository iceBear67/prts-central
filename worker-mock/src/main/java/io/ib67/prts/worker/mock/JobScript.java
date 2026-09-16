package io.ib67.prts.worker.mock;

import io.ib67.prts.worker.mock.protocol.JobState;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What a mock worker does with one job, in order.
 *
 * <p>A script is a sequence of reports: it decides when the job is accepted, what it logs, whether it
 * uploads anything, and how it ends. Compose one with {@link #andThen}, or write a lambda for
 * anything the canned scripts do not cover.
 *
 * <p>A script runs on its own thread (the mock's run executor), so it may block — waiting for a
 * cancellation, or for a test to release it.
 */
@FunctionalInterface
public interface JobScript {

    void run(JobRun job) throws Exception;

    /**
     * Whether {@link JobRun#acknowledge()} is called before this script runs.
     *
     * <p>Only the worker that never answers says no: a script that declines the acknowledgment leaves
     * the control plane blocking on its 30-second create timeout.
     */
    default boolean acknowledge() {
        return true;
    }

    /** This script, then that one. The acknowledgment is only sent if both want it. */
    default JobScript andThen(JobScript next) {
        Objects.requireNonNull(next, "next");
        var first = this;
        return new JobScript() {
            @Override
            public void run(JobRun job) throws Exception {
                first.run(job);
                next.run(job);
            }

            @Override
            public boolean acknowledge() {
                return first.acknowledge() && next.acknowledge();
            }
        };
    }

    /** Accepts the job and reports it as {@code RUNNING}. */
    static JobScript started() {
        return job -> {
            job.running();
        };
    }

    /** Accepts the job, reports {@code RUNNING}, and finishes it with {@code SUCCESS}. */
    static JobScript success() {
        return started().andThen(JobRun::succeeded);
    }

    /** Accepts the job, reports {@code RUNNING}, logs a reason under stderr, and fails. */
    static JobScript failure(String reason) {
        return started().andThen(error(reason)).andThen(JobRun::failed);
    }

    /**
     * Accepts the job and keeps it running until it is cancelled or interrupted.
     *
     * <p>The script itself reports nothing when it is told to stop: the control plane has already
     * moved the job to {@code CANCELLED} by the time it asks.
     */
    static JobScript busy() {
        return started().andThen(JobRun::awaitCancellation);
    }

    /**
     * Never answers. The control plane waits out its 30-second create timeout and gives up on this
     * worker, which is the only way to reach that path — expect the test that uses it to take half a
     * minute.
     */
    static JobScript silent() {
        return new JobScript() {
            @Override
            public void run(JobRun job) {
            }

            @Override
            public boolean acknowledge() {
                return false;
            }
        };
    }

    /** Appends one line under {@code stdout}. */
    static JobScript log(String message) {
        return job -> job.log(message);
    }

    /** Appends one line under {@code stderr}, flagged as an error. */
    static JobScript error(String message) {
        return job -> job.error(message);
    }

    /** Uploads one artifact, given a name and the bytes to store. */
    static JobScript upload(String name, byte[] content) {
        return job -> job.upload(name, content);
    }

    /** Uploads one artifact whose bytes are the UTF-8 encoding of {@code text}. */
    static JobScript upload(String name, String text) {
        return upload(name, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Waits, so a test can catch the job mid-flight. */
    static JobScript sleep(Duration duration) {
        return job -> Thread.sleep(duration);
    }

    /** Announces the job's agent, then keeps the job running. */
    static JobScript attachedAgent() {
        return job -> {
            job.attachAgent();
            job.awaitCancellation();
        };
    }

    /** Reports a state and nothing else. */
    static JobScript state(JobState state) {
        return job -> job.state(state);
    }

    /** Runs a side effect against the job, for whatever the canned steps do not express. */
    static JobScript doing(Consumer<JobRun> action) {
        Objects.requireNonNull(action, "action");
        return action::accept;
    }
}
