package io.ib67.prts.dto;

import io.ib67.prts.agent.job.JobSpecOverride;
import jakarta.annotation.Nullable;

import java.util.UUID;

public record CreateJobRequest(
        UUID templateId,
        @Nullable JobSpecOverride override,
        @Nullable String resourceClass,
        @Nullable String prompt
        //todo link outer resources
) {
}
