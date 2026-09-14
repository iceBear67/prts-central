package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to update a job specification template.
 *
 * <p>A supplied {@code spec} replaces the stored one whole rather than merging into it: merging is what
 * {@link io.ib67.prts.agent.job.JobSpecOverride} does for a single run, and it cannot express removing an
 * environment entry or a command argument, which editing a template has to be able to do.
 *
 * @param name          new template name, or null to keep unchanged
 * @param resourceClass new default resource class name, or null to keep unchanged
 * @param spec          replacement specification, or null to keep the stored one
 */
public record UpdateTemplateRequest(
        @Nullable @Size(min = 1, max = 200,
                message = "name must not be blank and at most {max} characters") String name,
        @Nullable @Pattern(regexp = CreateResourceClassRequest.NAME,
                message = "resourceClass is not a valid resource class name") String resourceClass,
        @Nullable @Valid JobSpecRequest spec
) {
    public UpdateTemplateRequest {
        name = name == null ? null : name.strip();
        resourceClass = resourceClass == null ? null : resourceClass.strip();
        if (name == null && resourceClass == null && spec == null) {
            throw new BadRequestException("name, resourceClass or spec is required");
        }
    }
}
