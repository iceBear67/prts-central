package io.ib67.prts.job.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Represents a project, which scopes jobs, templates, secrets, and member permissions.
 */
@Entity
@Table(name = "project")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Project extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    /**
     * What the project is for; empty string if nobody said.
     *
     * <p>Carries a DDL default so {@code schema-management.strategy: update} can add the column to a
     * database that already holds project rows.
     */
    @Builder.Default
    @Column(name = "description", nullable = false, columnDefinition = "varchar default ''")
    private String description = "";

    /**
     * Timestamp when the project was archived, or null if the project is active.
     */
    @Nullable
    @Column(name = "archived_at")
    private Instant archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** Searches projects by name with pagination, ordered by creation time descending. */
    public static List<Project> search(@Nullable String query, int offset, int limit) {
        var filter = query == null || query.isBlank() ? "%" : "%" + query.strip().toLowerCase() + "%";
        return find("lower(name) like ?1 order by id desc", filter)
                .range(offset, offset + limit - 1)
                .list();
    }
}
