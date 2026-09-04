package io.ib67.prts.agent.worker.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.worker.RegisteredWorker;
import io.ib67.prts.project.JobState;

import java.util.UUID;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "id"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerboundMessage.Register.class, name = "register"),
        @JsonSubTypes.Type(value = ServerboundMessage.UpdateJobLog.class, name = "updateJobLog"),
        @JsonSubTypes.Type(value = ServerboundMessage.UpdateResourceInfo.class, name = "updateResourceInfo"),
        @JsonSubTypes.Type(value = ServerboundMessage.JobCreated.class, name = "jobCreated"),
        @JsonSubTypes.Type(value = ServerboundMessage.JobStateUpdate.class, name = "jobStateUpdate"),
        @JsonSubTypes.Type(value = ServerboundMessage.UploadArtifactRequest.class, name = "uploadArtifactRequest"),
})
public sealed interface ServerboundMessage {
    /**
     * @param workerId not {@code id}: that name is taken by the type property above, which Jackson
     *                 consumes to pick the subtype and — {@code visible} being false — never binds,
     *                 so a component of that name would arrive null on every register.
     */
    record Register(UUID workerId, String name, RegisteredWorker.Info info) implements ServerboundMessage {
    }

    record UpdateJobLog(UUID jobId, String topic, String message, Boolean error) implements ServerboundMessage {
    }

    record UpdateResourceInfo(RegisteredWorker.Info info) implements ServerboundMessage {
    }

    /**
     * Acknowledges {@code ClientboundMessage.CreateJob}. Carries no job id: the job is identified by
     * the one we sent, and {@code requestId} identifies which attempt is being acknowledged.
     */
    record JobCreated(UUID requestId) implements ServerboundMessage {
    }

    record JobStateUpdate(UUID jobId, JobState state) implements ServerboundMessage {
    }

    record UploadArtifactRequest(UUID jobId, String name, long sizeBytes) implements ServerboundMessage {
    }
}
