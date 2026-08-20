package io.ib67.prts.dto;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecTemplate;
import jakarta.annotation.Nullable;

import java.util.UUID;

public record JobSpecTemplateView(
        UUID id,
        String name,
        JobSpec spec,
        @Nullable String resourceClass
) {
    public static JobSpecTemplateView of(JobSpecTemplate template) {
        var resourceClass = template.getResourceClass();
        return new JobSpecTemplateView(
                template.getId(),
                template.getName(),
                template.getSpec(),
                resourceClass == null ? null : resourceClass.getName());
    }
}
