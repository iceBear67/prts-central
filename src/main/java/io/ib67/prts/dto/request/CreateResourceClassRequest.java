package io.ib67.prts.dto.request;

import io.ib67.prts.dto.ResourceClassView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request payload to create a global resource class.
 *
 * @param shared Whether every project may name the class. Omitted means shared: a class an admin
 *               says nothing about is one the whole service may use, as the catalogue was before
 *               per-project availability existed.
 */
@Schema(description = ResourceClassView.FIGURES)
public record CreateResourceClassRequest(
        // Ensure both null and pattern mismatches produce consistent validation messages.
        @NotNull(message = "name must match [A-Za-z0-9][A-Za-z0-9._-]\\{0,63\\}")
        @Pattern(regexp = CreateResourceClassRequest.NAME, message = "name must match [A-Za-z0-9][A-Za-z0-9._-]\\{0,63\\}")
        String name,
        @PositiveOrZero(message = "numCpus must not be negative") int numCpus,
        @PositiveOrZero(message = "memCount must not be negative") int memCount,
        @PositiveOrZero(message = "diskSize must not be negative") int diskSize,
        Boolean shared
) {
    /** Regex pattern for valid resource class names up to {@code ResourceClass.MAX_NAME_LENGTH} characters. */
    public static final String NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    public CreateResourceClassRequest {
        shared = shared == null || shared;
    }
}
