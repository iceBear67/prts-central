package io.ib67.prts.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload to create a job specification template.
 *
 * @param resourceClass Name of the default resource class; must be visible in the template's scope.
 */
public record CreateTemplateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most {max} characters")
        String name,
        @NotNull(message = "spec is required") @Valid JobSpecRequest spec,
        @NotBlank(message = "resourceClass is required")
        @Pattern(regexp = CreateResourceClassRequest.NAME,
                message = "resourceClass is not a valid resource class name")
        String resourceClass
) {
    public CreateTemplateRequest {
        name = name == null ? null : name.strip();
        resourceClass = resourceClass == null ? null : resourceClass.strip();
    }
}
