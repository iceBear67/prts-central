package io.ib67.prts.testing;

import io.ib67.prts.Perm;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the actors and projects a tier C test needs.
 *
 * <p>Authentication goes through a real personal access token rather than {@code @TestSecurity},
 * which yields a bare principal that {@code UserIdentityAugmenter} cannot map to a {@code User}.
 */
@ApplicationScoped
public class Fixtures {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Inject
    UserService userService;
    @Inject
    ProjectService projectService;
    @Inject
    AccessTokenService accessTokenService;
    @Inject
    PermissionService permissionService;

    /** Registers a user and issues them a token. */
    public Actor actor(String name) {
        // Emails are unique per user; the counter keeps two actors of the same name apart.
        var user = userService.register(name, name + COUNTER.incrementAndGet() + "@example.test");
        return new Actor(user.getId(), accessTokenService.issue(user.getId()).token());
    }

    public UUID project(String name) {
        return projectService.create(name).getId();
    }

    public void join(Actor actor, UUID projectId, ProjectRole role) {
        userService.grant(actor.id(), projectId, role);
    }

    /** Grants {@link Perm#ADMIN_OF_ALL}, which is global and so takes no project. */
    public void makeAdmin(Actor actor) {
        permissionService.grant(actor.id(), Perm.ADMIN_OF_ALL, null);
    }

    /**
     * @param token the plaintext token, to be sent as {@code Authorization: Bearer <token>}
     */
    public record Actor(UUID id, String token) {
    }
}
