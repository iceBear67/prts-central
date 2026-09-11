package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.job.entity.JobRequest;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * Request payload to submit or queue a new job.
 *
 * <p>Also embedded in {@code JobView.createRequest} to describe the original request parameters.
 *
 * @param taskId task whose scope the job runs under, or null to run outside any task
 */
public record CreateJobRequest(
        @NotNull(message = "templateId is required") UUID templateId,
        @Nullable @Valid JobSpecOverride override,
        @Nullable @Pattern(regexp = CreateResourceClassRequest.NAME,
                message = "resourceClass is not a valid resource class name") String resourceClass,
        @Nullable UUID taskId
) {
    public JobRequest toRequest() {
        return new JobRequest(templateId, override, resourceClass, taskId);
    }

    public static CreateJobRequest of(JobRequest request) {
        return new CreateJobRequest(
                request.templateId(), request.override(), request.resourceClass(), request.taskId());
    }
}
