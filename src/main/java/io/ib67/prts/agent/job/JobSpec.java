package io.ib67.prts.agent.job;

import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.BadRequestException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a job runs. Carries <em>no secrets</em>: this is persisted as {@code jsonb} and published in
 * views, so secrets are to be injected per project at dispatch time instead.
 *
 * @param lock Two jobs naming the same lock never run at the same time. Scoped to the project the
 *             job belongs to; {@code null} or blank means no mutual exclusion.
 */
public record JobSpec(
        String image,
        Map<String, String> environment,
        Map<String, String> labels,
        List<String> command,
        Map<UUID, VolumeSpec> volumes,
        long timeout,
        @Nullable String lock
) {
    public static final String PROMPT_ENV = "PRTS_PROMPT";

    public record VolumeSpec(
            String mountPoint,
            long sizeLimit
    ){ }

    public JobSpec withPrompt(String prompt) {
        if (prompt == null) {
            return this;
        }
        var env = environment == null ? new HashMap<String, String>() : new HashMap<>(environment);
        env.put(PROMPT_ENV, prompt);
        return new JobSpec(image, env, labels, command, volumes, timeout, lock);
    }

    /** The lock to contend for, or {@code null} when this spec is not mutually exclusive. */
    @Nullable
    public String normalizedLock() {
        return lock == null || lock.isBlank() ? null : lock;
    }

    /**
     * Every volume must exist and belong to {@code projectId} — not merely to a project the caller is
     * in: this job's logs and artifacts are readable by everyone who can view <em>its</em> project.
     */
    public void requireVolumesIn(UUID projectId) {
        if (volumes == null || volumes.isEmpty()) {
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
