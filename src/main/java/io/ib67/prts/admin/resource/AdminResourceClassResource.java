package io.ib67.prts.admin.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ProjectResourceClass;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.ProjectInfo;
import io.ib67.prts.dto.ResourceClassView;
import io.ib67.prts.dto.request.CreateResourceClassRequest;
import io.ib67.prts.dto.request.UpdateResourceClassRequest;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.Project;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.UUID;

/**
 * Administrative endpoints for the service-wide resource class catalogue, and for which projects
 * may name each of its entries.
 *
 * <p>A class is either {@code shared} — every project's to use, which is what one created without
 * saying otherwise is — or open only to the projects listed under {@code /{name}/project}. The
 * other direction of the same mapping is {@code GET /project/{projectId}/resource-class}, which an
 * {@code admin:all} caller reaches for any project.
 */
@Path("/admin/resource-class")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminResourceClassResource {

    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public Page<ResourceClassView> listResourceClasses(
            @QueryParam("query") @Nullable String query,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                ResourceClass.search(query, start, window).stream()
                        .map(ResourceClassView::of)
                        .toList(),
                start,
                window,
                ResourceClass.countSearch(query));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @Transactional
    public ResourceClassView createResourceClass(
            @NotNull(message = "a request body is required") @Valid CreateResourceClassRequest request) {
        if (ResourceClass.findByName(request.name()).isPresent()) {
            throw new ClientErrorException(
                    "a resource class named " + request.name() + " already exists",
                    Response.Status.CONFLICT);
        }
        var klass = ResourceClass.builder()
                .name(request.name())
                .numCpus(request.numCpus())
                .memCount(request.memCount())
                .diskSize(request.diskSize())
                .shared(request.shared())
                .build();
        klass.persist();
        return ResourceClassView.of(klass);
    }

    /**
     * Updates resource class specifications.
     *
     * <p>Queued jobs will be matched against the updated requirements upon subsequent dispatch attempts.
     */
    @PATCH
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public ResourceClassView updateResourceClass(
            @PathParam("name") String name,
            @NotNull(message = "a request body is required") @Valid UpdateResourceClassRequest request) {
        var klass = require(name);
        if (request.numCpus() != null) {
            klass.setNumCpus(request.numCpus());
        }
        if (request.memCount() != null) {
            klass.setMemCount(request.memCount());
        }
        if (request.diskSize() != null) {
            klass.setDiskSize(request.diskSize());
        }
        if (request.shared() != null) {
            klass.setShared(request.shared());
        }
        return ResourceClassView.of(klass);
    }

    /**
     * The projects this class is open to.
     *
     * <p>While the class is {@code shared} the list says nothing about what may run — every project
     * may — but the rows are kept either way, so un-sharing restores the list an admin built rather
     * than emptying it.
     */
    @GET
    @Path("/{name}/project")
    @Transactional
    public Page<ProjectInfo> listAllowedProjects(
            @PathParam("name") String name,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        require(name);
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                ProjectResourceClass.listByResourceClassFetched(name, start, window).stream()
                        .map(grant -> ProjectInfo.of(grant.getProject()))
                        .toList(),
                start,
                window,
                ProjectResourceClass.countByResourceClass(name));
    }

    /** Opens the class to one project. Idempotent. */
    @PUT
    @Path("/{name}/project/{projectId}")
    @Transactional
    public void allowProject(@PathParam("name") String name, @PathParam("projectId") UUID projectId) {
        var klass = require(name);
        var project = Project.<Project>findByIdOptional(projectId)
                .orElseThrow(() -> new NotFoundException("no such project: " + projectId));
        if (!ProjectResourceClass.granted(projectId, name)) {
            ProjectResourceClass.of(project, klass).persist();
        }
    }

    /**
     * Withdraws one project's grant.
     *
     * <p>Jobs already queued against the class are refused when the dispatcher resolves them, and
     * running ones are left alone — the grant governs submission, not placement.
     */
    @DELETE
    @Path("/{name}/project/{projectId}")
    @Transactional
    public void denyProject(@PathParam("name") String name, @PathParam("projectId") UUID projectId) {
        require(name);
        if (!ProjectResourceClass.revoke(projectId, name)) {
            throw new NotFoundException(
                    "resource class " + name + " is not open to project " + projectId);
        }
    }

    /**
     * Deletes a resource class.
     *
     * <p>Returns 409 Conflict if the class is referenced by existing jobs or templates.
     */
    @DELETE
    @Path("/{name}")
    @Transactional
    public void deleteResourceClass(@PathParam("name") String name) {
        var klass = require(name);
        var jobs = Job.countByResourceClass(name);
        var templates = JobSpecTemplate.countByResourceClass(name);
        if (jobs > 0 || templates > 0) {
            throw new ClientErrorException(
                    "resource class " + name + " is still named by " + jobs + " job(s) and "
                            + templates + " template(s)",
                    Response.Status.CONFLICT);
        }
        klass.delete();
    }

    private static ResourceClass require(String name) {
        return ResourceClass.findByName(name)
                .orElseThrow(() -> new NotFoundException("no such resource class: " + name));
    }
}
