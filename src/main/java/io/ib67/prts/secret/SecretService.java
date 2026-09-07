package io.ib67.prts.secret;

import io.ib67.prts.project.ProjectService;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing project secrets, handling encrypted storage and decryption for job dispatch.
 */
@ApplicationScoped
public class SecretService {

    @Inject
    ProjectService projectService;
    @Inject
    SecretCipher cipher;

    /** Lists metadata for all secrets in a project. */
    public List<ProjectSecret> list(UUID projectId) {
        projectService.require(projectId);
        return ProjectSecret.listByProject(projectId);
    }

    /**
     * Creates and encrypts a new project secret.
     *
     * @throws ClientErrorException with 409 Conflict if a secret with the same name already exists
     */
    @Transactional
    public ProjectSecret create(UUID projectId, String name, @Nullable String description, String value) {
        var project = projectService.require(projectId);
        if (ProjectSecret.findIn(projectId, name).isPresent()) {
            throw new ClientErrorException(
                    "project " + projectId + " already has a secret named " + name,
                    Response.Status.CONFLICT);
        }
        var secret = ProjectSecret.of(
                project, name, normalize(description), cipher.seal(value, contextOf(projectId, name)));
        secret.persist();
        return secret;
    }

    /**
     * Updates an existing secret's description and/or value.
     */
    @Transactional
    public Optional<ProjectSecret> update(
            UUID projectId, String name, @Nullable String description, @Nullable String value) {
        var secret = ProjectSecret.findIn(projectId, name);
        secret.ifPresent(it -> {
            if (description != null) {
                it.setDescription(normalize(description));
            }
            if (value != null) {
                it.setCipherText(cipher.seal(value, contextOf(projectId, name)));
            }
        });
        return secret;
    }

    @Nullable
    private static String normalize(@Nullable String description) {
        return description == null || description.isBlank() ? null : description;
    }

    /** Deletes a secret by name. Returns false if not found. */
    @Transactional
    public boolean delete(UUID projectId, String name) {
        return ProjectSecret.deleteIn(projectId, name);
    }

    /**
     * Resolves and decrypts all secrets for a project into plaintext key-value pairs for job execution.
     */
    @Transactional
    public Map<String, String> resolve(UUID projectId) {
        var resolved = new LinkedHashMap<String, String>();
        for (var secret : ProjectSecret.listByProject(projectId)) {
            var name = secret.getName();
            resolved.put(name, cipher.open(secret.getCipherText(), contextOf(projectId, name)));
        }
        return resolved;
    }

    private static String contextOf(UUID projectId, String name) {
        return projectId + "/" + name;
    }
}
