package io.ib67.prts.agent.job;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authorizes individual job spec override fields before they are applied.
 *
 * <p>Each method checks whether the caller is permitted to override that field
 * and returns the value, or throws a security exception.
 */
public interface JobSpecOverrideAuthorizer {

    String image(String value);

    String description(String value);

    Map<String, String> environment(Map<String, String> value);

    Map<String, String> labels(Map<String, String> value);

    List<String> command(List<String> value);

    Map<UUID, JobSpec.VolumeSpec> volumes(Map<UUID, JobSpec.VolumeSpec> value);

    long timeout(long value);

    String lock(String value);

    String resourceClass(String name);
}
