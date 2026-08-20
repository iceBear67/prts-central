package io.ib67.prts.agent.job;

import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public record JobSpec(
        String image,
        Map<String, String> environment,
        Map<String, String> secrets,
        Map<String, String> labels,
        List<String> command,
        Map<UUID, VolumeSpec> volumes,
        long timeout
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
        return new JobSpec(image, env, secrets, labels, command, volumes, timeout);
    }

    /**
     * Each volume must exist, and {@code allowedProject} must accept its project.
     */
    public void requireVolumeAccess(Predicate<UUID> allowedProject) {
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
            if (!allowedProject.test(row.getProject().getId())) {
                throw new ForbiddenException("missing project permission");
            }
        }
    }
}
