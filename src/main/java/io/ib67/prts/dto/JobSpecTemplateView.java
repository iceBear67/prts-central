package io.ib67.prts.dto;

import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import jakarta.annotation.Nullable;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * @param projectId     the project owning the template, or {@code null} for one every project may use.
 * @param spec          what the template runs, or {@code null} for a caller who may not see it: id and
 *                      name are enough to create from, the content is what {@code job:template:read}
 *                      buys. {@code resourceClass} is content too and goes with it.
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
