package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.job.JobSpecTemplateView;
import io.ib67.prts.dto.request.CreateTemplateRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;
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

    @GET
    @Transactional
    public List<JobSpecTemplateView> listGlobalTemplates() {
        return JobSpecTemplate.listGlobalFetched().stream()
                .map(template -> JobSpecTemplateView.of(template, true))
                .toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @Transactional
    public JobSpecTemplateView createGlobalTemplate(
            @NotNull(message = "a request body is required") @Valid CreateTemplateRequest request) {
        var spec = request.spec().toSpec();
        if (!spec.volumes().isEmpty()) {
            // Volumes are project-scoped and cannot be referenced by global templates.
            throw new BadRequestException("a global template cannot mount volumes");
        }
        var template = JobSpecTemplate.builder()
                .name(request.name())
                .spec(spec)
                .resourceClass(requireClass(request.resourceClass()))
                .build();
        template.persist();
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

    private static ResourceClass requireClass(String name) {
        return ResourceClass.findByName(name)
                .orElseThrow(() -> new NotFoundException("no such resource class: " + name));
    }
}
