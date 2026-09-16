package io.ib67.prts.dto;

import io.ib67.prts.job.entity.Project;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a project referenced by another view.
 *
 * <p>Unlike {@link UserInfo} and {@link WorkerInfo} the name is never null: a reference to a project
 * is a foreign key, so the row is there for as long as the reference is.
 */
public record ProjectInfo(UUID id, String name) {
    public ProjectInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    public static ProjectInfo of(Project project) {
        return new ProjectInfo(project.getId(), project.getName());
    }
}
