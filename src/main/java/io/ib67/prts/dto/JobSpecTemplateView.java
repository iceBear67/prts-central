package io.ib67.prts.dto;

import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import jakarta.annotation.Nullable;

import java.util.UUID;

public record JobSpecTemplateView(
        UUID id,
        String name,
        JobView.SpecView spec,
        @Nullable String resourceClass
) {
    public static JobSpecTemplateView of(JobSpecTemplate template) {
        var resourceClass = template.getResourceClass();
        return new JobSpecTemplateView(
                template.getId(),
                template.getName(),
                JobView.SpecView.of(template.getSpec()),
                resourceClass == null ? null : resourceClass.getName());
    }
}
