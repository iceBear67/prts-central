package io.ib67.prts.job;

import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.pending.PendingJobState;
import jakarta.annotation.Nullable;

/**
 * A state one row of a merged job listing can be in: the union of {@link JobState} and
 * {@link PendingJobState}.
 *
 * <p>{@code GET /project/{projectId}/job} answers {@code JobStatusView}, so a caller narrowing it
 * holds neither enum on its own — a placed job is in one, a queue entry in the other, and
 * {@code FAILED} and {@code CANCELLED} are in both. A member selects whichever halves recognise it;
 * the table that does not contributes nothing to the page.
 */
public enum JobStatus {
    PENDING(JobState.PENDING, null),
    RUNNING(JobState.RUNNING, null),
    SUCCESS(JobState.SUCCESS, null),
    FAILED(JobState.FAILED, PendingJobState.FAILED),
    CANCELLED(JobState.CANCELLED, PendingJobState.CANCELLED),
    QUEUED(null, PendingJobState.QUEUED),
    DISPATCHING(null, PendingJobState.DISPATCHING),
    /** An entry that became a job is listed as that job, so this selects no row of the listing. */
    DISPATCHED(null, PendingJobState.DISPATCHED),
    EXPIRED(null, PendingJobState.EXPIRED);

    @Nullable
    private final JobState job;
    @Nullable
    private final PendingJobState queued;

    JobStatus(@Nullable JobState job, @Nullable PendingJobState queued) {
        this.job = job;
        this.queued = queued;
    }

    /** The job state this selects, or null when no job can be in it. */
    @Nullable
    public JobState job() {
        return job;
    }

    /** The queue state this selects, or null when no queue entry can be in it. */
    @Nullable
    public PendingJobState queued() {
        return queued;
    }
}
