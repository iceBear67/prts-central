package io.ib67.prts.project.entity;

/**
 * Access level a user holds on a project.
 *
 * <p>Persisted as the enum <em>ordinal</em> in {@code user_to_project.role}, a {@code smallint} that
 * Hibernate guards with a derived {@code CHECK (role >= 0 AND role <= 3)}. Constants must therefore
 * never be reordered or removed. Adding a fifth widens the generated check, but only for a schema
 * created from scratch — an existing database keeps the old constraint and will reject the new
 * ordinal until it is altered by hand.
 */
public enum ProjectRole {
    OWNER,
    MEMBER,
    VIEWER,
    NONE
}
