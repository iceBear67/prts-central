package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.project.entity.JobRequest;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload to submit or queue a new job.
 *
 * <p>Also rides outbound inside {@code JobView.createRequest}. That is safe: a constraint is only
 * evaluated when something hands the record to a {@code Validator}, which happens for the annotated
 * resource parameter and never for {@link #of(JobRequest)}.
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
