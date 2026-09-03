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
 * What a job runs. Everything but {@link #secret} is persisted as {@code jsonb} and published in
 * views.
 *
 * @param lock   The lock two jobs must never hold at once, scoped to the project the job belongs to;
 *               empty means no mutual exclusion.
 * @param secret The project's secrets in the clear, attached by {@code JobService} to the copy of
 *               the spec that goes to the scheduler and to no other; empty on every other copy.
 *               {@code @JsonIgnore} is what keeps them out of the {@code jsonb} column and out of
 *               every view, so a spec that carries them still cannot leak them. It also keeps them
 *               out of the spec the worker receives, which is why
 *               {@link io.ib67.prts.agent.worker.message.ClientboundMessage.CreateJob} carries a
 *               {@code secrets} field of its own, lifted from here when the message is sent.
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
    /**
     * Nothing here is nullable. A field that a stored row, a template or an override leaves out
     * arrives as {@code null} and is normalized to its empty value, so no reader has to tell absent
     * from empty — and a blank lock is the one way to say "not exclusive".
     */
    public JobSpec {
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
    ){ }

    /**
     * This spec plus {@code secret}. Nothing overrides that field, so attaching the project's
     * secrets to a spec on its way to a worker is the only way one is ever set.
     */
    public JobSpec withSecret(Map<String, String> secret) {
        return new JobSpec(image, environment, labels, command, volumes, timeout, lock, secret);
    }

    /** Hand-written so that {@link #secret} cannot reach a log through a {@code %s}. */
    @Override
    public String toString() {
        return "JobSpec[image=" + image + ", environment=" + environment + ", labels=" + labels
                + ", command=" + command + ", volumes=" + volumes + ", timeout=" + timeout
                + ", lock=" + lock + ", secret=" + secret.size() + " entries]";
    }

    /**
     * Every volume must exist and belong to {@code projectId} — not merely to a project the caller is
     * in: this job's logs and artifacts are readable by everyone who can view <em>its</em> project.
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
