package io.ib67.prts.agent.worker.message;

import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * One message as it arrived from a worker.
 *
 * <p>The correspondence rules are the ones {@link ClientboundEnvelope} documents; they hold in both
 * directions.
 */
public record ServerboundEnvelope(UUID id, @Nullable UUID replyTo, ServerboundMessage message) {
    public ServerboundEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
    }

    /** The answer to this message, addressed back to the worker that sent it. */
    public ClientboundEnvelope reply(ClientboundMessage answer) {
        return new ClientboundEnvelope(UUID.randomUUID(), id, answer);
    }
}
