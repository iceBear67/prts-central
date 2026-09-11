package io.ib67.prts.admin.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.ResourceClassView;
import io.ib67.prts.dto.request.CreateResourceClassRequest;
import io.ib67.prts.dto.request.UpdateResourceClassRequest;
import io.ib67.prts.job.entity.Job;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

/**
 * Administrative endpoints for the resource class catalogue, which is service-wide.
 *
 * <p>Every template and every job names a class, and a worker only reports the capacity it has — it
 * never declares a class — so an installation cannot run anything until one is defined here.
 */
@Path("/admin/resource-class")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminResourceClassResource {

    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public List<ResourceClassView> listResourceClasses(
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        return ResourceClass.listPage(Pages.clampOffset(offset, window), window).stream()
                .map(ResourceClassView::of)
                .toList();
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
                .build();
        klass.persist();
        return ResourceClassView.of(klass);
    }

    /**
     * Changes what a class demands. The name is its identity and cannot be edited.
     *
     * <p>Jobs already placed keep the worker they ran on, but one still queued is matched against the
     * new numbers when it is next dispatched.
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
        return ResourceClassView.of(klass);
    }

    /**
     * Deletes a resource class.
     *
     * <p>Refused with 409 while a job or a template still names it: both hold a foreign key to the row,
     * and a job is kept as the record of what ran rather than cascaded away with the class.
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
