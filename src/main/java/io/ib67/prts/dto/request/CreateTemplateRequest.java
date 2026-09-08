package io.ib67.prts.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload to define a job spec template.
 *
 * @param resourceClass Name of the resource class jobs default to; must be visible to the template's scope.
 */
public record CreateTemplateRequest(
        @NotBlank(message = "name is required") String name,
        // @Valid so the spec's own constraints are reached rather than only its presence.
        @NotNull(message = "spec is required") @Valid JobSpecRequest spec,
        @NotBlank(message = "resourceClass is required") String resourceClass
) {
    public CreateTemplateRequest {
        name = name == null ? null : name.strip();
        resourceClass = resourceClass == null ? null : resourceClass.strip();
    }
}
