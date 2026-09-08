package io.ib67.prts.dto.request;

import io.ib67.prts.Perm;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;

import java.util.List;

/**
 * Request payload to replace a subject's permission grants in a scope.
 *
 * @param permissions The permission identifiers to grant (see {@link Perm#permission()}).
 */
public record SetPermissionsRequest(
        @NotNull(message = "permissions is required, empty to hold none") List<String> permissions
) {
    /**
     * Resolves and deduplicates the requested permissions.
     */
    public List<Perm> resolved() {
        return permissions.stream()
                .map(name -> Perm.byPermission(name)
                        .orElseThrow(() -> new BadRequestException("unknown permission: " + name)))
                .distinct()
                .toList();
    }
}
