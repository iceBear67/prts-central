package io.ib67.prts.worker.mock.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Messages the control plane sends to a worker.
 *
 * <p>The control plane answers every message this worker sends with exactly one message, carried in
 * an envelope whose {@code replyTo} names what it answers: an {@link Ack}, or — for an
 * {@code uploadArtifactRequest} it accepted — a {@link PresignedUpload}. Everything else in this
 * interface arrives unsolicited and this worker owes it one answer.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Inbound.Ack.class, name = "ack"),
        @JsonSubTypes.Type(value = Inbound.CreateJob.class, name = "createJob"),
        @JsonSubTypes.Type(value = Inbound.CancelJob.class, name = "cancelJob"),
        @JsonSubTypes.Type(value = Inbound.InterruptJob.class, name = "interruptJob"),
        @JsonSubTypes.Type(value = Inbound.PresignedUpload.class, name = "presignedUpload"),
        @JsonSubTypes.Type(value = Inbound.CreateVolume.class, name = "createVolume"),
        @JsonSubTypes.Type(value = Inbound.DeleteVolume.class, name = "deleteVolume"),
        @JsonSubTypes.Type(value = Inbound.AgentFrame.class, name = "agentFrame"),
})
public sealed interface Inbound {

    /**
     * The answer to one message this worker sent, named by the envelope's {@code replyTo}.
     *
     * @param message why it was refused; ignored when {@code ok}
     */
    record Ack(boolean ok, String message) implements Inbound {
        public Ack {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Instructs this worker to run a job.
     *
     * @param spec     what to run
     * @param secrets  decrypted project secrets, delivered only here
     */
    record CreateJob(
            UUID jobId,
            JobSpec spec,
            ResourceClass resourceClass,
            Map<String, String> secrets
    ) implements Inbound {
        public CreateJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(resourceClass, "resourceClass");
            secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        }
    }

    /** Asks this worker to stop a running job. The job's own state report follows separately. */
    record CancelJob(UUID jobId) implements Inbound {
        public CancelJob {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    /**
     * Tells this worker to terminate a job at once and discard it, without expecting a status report.
     * Sent while a project or a task is being torn down.
     */
    record InterruptJob(UUID jobId, String reason) implements Inbound {
        public InterruptJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The URL an accepted artifact upload should be PUT to.
     *
     * <p>The object is recorded only once its size matches {@code contentLength}, so a worker that
     * writes a different number of bytes uploads into nothing.
     */
    record PresignedUpload(
            UUID uploadId,
            UUID jobId,
            String name,
            String objectKey,
            String url,
            String method,
            Instant expiresAt,
            long contentLength
    ) implements Inbound {
        public PresignedUpload {
            Objects.requireNonNull(uploadId, "uploadId");
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Asks this worker to allocate storage for a volume. */
    record CreateVolume(UUID volumeId, UUID projectId, String name, long sizeBytes)
            implements Inbound {
        public CreateVolume {
            Objects.requireNonNull(volumeId, "volumeId");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Asks this worker to destroy a volume and its data. */
    record DeleteVolume(UUID volumeId) implements Inbound {
        public DeleteVolume {
            Objects.requireNonNull(volumeId, "volumeId");
        }
    }

    /** One JSON-RPC frame addressed to a job's agent. */
    record AgentFrame(UUID jobId, JsonNode frame) implements Inbound {
        public AgentFrame {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(frame, "frame");
        }
    }
}
