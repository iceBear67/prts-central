package io.ib67.prts.agent.worker.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.ib67.prts.Reserved;
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
 * Named resource requirement profile used to match jobs to capable workers.
 */
@Entity
@Table(name = "resource_class")
@IdClass(ResourceClass.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ResourceClass extends PanacheEntityBase {

    /** Sentinel project ID for globally accessible resource classes. */
    @JsonIgnore
    public static final UUID GLOBAL = Reserved.ID;

    @Id
    @Column(name = "name", nullable = false, updatable = false, columnDefinition = "varchar")
    private String name;

    /** Owning project ID, or {@link #GLOBAL}. */
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

    /** Returns {@link #GLOBAL} if projectId is null, otherwise returns projectId. */
    public static UUID scopeOf(@Nullable UUID projectId) {
        return projectId == null ? GLOBAL : projectId;
    }

    /**
     * Looks up a resource class visible to the given project, falling back to global definitions.
     */
    public static Optional<ResourceClass> findVisible(@Nullable UUID projectId, String name) {
        var scope = scopeOf(projectId);
        var own = ResourceClass.<ResourceClass>findByIdOptional(new Key(name, scope));
        if (own.isPresent() || GLOBAL.equals(scope)) {
            return own;
        }
        return ResourceClass.findByIdOptional(new Key(name, GLOBAL));
    }

    /**
     * Deletes all resource classes belonging to the specified project.
     */
    public static long deleteByProject(UUID projectId) {
        if (projectId == null || GLOBAL.equals(projectId)) {
            throw new IllegalArgumentException("not a project scope: " + projectId);
        }
        return delete("projectId", projectId);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String name;
        private UUID projectId;
    }
}
