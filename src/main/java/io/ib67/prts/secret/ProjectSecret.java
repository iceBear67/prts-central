package io.ib67.prts.secret;

import io.ib67.prts.project.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One named secret of one project, keyed by {@code (project_id, name)} — the name is unique within a
 * project and is the handle every endpoint addresses it by, so there is no surrogate id to publish.
 * The value is only ever here sealed by {@link SecretCipher}.
 */
@Entity
@Table(name = "project_secret")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ProjectSecret extends PanacheEntityBase {

    @EmbeddedId
    private Id id;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    /** What the name is for, for whoever writes a job against it. Never the value. */
    @Column(name = "description", columnDefinition = "varchar")
    private String description;

    /** Sealed, and excluded from {@code toString} so no log line can carry even the ciphertext. */
    @Column(name = "cipher_text", nullable = false, columnDefinition = "varchar")
    @ToString.Exclude
    private String cipherText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public String getName() {
        return id == null ? null : id.getName();
    }

    public static ProjectSecret of(Project project, String name, String description, String cipherText) {
        var secret = new ProjectSecret();
        secret.id = new Id(project.getId(), name);
        secret.project = project;
        secret.description = description;
        secret.cipherText = cipherText;
        return secret;
    }

    /** Ordered by name: the primary key is already {@code (project_id, name)}. */
    public static List<ProjectSecret> listByProject(UUID projectId) {
        return list("id.projectId", Sort.by("id.name"), projectId);
    }

    public static Optional<ProjectSecret> findIn(UUID projectId, String name) {
        return findByIdOptional(new Id(projectId, name));
    }

    public static boolean deleteIn(UUID projectId, String name) {
        return deleteById(new Id(projectId, name));
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class Id {

        @Column(name = "project_id", nullable = false)
        private UUID projectId;

        @Column(name = "name", nullable = false, columnDefinition = "varchar")
        private String name;
    }
}
