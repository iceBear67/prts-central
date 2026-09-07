package io.ib67.prts.secret;

import io.ib67.prts.project.entity.Project;
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
 * An encrypted secret key-value pair belonging to a project, keyed by {@code (projectId, name)}.
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

    @Column(name = "description", columnDefinition = "varchar")
    private String description;

    /** Ciphertext encrypted using {@link SecretCipher}. */
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
