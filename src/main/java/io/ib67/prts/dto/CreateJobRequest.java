package io.ib67.prts.dto;

import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.project.Job;
import jakarta.annotation.Nullable;

import java.util.UUID;

public record CreateJobRequest(
        UUID templateId,
        @Nullable JobSpecOverride override,
        @Nullable String resourceClass,
        @Nullable String prompt
        //todo link outer resources
) {
    /**
     * The request that reproduces a job, for the client to post back to {@code /create} — how a
     * re-run goes through the same gates as the original create. The resource class is the one that
     * actually ran, not what the requester typed: naming it pins the re-run to it even if the
     * template has moved on since. {@code null} when some other path made the job and there is
     * nothing to replay.
     *
     * <p>Reads only what a detached job carries, so it is safe after the transaction closed.
     */
    @Nullable
    public static CreateJobRequest of(Job job) {
        if (job.getTemplateId() == null) {
            return null;
        }
        return new CreateJobRequest(
                job.getTemplateId(),
                job.getCreateOverride(),
                job.getResourceClass().getName(),
                job.getCreatePrompt());
    }
}
