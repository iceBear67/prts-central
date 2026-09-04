package io.ib67.prts.agent.worker.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.worker.RegisteredWorker;
import io.ib67.prts.project.JobState;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Every required component is checked in the canonical constructor, so a message that would carry a
 * hole into the database or the scheduler fails to decode instead. A decode failure reaches the
 * worker as a {@code Response} — see {@code WorkerWebSocket.onError}.
 */
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
     * @param info     {@code null} means unbounded resources and no pending work we know of.
     */
    record Register(UUID workerId, String name, @Nullable RegisteredWorker.Info info)
            implements ServerboundMessage {
        public Register {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Only the job is required: a line may carry no topic and no text, and an absent error is false. */
    record UpdateJobLog(UUID jobId, @Nullable String topic, @Nullable String message, @Nullable Boolean error)
            implements ServerboundMessage {
        public UpdateJobLog {
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    record UpdateResourceInfo(RegisteredWorker.Info info) implements ServerboundMessage {
        public UpdateResourceInfo {
            Objects.requireNonNull(info, "info");
        }
    }

    /**
     * Acknowledges {@code ClientboundMessage.CreateJob}. Carries no job id: the job is identified by
     * the one we sent, and {@code requestId} identifies which attempt is being acknowledged.
     */
    record JobCreated(UUID requestId) implements ServerboundMessage {
        public JobCreated {
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    record JobStateUpdate(UUID jobId, JobState state) implements ServerboundMessage {
        public JobStateUpdate {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(state, "state");
        }
    }

    record UploadArtifactRequest(UUID jobId, String name, long sizeBytes) implements ServerboundMessage {
        public UploadArtifactRequest {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(name, "name");
        }
    }
}
