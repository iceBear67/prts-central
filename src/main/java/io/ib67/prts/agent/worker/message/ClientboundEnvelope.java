package io.ib67.prts.agent.worker.message;

import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * One message on its way to a worker.
 *
 * <p>An envelope with no {@code replyTo} is asked and must be answered exactly once, with an
 * envelope whose {@code replyTo} is this one's {@code id}. An envelope that carries a {@code replyTo}
 * is itself an answer and is never answered again.
 */
public record ClientboundEnvelope(UUID id, @Nullable UUID replyTo, ClientboundMessage message) {
    public ClientboundEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
    }

    /** A message the control plane sends on its own initiative. */
    public static ClientboundEnvelope of(ClientboundMessage message) {
        return new ClientboundEnvelope(UUID.randomUUID(), null, message);
    }
}
