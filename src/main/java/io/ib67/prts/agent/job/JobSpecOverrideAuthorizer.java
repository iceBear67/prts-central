package io.ib67.prts.agent.job;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clears one override field and hands the value back, so that {@link JobSpecOverride#applyTo} decides
 * field by field. {@link JobSpecOverridePermissions} is the implementation that asks for a permission;
 * it is a parameter rather than a fixed dependency because a create may be authorized in the request
 * it arrived in and submitted later, off a thread where no such check could run — see
 * {@code JobService#authorizeCreate}.
 */
public interface JobSpecOverrideAuthorizer {

    String image(String value);

    Map<String, String> environment(Map<String, String> value);

    Map<String, String> labels(Map<String, String> value);

    List<String> command(List<String> value);

    Map<UUID, JobSpec.VolumeSpec> volumes(Map<UUID, JobSpec.VolumeSpec> value);

    long timeout(long value);

    String lock(String value);

    String resourceClass(String name);
}
