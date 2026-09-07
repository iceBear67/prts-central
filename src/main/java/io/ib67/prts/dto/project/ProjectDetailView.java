package io.ib67.prts.dto.project;

import io.ib67.prts.project.entity.Project;
import io.ib67.prts.project.entity.ProjectRole;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One project in full, for a caller who may read it.
 *
 * @param role   the caller's own role, {@link ProjectRole#NONE} when they are not on the roster.
 * @param access what let the caller in — on the roster, or past it: an {@code admin:all} holder sees
 *               every project, and a {@code project:read} grant one they are not a member of.
 */
public record ProjectDetailView(
        UUID id,
        String name,
        ProjectRole role,
        Access access,
        List<ProjectMemberView> members,
        Jobs jobs
) {
    public ProjectDetailView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(jobs, "jobs");
    }

    public enum Access { MEMBER, ADMIN, PERMISSION }

    /**
     * @param total   jobs the caller would see listed.
     * @param running of those, the ones not finished — handed to a worker, or running on one.
     * @param queued  queue entries still waiting to become a job.
     */
    public record Jobs(long total, long running, long queued) {
    }

    public static ProjectDetailView of(
            Project project, ProjectRole role, Access access, List<ProjectMemberView> members, Jobs jobs) {
        return new ProjectDetailView(project.getId(), project.getName(), role, access, members, jobs);
    }
}
