package io.ib67.prts.worker.mock.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * One message as it arrived from the control plane.
 *
 * <p>The correspondence rules are the ones {@link OutboundEnvelope} documents.
 */
public record InboundEnvelope(UUID id, UUID replyTo, Inbound message) {
    public InboundEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
    }

    /** True when this is an answer to something this worker asked. */
    public boolean isAnswer() {
        return replyTo != null;
    }
}
