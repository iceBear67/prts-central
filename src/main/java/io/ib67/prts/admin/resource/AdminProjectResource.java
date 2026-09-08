package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.Pages;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.admin.AdminProjectView;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.Project;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Administrative endpoints for cross-project queries.
 */
@Path("/admin/project")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminProjectResource {

    @Inject
    EntityManager entityManager;
    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public List<AdminProjectView> listProjects(
            @QueryParam("query") @Nullable String query,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") @Nullable Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var projects = Project.search(query, Pages.clampOffset(offset, window), window);
        // Batch query aggregated metrics for the page of projects.
        var ids = projects.stream().map(Project::getId).toList();
        var members = memberCounts(ids);
        var jobs = Job.countVisibleByProjects(ids);
        var queued = PendingJob.countActiveByProjects(ids);
        return projects.stream()
                .map(project -> AdminProjectView.of(
                        project,
                        members.getOrDefault(project.getId(), 0L),
                        jobs.getOrDefault(project.getId(), 0L),
                        queued.getOrDefault(project.getId(), 0L)))
                .toList();
    }

    private Map<UUID, Long> memberCounts(List<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return entityManager
                .createQuery("select l.id.projectId, count(l) from UserToProject l "
                        + "where l.id.projectId in ?1 group by l.id.projectId", Object[].class)
                .setParameter(1, projectIds)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }
}
