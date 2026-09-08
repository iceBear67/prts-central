package io.ib67.prts.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload to create a job specification template.
 *
 * @param resourceClass Name of the default resource class; must be visible in the template's scope.
 */
public record CreateTemplateRequest(
        @NotBlank(message = "name is required") String name,
        @NotNull(message = "spec is required") @Valid JobSpecRequest spec,
        @NotBlank(message = "resourceClass is required") String resourceClass
) {
    public CreateTemplateRequest {
        name = name == null ? null : name.strip();
        resourceClass = resourceClass == null ? null : resourceClass.strip();
    }
}
