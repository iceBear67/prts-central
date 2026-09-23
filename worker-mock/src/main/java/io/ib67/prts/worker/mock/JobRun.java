package io.ib67.prts.worker.mock;

import com.fasterxml.jackson.databind.JsonNode;
import io.ib67.prts.worker.mock.protocol.Inbound;
import io.ib67.prts.worker.mock.protocol.JobSpec;
import io.ib67.prts.worker.mock.protocol.JobState;
import io.ib67.prts.worker.mock.protocol.ResourceClass;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * One job as a script sees it: what it was asked to run, and everything it can report back.
 *
 * <p>Every method sends a message to the control plane. None of them is implicit — a script that
 * reports nothing leaves the job exactly as the control plane sees it, which is {@code PENDING}
 * with a container that never finishes.
 */
public interface JobRun {

    UUID jobId();

    JobSpec spec();

    ResourceClass resourceClass();

    /** Decrypted project secrets, present only on this in-memory hand-off. */
    Map<String, String> secrets();

    /** The last state this script reported. */
    JobState state();

    /**
     * Accepts the job by answering its {@code createJob} envelope with {@code ack}.
     *
     * <p>The control plane blocks on this for 30 seconds before it gives up on the worker, so every
     * {@link JobScript} answers it first through {@link JobScript#acknowledge()}.
     */
    void acknowledge();

    boolean isAcknowledged();

    // ---- reporting ----

    /** Appends a line to the job's log under the {@code stdout} topic. */
    void log(String message);

    /** Appends a line to the job's log. */
    void log(String topic, String message);

    /** Appends a line under {@code stderr}, flagged as an error. */
    void error(String message);

    /** Reports a state. A terminal state ends the run and releases {@link #awaitTerminal}. */
    void state(JobState state);

    void running();

    void succeeded();

    void failed();

    void cancelled();

    // ---- artifacts ----

    /**
     * Asks the control plane where to put an artifact, then uploads it there.
     *
     * <p>The control plane records nothing until the object's size matches the one announced here, so
     * the size asked for is the length of {@code content}.
     *
     * @throws IllegalStateException if the upload is refused or the storage rejects the bytes
     */
    void upload(String name, byte[] content);

    // ---- its agent ----

    /**
     * Announces that a job's agent is up, using the configured {@link AgentBehaviour}'s session id.
     *
     * @throws IllegalStateException if the mock has no agent behaviour configured
     */
    void attachAgent();

    void attachAgent(String sessionId);

    /** Carries one JSON-RPC frame to the control plane on the agent's behalf. */
    void agentFrame(JsonNode frame);

    /** Announces that the job's agent went away while the job keeps running. */
    void detachAgent(String reason);

    // ---- waiting ----

    /**
     * Blocks until the control plane cancels or interrupts this job, or the connection to it drops:
     * the control plane fails every job of a worker it loses, so the worker stops them itself.
     *
     * <p>Returns on interruption as well: that is the mock being torn down, not a cancellation.
     */
    void awaitCancellation();

    /** @return whether the job was cancelled, interrupted, or terminal within the timeout */
    boolean awaitCancellation(Duration timeout);

    /** True after a {@code cancelJob}, and after the connection dropped while the job was open. */
    boolean wasCancelled();

    boolean wasInterrupted();

    /** Why the worker was told to interrupt, or null. */
    String interruptReason();

    /** @return whether the job reached a terminal state this script reported */
    boolean awaitTerminal(Duration timeout);

    /** The {@code createJob} request this run was accepted from. */
    Inbound.CreateJob request();
}
