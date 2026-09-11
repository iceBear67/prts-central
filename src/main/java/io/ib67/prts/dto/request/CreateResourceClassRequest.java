package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request payload to create a global resource class.
 *
 * <p>The three capacities carry no unit of their own: they are compared against the numbers a worker
 * reports for itself, and zero means the class asks for nothing on that axis.
 */
public record CreateResourceClassRequest(
        // Ensure both null and pattern mismatches produce consistent validation messages.
        @NotNull(message = "name must match [A-Za-z0-9][A-Za-z0-9._-]\\{0,63\\}")
        @Pattern(regexp = CreateResourceClassRequest.NAME, message = "name must match [A-Za-z0-9][A-Za-z0-9._-]\\{0,63\\}")
        String name,
        @PositiveOrZero(message = "numCpus must not be negative") int numCpus,
        @PositiveOrZero(message = "memCount must not be negative") int memCount,
        @PositiveOrZero(message = "diskSize must not be negative") int diskSize
) {
    /** Pattern for valid resource class names. Its length is {@code ResourceClass.MAX_NAME_LENGTH}. */
    public static final String NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

}
