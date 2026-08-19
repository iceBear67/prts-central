package io.ib67.prts.project;

/**
 * Access level a user holds on a project.
 *
 * <p>Persisted as the enum <em>ordinal</em> so that it fits the {@code int} column and its
 * {@code CHECK (permission > -1 AND permission < 4)} constraint. Constants must therefore never be
 * reordered or removed, and a fifth one cannot be added without widening the constraint.
 */
public enum ProjectRole {
    OWNER,
    MEMBER,
    VIEWER,
    NONE
}
