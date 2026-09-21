package io.ib67.prts.worker.mock.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * One message on its way to the control plane.
 *
 * <p>An envelope with no {@code replyTo} is asked and gets exactly one answer back, an envelope
 * whose {@code replyTo} is this one's {@code id}. An envelope that carries a {@code replyTo} is
 * itself an answer and is never answered again. The rule holds in both directions.
 */
public record OutboundEnvelope(UUID id, UUID replyTo, Outbound message) {
    public OutboundEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
    }

    /** A message this worker sends on its own initiative. */
    public static OutboundEnvelope of(Outbound message) {
        return new OutboundEnvelope(UUID.randomUUID(), null, message);
    }

    /** The answer to a message the control plane sent. */
    public static OutboundEnvelope answering(UUID replyTo, Outbound message) {
        return new OutboundEnvelope(UUID.randomUUID(), Objects.requireNonNull(replyTo, "replyTo"), message);
    }
}
