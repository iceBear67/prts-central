package io.ib67.prts.agent.job;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.RequirePermission;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates caller permissions for each overridable {@link JobSpec} field via {@link RequirePermission}.
 *
 * <p>Note: The class name is inspected by {@link io.ib67.prts.openapi.EndpointOASFilter}
 * to document override permissions in OpenAPI specifications.
 */
@ApplicationScoped
public class JobSpecOverridePermissions implements JobSpecOverrideAuthorizer {

    @Override
    @RequirePermission(Perm.JOB_SPEC_IMAGE)
    public String image(String value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_SPEC_DESCRIPTION)
    public String description(String value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_SPEC_ENVIRONMENT)
    public Map<String, String> environment(Map<String, String> value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_SPEC_LABELS)
    public Map<String, String> labels(Map<String, String> value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_SPEC_COMMAND)
    public List<String> command(List<String> value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_SPEC_VOLUMES)
    public Map<UUID, JobSpec.VolumeSpec> volumes(Map<UUID, JobSpec.VolumeSpec> value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_SPEC_TIMEOUT)
    public long timeout(long value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_SPEC_LOCK)
    public String lock(String value) {
        return value;
    }

    @Override
    @RequirePermission(Perm.JOB_RESOURCE_CLASS)
    public String resourceClass(String name) {
        return name;
    }
}
