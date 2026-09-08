package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.project.entity.JobRequest;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload to submit or queue a new job.
 *
 * <p>Also embedded in {@code JobView.createRequest} to describe the original request parameters.
 */
public record CreateJobRequest(
        @NotNull(message = "templateId is required") UUID templateId,
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
