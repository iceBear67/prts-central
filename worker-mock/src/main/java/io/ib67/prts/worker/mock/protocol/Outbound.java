package io.ib67.prts.worker.mock.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.UUID;

/**
 * Messages a worker sends to the control plane, written as the wire contract defines them.
 *
 * <p>{@link #type()} is the value of the {@code type} discriminator property; the control plane's
 * own names for these messages are recorded here so a reader can line the two up.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Outbound.Ack.class, name = "ack"),
        @JsonSubTypes.Type(value = Outbound.Register.class, name = "register"),
        @JsonSubTypes.Type(value = Outbound.UpdateResourceInfo.class, name = "updateResourceInfo"),
        @JsonSubTypes.Type(value = Outbound.JobStateUpdate.class, name = "jobStateUpdate"),
        @JsonSubTypes.Type(value = Outbound.UpdateJobLog.class, name = "updateJobLog"),
        @JsonSubTypes.Type(value = Outbound.UploadArtifactRequest.class, name = "uploadArtifactRequest"),
        @JsonSubTypes.Type(value = Outbound.AgentAttached.class, name = "agentAttached"),
        @JsonSubTypes.Type(value = Outbound.AgentFrame.class, name = "agentFrame"),
        @JsonSubTypes.Type(value = Outbound.AgentDetached.class, name = "agentDetached"),
})
public sealed interface Outbound {

    /**
     * Answers one message the control plane sent, named by the envelope's {@code replyTo}.
     *
     * <p>This is what accepts a {@code createJob}, a {@code createVolume} or a {@code deleteVolume}.
     * The control plane blocks on the acceptance of a job for up to 30 seconds and places nothing
     * until it arrives.
     *
     * @param message why it was refused; ignored when {@code ok}
     */
    record Ack(boolean ok, String message) implements Outbound {
        public Ack {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Announces this worker under an id it asserts for itself.
     *
     * <p>{@code info} may be absent: a worker that reports nothing is treated as unbounded and idle.
     */
    record Register(UUID workerId, String name, ResourceInfo info) implements Outbound {
        public Register {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Refreshes the resource snapshot the control plane keeps for placement. */
    record UpdateResourceInfo(ResourceInfo info) implements Outbound {
        public UpdateResourceInfo {
            Objects.requireNonNull(info, "info");
        }
    }

    /** Reports a job's state. The control plane keeps the first terminal state it hears. */
    record JobStateUpdate(UUID jobId, JobState state) implements Outbound {
        public JobStateUpdate {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * Appends one line to a job's log.
     *
     * @param topic where the line came from, conventionally {@code stdout} or {@code stderr}
     */
    record UpdateJobLog(UUID jobId, String topic, String message, Boolean error) implements Outbound {
        public UpdateJobLog {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    /** Asks for a presigned URL to upload one artifact of a job. Answered by {@code presignedUpload}. */
    record UploadArtifactRequest(UUID jobId, String name, long sizeBytes) implements Outbound {
        public UploadArtifactRequest {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Announces that a job's agent is initialized.
     *
     * @param initialize verbatim {@code initialize} result the agent produced
     * @param sessionId  root session id the worker opened for it
     */
    record AgentAttached(UUID jobId, JsonNode initialize, String sessionId) implements Outbound {
        public AgentAttached {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(initialize, "initialize");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    /** Carries one JSON-RPC frame of a job's agent. */
    record AgentFrame(UUID jobId, JsonNode frame) implements Outbound {
        public AgentFrame {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(frame, "frame");
        }
    }

    /** Signals that a job's agent went away while the job itself is still running. */
    record AgentDetached(UUID jobId, String reason) implements Outbound {
        public AgentDetached {
            Objects.requireNonNull(jobId, "jobId");
        }
    }
}
