package io.ib67.prts.secret.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.CreateSecretRequest;
import io.ib67.prts.dto.SecretView;
import io.ib67.prts.dto.UpdateSecretRequest;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.secret.SecretConfig;
import io.ib67.prts.secret.SecretService;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.regex.Pattern;

/**
 * A project's secrets. Listing them takes {@link ProjectRole#MEMBER}, since whoever writes a job has
 * to know which names exist and what they are for; creating, describing and deleting takes
 * {@link ProjectRole#OWNER}. Nothing here reads a value back — a stored secret is only reachable by
 * the dispatcher.
 *
 * <p>A value has no update: replacing one is a delete and a create, which is one fewer way to take
 * away the value a running job was given. Only the description can be changed in place.
 */
@Path("/project/{projectId}/secret")
@Produces(MediaType.APPLICATION_JSON)
public class SecretResource {

    /**
     * Secrets are destined for a job's environment, so a name is held to that shape rather than to
     * whatever survives a URL path.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    @Inject
    SecretService secretService;
    @Inject
    SecretConfig secretConfig;

    @GET
    @Transactional
    @RequirePermission(value = Perm.PROJECT_SECRET_READ, defaultRole = ProjectRole.MEMBER)
    public List<SecretView> listSecrets(@ProjectId @PathParam("projectId") UUID projectId) {
        return secretService.list(projectId).stream()
                .map(SecretView::of)
                .toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(value = Perm.PROJECT_SECRET_MANAGE, defaultRole = ProjectRole.OWNER)
    public SecretView createSecret(
            @ProjectId @PathParam("projectId") UUID projectId, CreateSecretRequest request) {
        if (request == null || request.name() == null || !NAME.matcher(request.name()).matches()) {
            throw new BadRequestException("name must match " + NAME.pattern());
        }
        if (request.value() == null || request.value().isEmpty()) {
            throw new BadRequestException("value is required");
        }
        if (request.value().length() > secretConfig.maxValueLength()) {
            throw new BadRequestException(
                    "value must be at most " + secretConfig.maxValueLength() + " characters");
        }
        return SecretView.of(secretService.create(
                projectId, request.name(), description(request.description()), request.value()));
    }

    @PATCH
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(value = Perm.PROJECT_SECRET_MANAGE, defaultRole = ProjectRole.OWNER)
    public SecretView describeSecret(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("name") String name,
            UpdateSecretRequest request) {
        if (request == null) {
            throw new BadRequestException("description is required, blank to clear it");
        }
        return secretService.describe(projectId, name, description(request.description()))
                .map(SecretView::of)
                .orElseThrow(() -> new NotFoundException(
                        "no such secret in project " + projectId + ": " + name));
    }

    @DELETE
    @Path("/{name}")
    @RequirePermission(value = Perm.PROJECT_SECRET_MANAGE, defaultRole = ProjectRole.OWNER)
    public void deleteSecret(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("name") String name) {
        if (!secretService.delete(projectId, name)) {
            throw new NotFoundException("no such secret in project " + projectId + ": " + name);
        }
    }

    /** Blank is no description at all, so clearing one and never setting one are the same row. */
    @Nullable
    private String description(@Nullable String description) {
        if (description == null || description.isBlank()) {
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
