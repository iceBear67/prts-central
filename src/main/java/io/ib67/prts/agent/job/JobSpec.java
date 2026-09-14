package io.ib67.prts.agent.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.quarkus.security.ForbiddenException;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Container execution specification for a job.
 *
 * @param description What this spec runs, for readers; empty string if none was given.
 * @param lock        Mutual exclusion lock name scoped to the project; empty string if no lock is required.
 * @param secret      Decrypted project secrets attached at dispatch time. Excluded from serialization to avoid persistence or exposure.
 */
public record JobSpec(
        String image,
        String description,
        Map<String, String> environment,
        Map<String, String> labels,
        List<String> command,
        Map<UUID, VolumeSpec> volumes,
        long timeout,
        String lock,
        @JsonIgnore Map<String, String> secret
) {
    // Shared size limits across JobSpecRequest, JobSpecOverride, and TaskScope.
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_ENTRY_KEY_LENGTH = 128;
    public static final int MAX_ENTRY_VALUE_LENGTH = 4096;
    public static final int MAX_COMMAND_ARGUMENTS = 256;
    public static final int MAX_ARGUMENT_LENGTH = 4096;
    public static final int MAX_VOLUMES = 16;
    public static final int MAX_IMAGE_LENGTH = 512;
    public static final int MAX_LOCK_LENGTH = 200;
    /** Maximum execution timeout allowed for a job (7 days). */
    public static final long MAX_TIMEOUT_SECONDS = 604800;

    public JobSpec {
        Objects.requireNonNull(image, "image");
        environment = Objects.requireNonNullElse(environment, Map.of());
        labels = Objects.requireNonNullElse(labels, Map.of());
        command = Objects.requireNonNullElse(command, List.of());
        volumes = Objects.requireNonNullElse(volumes, Map.of());
        secret = Objects.requireNonNullElse(secret, Map.of());
        description = description == null || description.isBlank() ? "" : description;
        lock = lock == null || lock.isBlank() ? "" : lock;
    }

    /**
     * @param mountPoint where the worker binds the volume inside the container; see {@link #MOUNT_POINT}
     */
    public record VolumeSpec(
            @Size(max = VolumeSpec.MAX_MOUNT_POINT_LENGTH,
                    message = "mountPoint must be at most {max} characters")
            @Pattern(regexp = VolumeSpec.MOUNT_POINT, message = VolumeSpec.MOUNT_POINT_REJECTED)
            String mountPoint,
            @PositiveOrZero(message = "volume sizeLimit must be >= 0") long sizeLimit
    ) {
        /**
         * Absolute path pattern rejecting {@code .}, {@code ..}, empty segments, trailing slashes,
         * whitespace, and control characters to ensure safe container bind mounts.
         */
        public static final String MOUNT_POINT = "(/(?!\\.\\.?(?=/|$))[^/\\x00-\\x20\\x7f]+)+";

        /** Maximum allowed length for a mount point path. */
        public static final int MAX_MOUNT_POINT_LENGTH = 512;

        /** Shared with {@code AttachVolumeRequest}: one rule, so one way to say it was broken. */
        public static final String MOUNT_POINT_REJECTED =
                "mountPoint must be an absolute path of non-empty segments, "
                        + "without '.', '..', whitespace or a trailing slash";

        public VolumeSpec {
            Objects.requireNonNull(mountPoint, "mountPoint");
        }
    }

    /**
     * Returns a copy of this spec with the given secrets attached.
     */
    public JobSpec withSecret(Map<String, String> secret) {
        return new JobSpec(
                image, description, environment, labels, command, volumes, timeout, lock, secret);
    }

    // Redacted toString to prevent logging decrypted secret values.
    @Override
    public String toString() {
        return "JobSpec[image=" + image + ", description=" + description
                + ", environment=" + environment + ", labels=" + labels
                + ", command=" + command + ", volumes=" + volumes + ", timeout=" + timeout
                + ", lock=" + lock + ", secret=" + secret.size() + " entries]";
    }

    /**
     * Validates that every requested volume exists, belongs to the specified project, is ready, and is
     * hosted on a single worker.
     */
    public void requireVolumesIn(UUID projectId) {
        if (volumes.isEmpty()) {
            return;
        }
        var rows = WorkerVolume.listByIds(volumes.keySet());
        if (rows.size() != volumes.size()) {
            throw new BadRequestException("unknown volume in job spec");
        }
        UUID host = null;
        for (var row : rows) {
            if (!row.getProject().getId().equals(projectId)) {
                throw new ForbiddenException("volume " + row.getId() + " belongs to another project");
            }
            if (!row.getState().isUsable()) {
                throw new BadRequestException(
                        "volume " + row.getId() + " is not ready: " + row.getState());
            }
            var worker = row.getWorker().getId();
            if (host == null) {
                host = worker;
            } else if (!host.equals(worker)) {
                throw new BadRequestException(
                        "a job cannot mount volumes from more than one worker: "
                                + host + " and " + worker);
            }
        }
    }
}
