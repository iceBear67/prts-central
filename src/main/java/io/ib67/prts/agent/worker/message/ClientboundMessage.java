package io.ib67.prts.agent.worker.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.job.JobSpec;

import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "id"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientboundMessage.Response.class, name = "result"),
        @JsonSubTypes.Type(value = ClientboundMessage.CreateJob.class, name = "createJob"),
        @JsonSubTypes.Type(value = ClientboundMessage.PresignedUpload.class, name = "presignedUpload"),
})
public sealed interface ClientboundMessage {
    record Response(boolean ok, String message) implements ClientboundMessage {
    }

    record CreateJob(UUID requestId, JobSpec spec) implements ClientboundMessage {
    }

    record PresignedUpload(
            UUID uploadId,
            UUID jobId,
            String objectKey,
            String url,
            String method,
            Instant expiresAt,
            long contentLength
    ) implements ClientboundMessage {
    }
}
