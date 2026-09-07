package io.ib67.prts.agent.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Container execution specification for a job.
 *
 * @param lock   Mutual exclusion lock name scoped to the project; empty string if no lock is required.
 * @param secret Decrypted project secrets attached at dispatch time. Excluded from serialization to avoid persistence or exposure.
 */
public record JobSpec(
        String image,
        Map<String, String> environment,
        Map<String, String> labels,
        List<String> command,
        Map<UUID, VolumeSpec> volumes,
        long timeout,
        String lock,
        @JsonIgnore Map<String, String> secret
) {
    public JobSpec {
        Objects.requireNonNull(image, "image");
        environment = Objects.requireNonNullElse(environment, Map.of());
        labels = Objects.requireNonNullElse(labels, Map.of());
        command = Objects.requireNonNullElse(command, List.of());
        volumes = Objects.requireNonNullElse(volumes, Map.of());
        secret = Objects.requireNonNullElse(secret, Map.of());
        lock = lock == null || lock.isBlank() ? "" : lock;
    }

    public record VolumeSpec(
            String mountPoint,
            long sizeLimit
    ) {
        public VolumeSpec {
            Objects.requireNonNull(mountPoint, "mountPoint");
        }
    }

    /**
     * Returns a copy of this spec with the given secrets attached.
     */
    public JobSpec withSecret(Map<String, String> secret) {
        return new JobSpec(image, environment, labels, command, volumes, timeout, lock, secret);
    }

    // Redacted toString to prevent logging decrypted secret values.
    @Override
    public String toString() {
        return "JobSpec[image=" + image + ", environment=" + environment + ", labels=" + labels
                + ", command=" + command + ", volumes=" + volumes + ", timeout=" + timeout
                + ", lock=" + lock + ", secret=" + secret.size() + " entries]";
    }

    /**
     * Validates that all requested volumes exist and belong to the specified project.
     */
    public void requireVolumesIn(UUID projectId) {
        if (volumes.isEmpty()) {
            return;
        }
        if (volumes.containsKey(null)) {
            throw new BadRequestException("volume id is required");
        }
        var rows = WorkerVolume.listByIds(volumes.keySet());
        if (rows.size() != volumes.size()) {
            throw new BadRequestException("unknown volume in job spec");
        }
        for (var row : rows) {
            if (!row.getProject().getId().equals(projectId)) {
                throw new ForbiddenException("volume " + row.getId() + " belongs to another project");
            }
        }
    }
}
