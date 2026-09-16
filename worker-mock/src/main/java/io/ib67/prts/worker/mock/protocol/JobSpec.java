package io.ib67.prts.worker.mock.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The container specification a worker receives, as the wire writes it.
 *
 * <p>Only the fields a worker acts on are modelled. Project secrets are not part of the spec: the
 * control plane sends them beside it, in {@link Inbound.CreateJob#secrets()}, so that they are never
 * persisted with the job.
 */
public record JobSpec(
        String image,
        String description,
        Map<String, String> environment,
        Map<String, String> labels,
        List<String> command,
        Map<UUID, VolumeSpec> volumes,
        long timeout,
        String lock
) {
    public JobSpec {
        Objects.requireNonNull(image, "image");
        description = description == null ? "" : description;
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        command = command == null ? List.of() : List.copyOf(command);
        volumes = volumes == null ? Map.of() : Map.copyOf(volumes);
        lock = lock == null ? "" : lock;
    }

    /**
     * Where a volume is bound inside the container.
     *
     * @param sizeLimit how many bytes of the volume the job may use
     */
    public record VolumeSpec(String mountPoint, long sizeLimit) {
        public VolumeSpec {
            Objects.requireNonNull(mountPoint, "mountPoint");
        }
    }
}
