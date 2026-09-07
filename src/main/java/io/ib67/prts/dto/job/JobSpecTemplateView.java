package io.ib67.prts.dto.job;

import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import jakarta.annotation.Nullable;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * View representing a job spec template.
 *
 * @param projectId Owning project ID, or null if globally available.
 * @param spec      The job spec definition, or null if hidden by caller permissions.
 */
public record JobSpecTemplateView(
        UUID id,
        String name,
        @Nullable UUID projectId,
        @Nullable JobView.SpecView spec,
        @Nullable String resourceClass
) {
    public JobSpecTemplateView {
        requireNonNull(id, "id");
        requireNonNull(name, "name");
    }

    public static JobSpecTemplateView of(JobSpecTemplate template, boolean withSpec) {
        var resourceClass = template.getResourceClass();
        var project = template.getProject();
        return new JobSpecTemplateView(
                template.getId(),
                template.getName(),
                project == null ? null : project.getId(),
                withSpec ? JobView.SpecView.of(template.getSpec()) : null,
                withSpec && resourceClass != null ? resourceClass.getName() : null);
    }
}
