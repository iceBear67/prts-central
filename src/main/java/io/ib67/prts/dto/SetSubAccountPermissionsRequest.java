package io.ib67.prts.dto;

import java.util.List;

/**
 * The complete set the sub-account should hold in this project, by {@link io.ib67.prts.Perm}
 * string — an empty list takes everything away. Not the enum constant names: the strings are already
 * schema, and parsing them in the resource keeps the message that a Jackson creator's throw would
 * replace with a bodiless 400.
 */
public record SetSubAccountPermissionsRequest(List<String> permissions) {
}
