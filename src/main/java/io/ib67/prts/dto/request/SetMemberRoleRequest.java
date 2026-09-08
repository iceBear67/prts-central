package io.ib67.prts.dto.request;

import io.ib67.prts.project.entity.ProjectRole;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;

public record SetMemberRoleRequest(@NotNull(message = "role is required") ProjectRole role) {
    public SetMemberRoleRequest {
        // Not a constraint: excluding one enum value is a rule about what a membership means, not a
        // shape a standard constraint expresses. A bespoke validator would be more machinery than the
        // rule deserves, and custom constraints do not reach the OpenAPI schema anyway.
        if (role == ProjectRole.NONE) {
            throw new BadRequestException(
                    "NONE is the absence of a membership; delete the member instead");
        }
    }
}
