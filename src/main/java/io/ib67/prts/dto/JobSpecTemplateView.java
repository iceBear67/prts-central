package io.ib67.prts.dto;

import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import jakarta.annotation.Nullable;

import java.util.UUID;

/** @param projectId the owning project, or {@code null} for a global template. */
public record JobSpecTemplateView(
        UUID id,
        String name,
        @Nullable UUID projectId,
        JobView.SpecView spec,
        @Nullable String resourceClass
) {
    public static JobSpecTemplateView of(JobSpecTemplate template) {
        var resourceClass = template.getResourceClass();
        var project = template.getProject();
        return new JobSpecTemplateView(
                template.getId(),
                template.getName(),
                project == null ? null : project.getId(),
                JobView.SpecView.of(template.getSpec()),
                resourceClass == null ? null : resourceClass.getName());
    }
}
