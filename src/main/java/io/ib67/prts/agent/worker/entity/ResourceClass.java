package io.ib67.prts.agent.worker.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-wide resource profile specifying CPU, memory, and disk requirements for job placement.
 */
@Entity
@Table(name = "resource_class")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ResourceClass extends PanacheEntityBase {

    @Id
    @Column(name = "name", nullable = false, updatable = false, columnDefinition = "varchar")
    private String name;

    @Column(name = "num_cpus", nullable = false)
    private int numCpus;

    @Column(name = "mem_count", nullable = false)
    private int memCount;

    @Column(name = "disk_size", nullable = false)
    private int diskSize;

    /**
     * Whether every project may name this class without being listed in {@link ProjectResourceClass}.
     *
     * <p>Defaults to true, so a deployment that grants nothing keeps the catalogue it had before
     * per-project availability existed: one service-wide list every project may pick from.
     */
    @Builder.Default
    @Column(name = "shared", nullable = false, columnDefinition = "boolean default true")
    private boolean shared = true;

    // Static wrapper around findByIdOptional to enable mockStatic in tests.
    public static Optional<ResourceClass> findByName(String name) {
        return findByIdOptional(name);
    }

    /** Lists one page of the catalogue, by name, matching {@code query} against the name. */
    public static List<ResourceClass> search(@Nullable String query, int offset, int length) {
        return ResourceClass.<ResourceClass>find("lower(name) like ?1 order by name", like(query))
                .range(offset, offset + length - 1)
                .list();
    }

    public static long countSearch(@Nullable String query) {
        return count("lower(name) like ?1", like(query));
    }

    /**
     * Shared with every project, or granted to this one. The subquery is uncorrelated so the
     * predicate stays a fragment both {@code find} and {@code count} accept.
     */
    private static final String AVAILABLE_TO =
            "(shared = true or name in (select g.id.resourceClass from ProjectResourceClass g "
                    + "where g.id.projectId = ?1))";

    /** Lists one page of the classes a project may run in, by name. */
    public static List<ResourceClass> listAvailableTo(
            UUID projectId, @Nullable String query, int offset, int length) {
        return ResourceClass.<ResourceClass>find(
                        AVAILABLE_TO + " and lower(name) like ?2 order by name", projectId, like(query))
                .range(offset, offset + length - 1)
                .list();
    }

    public static long countAvailableTo(UUID projectId, @Nullable String query) {
        return count(AVAILABLE_TO + " and lower(name) like ?2", projectId, like(query));
    }

    /** Whether the project may run in this class. */
    public boolean availableTo(UUID projectId) {
        return shared || ProjectResourceClass.granted(projectId, name);
    }

    /**
     * This class, once the project is known to be allowed to name it.
     *
     * <p>Every path that pins a class to a project passes through here — job submission, a project's
     * own templates — or the per-project catalogue would be a suggestion rather than the answer.
     *
     * @throws ForbiddenException the class exists, but is not this project's to use
     */
    public ResourceClass requireAvailableTo(UUID projectId) {
        if (!availableTo(projectId)) {
            throw new ForbiddenException("resource class " + name + " is not available to this project");
        }
        return this;
    }

    private static String like(@Nullable String query) {
        return query == null || query.isBlank() ? "%" : "%" + query.strip().toLowerCase() + "%";
    }
}
