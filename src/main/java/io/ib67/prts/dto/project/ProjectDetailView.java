package io.ib67.prts.dto.project;

import io.ib67.prts.job.entity.Project;
import io.ib67.prts.job.entity.ProjectRole;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Detailed view of a project including membership and job counts.
 *
 * @param role       The caller's role in the project, or {@link ProjectRole#NONE} if not a direct member.
 * @param access     The authorization basis allowing the caller to view the project.
 * @param archivedAt Timestamp when the project was archived, or null if active.
 */
public record ProjectDetailView(
        UUID id,
        String name,
        String description,
        ProjectRole role,
        Access access,
        @Nullable Instant archivedAt,
        List<ProjectMemberView> members,
        Jobs jobs
) {
    public ProjectDetailView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(jobs, "jobs");
    }

    public enum Access { MEMBER, ADMIN, PERMISSION }

    /**
     * Aggregated job statistics for the project.
     *
     * @param total   Total visible jobs.
     * @param running Currently executing jobs.
     * @param queued  Jobs pending dispatch in the queue.
     */
    public record Jobs(long total, long running, long queued) {
    }

    public static ProjectDetailView of(
            Project project, ProjectRole role, Access access, List<ProjectMemberView> members, Jobs jobs) {
        return new ProjectDetailView(project.getId(), project.getName(), project.getDescription(),
                role, access, project.getArchivedAt(), members, jobs);
    }
}
