package io.ib67.prts.agent.runner.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.runner.RunnerInfo;
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
})
public sealed interface ServerboundMessage {
    record Register(UUID id, String name, RunnerInfo info) implements ServerboundMessage {
    }

    record UpdateJobLog(UUID jobId, String topic, String message, Boolean error) implements ServerboundMessage {
    }

    record UpdateResourceInfo(RunnerInfo info) implements ServerboundMessage {
    }

    record JobCreated(UUID requestId, UUID jobId) implements ServerboundMessage {
    }

    record JobStateUpdate(UUID jobId, JobState state) implements ServerboundMessage {
    }
}
    