package io.ib67.prts.dto.request;

import io.ib67.prts.dto.ResourceClassView;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request payload to update resource class capacities. Null fields remain unchanged.
 *
 * @param shared Whether every project may name the class. Turning it off leaves the class open only
 *               to the projects listed under it; the list is kept either way.
 */
@Schema(description = ResourceClassView.FIGURES)
public record UpdateResourceClassRequest(
        @Nullable @PositiveOrZero(message = "numCpus must not be negative") Integer numCpus,
        @Nullable @PositiveOrZero(message = "memCount must not be negative") Integer memCount,
        @Nullable @PositiveOrZero(message = "diskSize must not be negative") Integer diskSize,
        @Nullable Boolean shared
) {
    public UpdateResourceClassRequest {
        if (numCpus == null && memCount == null && diskSize == null && shared == null) {
            throw new BadRequestException("numCpus, memCount, diskSize or shared is required");
        }
    }
}
