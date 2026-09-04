package io.ib67.prts.agent.worker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

/**
 * A named resource requirement. Workers are matched against these numbers when a job is scheduled.
 * The name is unique within a project, so two projects may each mean their own thing by "large".
 */
@Entity
@Table(name = "resource_class")
// @IdClass, not @EmbeddedId: the name stays a plain property, so getName() and the JSON the worker
// receives are unchanged by the project half of the key.
@IdClass(ResourceClass.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ResourceClass extends PanacheEntityBase {

    /**
     * Scope of a class every project may use. The project is part of the primary key and Postgres
     * cannot key on null, so "no project" is this reserved all-zero id — same reason as
     * {@link io.ib67.prts.user.Permission#GLOBAL}.
     */
    @JsonIgnore
    public static final UUID GLOBAL = new UUID(0, 0);

    @Id
    @Column(name = "name", nullable = false, updatable = false, columnDefinition = "varchar")
    private String name;

    /** The owning project, or {@link #GLOBAL}. Not published: no worker needs to know. */
    @Id
    @Column(name = "project_id", nullable = false, updatable = false)
    @JsonIgnore
    private UUID projectId;

    @Column(name = "num_cpus", nullable = false)
    private int numCpus;

    @Column(name = "mem_count", nullable = false)
    private int memCount;

    @Column(name = "disk_size", nullable = false)
    private int diskSize;

    public Key key() {
        return new Key(name, scopeOf(projectId));
    }

    public boolean isGlobal() {
        return GLOBAL.equals(projectId);
    }

    /** Null is the global scope everywhere this key is built, as it cannot be stored as one. */
    public static UUID scopeOf(@Nullable UUID projectId) {
        return projectId == null ? GLOBAL : projectId;
    }

    /**
     * What {@code name} means inside {@code projectId}: the project's own definition, or the global
     * one when it has none — a project-scoped row shadows a global row of the same name. Nothing
     * reaches another project's, which is the point of keying on the project at all.
     */
    public static Optional<ResourceClass> findVisible(@Nullable UUID projectId, String name) {
        var scope = scopeOf(projectId);
        var own = ResourceClass.<ResourceClass>findByIdOptional(new Key(name, scope));
        if (own.isPresent() || GLOBAL.equals(scope)) {
            return own;
        }
        return ResourceClass.findByIdOptional(new Key(name, GLOBAL));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String name;
        private UUID projectId;
    }
}
