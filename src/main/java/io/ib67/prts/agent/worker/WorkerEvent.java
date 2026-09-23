package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.message.ServerboundMessage;
import lombok.experimental.UtilityClass;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@UtilityClass
public class WorkerEvent {
    public static final String ONLINE = "worker-online"; //type: Worker
    public static final String OFFLINE = "worker-offline"; //type: ID
    public static final String SERVERBOUND_EVENT = "worker-event"; //type: C2S

    public record C2S(UUID worker, ServerboundMessage message) {}
}
