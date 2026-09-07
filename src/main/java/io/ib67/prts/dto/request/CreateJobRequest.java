package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.project.entity.JobRequest;
import jakarta.annotation.Nullable;

import java.util.UUID;

/** The wire shape of a {@link JobRequest}; the resource checks it and converts. */
public record CreateJobRequest(
        UUID templateId,
        @Nullable JobSpecOverride override,
        @Nullable String resourceClass
        //todo link outer resources
) {
    public JobRequest toRequest() {
        return new JobRequest(templateId, override, resourceClass);
    }

    public static CreateJobRequest of(JobRequest request) {
        return new CreateJobRequest(request.templateId(), request.override(), request.resourceClass());
    }
}
