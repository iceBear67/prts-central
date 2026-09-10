package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to change what a resource class demands. Null fields keep their current value.
 */
public record UpdateResourceClassRequest(
        @Nullable @PositiveOrZero(message = "numCpus must not be negative") Integer numCpus,
        @Nullable @PositiveOrZero(message = "memCount must not be negative") Integer memCount,
        @Nullable @PositiveOrZero(message = "diskSize must not be negative") Integer diskSize
) {
    public UpdateResourceClassRequest {
        if (numCpus == null && memCount == null && diskSize == null) {
            throw new BadRequestException("numCpus, memCount or diskSize is required");
        }
    }
}
