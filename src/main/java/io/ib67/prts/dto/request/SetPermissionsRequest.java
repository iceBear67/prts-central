package io.ib67.prts.dto.request;

import io.ib67.prts.Perm;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;

import java.util.List;

/**
 * Request payload replacing a subject's permission grants in one scope.
 *
 * @param permissions The grants to end up with, by their {@link Perm#permission()} identifier.
 */
public record SetPermissionsRequest(
        @NotNull(message = "permissions is required, empty to hold none") List<String> permissions
) {
    /**
     * The permissions named, deduplicated.
     *
     * <p>Resolving a name to a {@link Perm} is a lookup, not a shape check, so it stays here rather
     * than becoming a constraint.
     */
    public List<Perm> resolved() {
        return permissions.stream()
                .map(name -> Perm.byPermission(name)
                        .orElseThrow(() -> new BadRequestException("unknown permission: " + name)))
                .distinct()
                .toList();
    }
}
