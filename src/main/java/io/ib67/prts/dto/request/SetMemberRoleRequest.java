package io.ib67.prts.dto.request;

import io.ib67.prts.project.entity.ProjectRole;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;

public record SetMemberRoleRequest(@NotNull(message = "role is required") ProjectRole role) {
    public SetMemberRoleRequest {
        // Setting a role to NONE is disallowed; member removal must be done via DELETE.
        if (role == ProjectRole.NONE) {
            throw new BadRequestException(
                    "NONE is the absence of a membership; delete the member instead");
        }
    }
}
