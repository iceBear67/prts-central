package io.ib67.prts.dto.request;

import io.ib67.prts.Perm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * Request to open a project sub-account.
 *
 * <p>The grants and the credential are part of the same call so a usable account costs one write
 * rather than three, with nothing half-made left behind if a later one fails.
 *
 * @param permissions Project permissions to grant, empty to hold none.
 * @param issueToken  Whether to mint the account's token; it is returned once, in this response.
 */
public record CreateSubAccountRequest(
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,
        List<String> permissions,
        boolean issueToken
) {
    public CreateSubAccountRequest {
        name = name == null ? null : name.strip();
        permissions = Objects.requireNonNullElse(permissions, List.of());
    }

    /** Resolves the requested permissions, rejecting names no permission answers to. */
    public List<Perm> resolvedPermissions() {
        return SetPermissionsRequest.resolve(permissions);
    }
}
