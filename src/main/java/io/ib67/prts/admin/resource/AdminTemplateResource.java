package io.ib67.prts.admin.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.job.JobSpecTemplateView;
import io.ib67.prts.dto.request.CreateTemplateRequest;
import io.ib67.prts.dto.request.JobSpecRequest;
import io.ib67.prts.dto.request.UpdateTemplateRequest;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
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
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.UUID;

/**
 * Administrative endpoints for managing global job specification templates.
 *
 * <p>Project-scoped templates are managed via project endpoints; this resource only handles global templates.
 */
@Path("/admin/template")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminTemplateResource {

    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public Page<JobSpecTemplateView> listGlobalTemplates(
            @QueryParam("query") @Nullable String query,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                JobSpecTemplate.searchGlobal(query, start, window).stream()
                        .map(template -> JobSpecTemplateView.of(template, true))
                        .toList(),
                start,
                window,
                JobSpecTemplate.countGlobal(query));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @Transactional
    public JobSpecTemplateView createGlobalTemplate(
            @NotNull(message = "a request body is required") @Valid CreateTemplateRequest request) {
        var template = JobSpecTemplate.builder()
                .name(request.name())
                .spec(globalSpec(request.spec()))
                .resourceClass(requireClass(request.resourceClass()))
                .build();
        template.persist();
        return JobSpecTemplateView.of(template, true);
    }

    /**
     * Applies the fields the caller supplied; a null field leaves that part of the template as it was.
     *
     * <p>Updating rather than replacing matters because the ID is what a queued job and a re-run payload
     * hold: delete-and-recreate would leave both pointing at a template that no longer exists.
     */
    @PATCH
    @Path("/{templateId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public JobSpecTemplateView updateGlobalTemplate(
            @PathParam("templateId") UUID templateId,
            @NotNull(message = "a request body is required") @Valid UpdateTemplateRequest request) {
        var template = JobSpecTemplate.findGlobalFetched(templateId)
                .orElseThrow(NotFoundException::new);
        if (request.name() != null) {
            template.setName(request.name());
        }
        if (request.resourceClass() != null) {
            template.setResourceClass(requireClass(request.resourceClass()));
        }
        if (request.spec() != null) {
            template.setSpec(globalSpec(request.spec()));
        }
        return JobSpecTemplateView.of(template, true);
    }

    @DELETE
    @Path("/{templateId}")
    @Transactional
    public void deleteGlobalTemplate(@PathParam("templateId") UUID templateId) {
        JobSpecTemplate.findGlobalFetched(templateId)
                .orElseThrow(NotFoundException::new)
                .delete();
    }

    private static JobSpec globalSpec(JobSpecRequest request) {
        var spec = request.toSpec();
        if (!spec.volumes().isEmpty()) {
            // Volumes are project-scoped and cannot be referenced by global templates.
            throw new BadRequestException("a global template cannot mount volumes");
        }
        return spec;
    }

    private static ResourceClass requireClass(String name) {
        return ResourceClass.findByName(name)
                .orElseThrow(() -> new NotFoundException("no such resource class: " + name));
    }
}
