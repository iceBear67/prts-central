package io.ib67.prts.agent.worker.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientboundMessage.Response.class, name = "result"),
        @JsonSubTypes.Type(value = ClientboundMessage.CreateJob.class, name = "createJob"),
        @JsonSubTypes.Type(value = ClientboundMessage.CancelJob.class, name = "cancelJob"),
        @JsonSubTypes.Type(value = ClientboundMessage.InterruptJob.class, name = "interruptJob"),
        @JsonSubTypes.Type(value = ClientboundMessage.PresignedUpload.class, name = "presignedUpload"),
})
public sealed interface ClientboundMessage {
    record Response(boolean ok, String message) implements ClientboundMessage {
        public Response {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * @param jobId   the id the job is known by on both sides: the worker must quote it back in
     *                {@code JobStateUpdate}, {@code UpdateJobLog} and {@code UploadArtifactRequest}.
     * @param secrets the project's secrets in the clear, beside the spec rather than inside it:
     *                {@link JobSpec#secret()} is {@code @JsonIgnore}d, which is what keeps secrets
     *                out of the {@code jsonb} column and out of every view but also drops them from
     *                the serialized spec — so the sender lifts them onto this field by hand. This
     *                message is therefore the only place a secret is serialized, and it is never
     *                persisted. Empty when the project keeps none.
     */
    record CreateJob(
            UUID requestId,
            UUID jobId,
            JobSpec spec,
            ResourceClass resourceClass,
            Map<String, String> secrets
    ) implements ClientboundMessage {
        public CreateJob {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(resourceClass, "resourceClass");
            Objects.requireNonNull(secrets, "secrets");
        }
    }

    /** Stop {@code jobId} and release its resources. The job is already terminal on our side. */
    record CancelJob(UUID jobId) implements ClientboundMessage {
        public CancelJob {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    /**
     * Stop {@code jobId} and drop anything still queued for it. Distinct from {@link CancelJob},
     * which leaves a row that still expects the worker's final word: here the job is gone, so its
     * state updates, logs and artifact uploads have nowhere to land and must not be sent.
     */
    record InterruptJob(UUID jobId, String reason) implements ClientboundMessage {
        public InterruptJob {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    record PresignedUpload(
            UUID uploadId,
            UUID jobId,
            String name,
            String objectKey,
            String url,
            String method,
            Instant expiresAt,
            long contentLength
    ) implements ClientboundMessage {
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
}
