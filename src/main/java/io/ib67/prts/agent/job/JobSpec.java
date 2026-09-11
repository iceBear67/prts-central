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
    // Shared by every inbound shape that builds a spec (JobSpecRequest, JobSpecOverride, TaskScope):
    // each value is stored as jsonb and resent on every dispatch, so an unbounded one is storage
    // amplification bought with a single job:create.
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_ENTRY_KEY_LENGTH = 128;
    public static final int MAX_ENTRY_VALUE_LENGTH = 4096;
    public static final int MAX_COMMAND_ARGUMENTS = 256;
    public static final int MAX_ARGUMENT_LENGTH = 4096;
    public static final int MAX_VOLUMES = 16;
    public static final int MAX_IMAGE_LENGTH = 512;
    public static final int MAX_LOCK_LENGTH = 200;
    /** Seven days. A job outliving that is a stuck job, not a long one. */
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
         * An absolute path of clean segments: no {@code .}, no {@code ..}, no empty segment, no
         * trailing slash, and no whitespace or control character anywhere.
         *
         * <p>The control plane never resolves this path — it hands it to a worker, which turns it into
         * a bind mount. So a traversing or ambiguous path has to be refused here, before it is stored
         * and replayed on every dispatch. Not being the component that dereferences it is exactly why
         * the control plane cannot be the one that lets it through.
         */
        public static final String MOUNT_POINT = "(/(?!\\.\\.?(?=/|$))[^/\\x00-\\x20\\x7f]+)+";

        /** Longest mount point accepted. Well past any real path, short of a storage amplifier. */
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
     *
     * <p>The affinity check is here rather than left to placement because {@code WorkerScheduler} has no
     * way to report it: every reason it cannot place a job collapses into one message, so a spec naming
     * volumes on two workers would be requeued with backoff until it expired, saying only that no worker
     * could take it.
     *
     * <p>Everything here is state that can change between enqueue and dispatch, which is why it is
     * rechecked rather than trusted from the request. The mount point cannot, and is enforced once by
     * {@link VolumeSpec#MOUNT_POINT} where the value enters.
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
