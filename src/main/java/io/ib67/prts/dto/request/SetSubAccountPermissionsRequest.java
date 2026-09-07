package io.ib67.prts.dto.request;

import java.util.List;

/**
 * Request payload to set granted permissions for a sub-account by string identifier.
 */
public record SetSubAccountPermissionsRequest(List<String> permissions) {
}
