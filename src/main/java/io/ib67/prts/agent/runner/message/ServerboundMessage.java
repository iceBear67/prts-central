package io.ib67.prts.agent.runner.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.ib67.prts.agent.runner.RunnerService;

import java.util.UUID;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "id"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerboundMessage.Register.class, name = "register"),
        @JsonSubTypes.Type(value = ServerboundMessage.UpdateJobLog.class, name = "updateJobLog"),
})
public sealed interface ServerboundMessage {
    record Register(UUID id, String name, RunnerService.ResourceInfo info) implements ServerboundMessage {
    }

    record UpdateJobLog(UUID jobId, String topic, String message, Boolean error) implements ServerboundMessage {
    }
}
