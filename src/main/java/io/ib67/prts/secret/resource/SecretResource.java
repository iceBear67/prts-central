package io.ib67.prts.secret.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.request.CreateSecretRequest;
import io.ib67.prts.dto.SecretView;
import io.ib67.prts.dto.request.UpdateSecretRequest;
import io.ib67.prts.project.ProjectConfig;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.secret.SecretConfig;
import io.ib67.prts.secret.SecretService;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoint managing project secret metadata and updates.
 */
@Path("/project/{projectId}/secret")
@Produces(MediaType.APPLICATION_JSON)
public class SecretResource {

    @Inject
    SecretService secretService;
    @Inject
    SecretConfig secretConfig;
    @Inject
    ProjectService projectService;
    @Inject
    ProjectConfig projectConfig;

    @GET
    @Transactional
    @RequirePermission(value = Perm.PROJECT_SECRET_READ, defaultRole = ProjectRole.MEMBER)
    public List<SecretView> listSecrets(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, projectConfig.list().maxPageSize());
        return secretService.list(projectId, Pages.clampOffset(offset, window), window).stream()
                .map(SecretView::of)
                .toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(value = Perm.PROJECT_SECRET_MANAGE, defaultRole = ProjectRole.OWNER)
    public SecretView createSecret(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid CreateSecretRequest request) {
        projectService.requireWritable(projectId);
        return SecretView.of(secretService.create(
                projectId, request.name(), description(request.description()), value(request.value())));
    }

    @PATCH
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(value = Perm.PROJECT_SECRET_MANAGE, defaultRole = ProjectRole.OWNER)
    public SecretView updateSecret(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("name") String name,
            @NotNull(message = "a request body is required") @Valid UpdateSecretRequest request) {
        projectService.requireWritable(projectId);
        var value = request.value() == null ? null : value(request.value());
        return secretService.update(projectId, name, description(request.description()), value)
                .map(SecretView::of)
                .orElseThrow(() -> new NotFoundException(
                        "no such secret in project " + projectId + ": " + name));
    }

    @DELETE
    @Path("/{name}")
    @RequirePermission(value = Perm.PROJECT_SECRET_MANAGE, defaultRole = ProjectRole.OWNER)
    public void deleteSecret(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("name") String name) {
        projectService.requireWritable(projectId);
        if (!secretService.delete(projectId, name)) {
            throw new NotFoundException("no such secret in project " + projectId + ": " + name);
        }
    }

    /** Validates secret value length against the runtime configuration limit. */
    private String value(String value) {
        if (value.length() > secretConfig.maxValueLength()) {
            throw new BadRequestException(
                    "value must be at most " + secretConfig.maxValueLength() + " characters");
        }
        return value;
    }

    @Nullable
    private String description(@Nullable String description) {
        if (description == null) {
            return null;
        }
        var stripped = description.strip();
        if (stripped.length() > secretConfig.maxDescriptionLength()) {
            throw new BadRequestException(
                    "description must be at most " + secretConfig.maxDescriptionLength() + " characters");
        }
        return stripped;
    }
}
