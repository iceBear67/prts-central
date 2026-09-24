package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.message.ServerboundMessage;
import lombok.experimental.UtilityClass;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@UtilityClass
public class WorkerEvent {
    public static final String OFFLINE = "worker-offline"; //type: ID
    public static final String SERVERBOUND_EVENT = "worker-event"; //type: C2S
    public static final String ASSIGNED = "worker-job-assigned"; //type: Assignment

    public record C2S(UUID worker, ServerboundMessage message) {}

    /** A job offered to a worker, announced before the job is sent. */
    public record Assignment(UUID job, UUID worker) {
        public Assignment {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(worker, "worker");
        }
    }
}
