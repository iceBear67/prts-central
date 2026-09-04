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
 * Project-scoped secrets, sealed on the way in and opened on the way out. Nothing outside this
 * package sees a plaintext value except {@link #resolve(UUID)}, which exists for injecting them into
 * a job at dispatch time — a caller was gated by the endpoint, as everywhere else in this codebase.
 *
 * <p>There is no re-sealing here: rotating a key is an offline job, done by {@code scripts/secrets.py}
 * against the database directly.
 */
@ApplicationScoped
public class SecretService {

    @Inject
    ProjectService projectService;
    @Inject
    SecretCipher cipher;

    /** The rows, not their values: {@link ProjectSecret#getCipherText()} is still sealed. */
    public List<ProjectSecret> list(UUID projectId) {
        projectService.require(projectId);
        return ProjectSecret.listByProject(projectId);
    }

    /**
     * Conflicts rather than replacing: rotating a secret is a delete and a create, so a create can
     * never silently drop the value a job is running with.
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
                project, name, description, cipher.seal(value, contextOf(projectId, name)));
        secret.persist();
        return secret;
    }

    /** The description is the only thing about a secret that can be changed in place. */
    @Transactional
    public Optional<ProjectSecret> describe(UUID projectId, String name, @Nullable String description) {
        var secret = ProjectSecret.findIn(projectId, name);
        secret.ifPresent(it -> it.setDescription(description));
        return secret;
    }

    /** {@code false} means the project has no secret by that name. */
    @Transactional
    public boolean delete(UUID projectId, String name) {
        return ProjectSecret.deleteIn(projectId, name);
    }

    /**
     * Every secret of the project in the clear. The one caller is {@code JobLauncher}, which attaches
     * the result to the copy of the spec it hands the scheduler — never to the one it persists — so
     * the plaintext lives only as long as the dispatch does.
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

    /** What the ciphertext is bound to, so a row cannot be moved to another project or name. */
    private static String contextOf(UUID projectId, String name) {
        return projectId + "/" + name;
    }
}
