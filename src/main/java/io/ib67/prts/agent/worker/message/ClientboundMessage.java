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
     * Instructs a worker to execute a job.
     *
     * @param jobId   Job identifier.
     * @param secrets Decrypted secrets needed for the job.
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

    /** Requests the worker to cancel a running job. */
    record CancelJob(UUID jobId) implements ClientboundMessage {
        public CancelJob {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    /** Instructs the worker to immediately terminate and discard a job. */
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
