package io.ib67.prts.agent.runner.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "id"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientboundMessage.Response.class, name = "result"),
})
public sealed interface ClientboundMessage {
    record Response(boolean ok, String message) implements ClientboundMessage {}
}
