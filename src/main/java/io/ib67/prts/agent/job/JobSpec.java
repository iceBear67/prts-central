package io.ib67.prts.agent.job;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JobSpec(
        String image,
        Map<String, String> environment,
        Map<String, String> labels,
        int maxCpus,
        long maxMemoryBytes,
        long maxEphemeralStorages,
        List<String> command,
        Map<UUID, VolumeSpec> volumes,
        long timeout
) {
    public record VolumeSpec(
            String mountPoint,
            long sizeLimit
    ){ }

}
