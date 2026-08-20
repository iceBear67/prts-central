package io.ib67.prts.agent.job;

import io.ib67.prts.Perms;
import io.ib67.prts.auth.RequirePermission;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class JobSpecOverridePermissions {

    @RequirePermission(Perms.JOB_SPEC_IMAGE)
    public String image(String value) {
        return value;
    }

    @RequirePermission(Perms.JOB_SPEC_ENVIRONMENT)
    public Map<String, String> environment(Map<String, String> value) {
        return value;
    }

    @RequirePermission(Perms.JOB_SPEC_SECRETS)
    public Map<String, String> secrets(Map<String, String> value) {
        return value;
    }

    @RequirePermission(Perms.JOB_SPEC_LABELS)
    public Map<String, String> labels(Map<String, String> value) {
        return value;
    }

    @RequirePermission(Perms.JOB_SPEC_COMMAND)
    public List<String> command(List<String> value) {
        return value;
    }

    @RequirePermission(Perms.JOB_SPEC_VOLUMES)
    public Map<UUID, JobSpec.VolumeSpec> volumes(Map<UUID, JobSpec.VolumeSpec> value) {
        return value;
    }

    @RequirePermission(Perms.JOB_SPEC_TIMEOUT)
    public long timeout(long value) {
        return value;
    }

    @RequirePermission(Perms.JOB_RESOURCE_CLASS)
    public String resourceClass(String name) {
        return name;
    }
}
