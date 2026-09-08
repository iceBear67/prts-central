package io.ib67.prts.project.entity;

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
     * When the project was archived, or null while it is live. A single nullable column rather than a
     * boolean beside a timestamp, so the two can never disagree.
     */
    @Nullable
    @Column(name = "archived_at")
    private Instant archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** Lists projects whose name contains the query, newest first. */
    public static List<Project> search(@Nullable String query, int offset, int limit) {
        var filter = query == null || query.isBlank() ? "%" : "%" + query.strip().toLowerCase() + "%";
        return find("lower(name) like ?1 order by id desc", filter)
                .range(offset, offset + limit - 1)
                .list();
    }
}
