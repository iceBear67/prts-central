package io.ib67.prts.agent.worker.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.worker.RegisteredWorker;
import io.ib67.prts.project.entity.JobState;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Messages sent from workers to the central control plane over WebSocket.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
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
    record Register(UUID workerId, String name, @Nullable RegisteredWorker.Info info)
            implements ServerboundMessage {
        public Register {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(name, "name");
        }
    }

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
     * Acknowledges receipt of a job creation request.
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
