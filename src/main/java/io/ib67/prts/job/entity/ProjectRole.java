package io.ib67.prts.job.entity;

/**
 * Access level a user holds on a project.
 *
 * <p>Note: Persisted by ordinal in the database. Do not reorder or remove existing constants.
 */
public enum ProjectRole {
    OWNER,
    MEMBER,
    VIEWER,
    NONE
}
