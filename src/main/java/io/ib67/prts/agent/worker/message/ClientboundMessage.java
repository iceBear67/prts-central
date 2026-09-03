package io.ib67.prts.agent.worker.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "id"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientboundMessage.Response.class, name = "result"),
        @JsonSubTypes.Type(value = ClientboundMessage.CreateJob.class, name = "createJob"),
        @JsonSubTypes.Type(value = ClientboundMessage.CancelJob.class, name = "cancelJob"),
        @JsonSubTypes.Type(value = ClientboundMessage.PresignedUpload.class, name = "presignedUpload"),
})
public sealed interface ClientboundMessage {
    record Response(boolean ok, String message) implements ClientboundMessage {
    }

    /**
     * @param jobId   the id the job is known by on both sides: the worker must quote it back in
     *                {@code JobStateUpdate}, {@code UpdateJobLog} and {@code UploadArtifactRequest}.
     * @param secrets the project's secrets in the clear, beside the spec rather than inside it:
     *                {@link JobSpec#secret()} is {@code @JsonIgnore}d, which is what keeps secrets
     *                out of the {@code jsonb} column and out of every view but also drops them from
     *                the serialized spec — so the sender lifts them onto this field by hand. This
     *                message is therefore the only place a secret is serialized, and it is never
     *                persisted.
     */
    record CreateJob(
            UUID requestId,
            UUID jobId,
            JobSpec spec,
            ResourceClass resourceClass,
            @Nullable Map<String, String> secrets
    ) implements ClientboundMessage {
    }

    /** Stop {@code jobId} and release its resources. The job is already terminal on our side. */
    record CancelJob(UUID jobId) implements ClientboundMessage {
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
    }
}
