package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class PermissionService {

    public static final String CACHE_NAME = "user-permissions";

    @Inject
    EntityManager entityManager;

    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    @Inject
    UserService userService;

    @CacheName(CACHE_NAME)
    Cache cache;

    /** Every grant the user holds, each carrying the project it was granted in. */
    public Set<Permission.Id> grantsOf(UUID userId) {
        return cache.<UUID, Set<Permission.Id>>get(userId, this::loadGrants).await().indefinitely();
    }

    public boolean has(UUID userId, Perm perm) {
        return has(userId, perm, null);
    }

    /**
     * A project-scoped permission matches only a grant in {@code projectId} — nothing implies it
     * across projects, which is what {@link Perm#ADMIN_OF_ALL} is for. A missing project therefore
     * matches nothing instead of throwing: callers use this to decide, not to validate.
     */
    public boolean has(UUID userId, Perm perm, @Nullable UUID projectId) {
        var id = Permission.idOf(userId, perm, projectId);
        return id.getProjectId() != null && grantsOf(userId).contains(id);
    }

    public boolean isAdmin(UUID userId) {
        return has(userId, Perm.ADMIN_OF_ALL);
    }

    /**
     * The decision {@code @RequirePermission(value = perm, defaultRole = defaultRole)} makes,
     * answered instead of enforced — for a caller that varies what it returns rather than refusing.
     * Kept in step with {@link io.ib67.prts.auth.RequirePermissionInterceptor} by hand; it stays a
     * separate method because the interceptor must throw and this must not.
     */
    public boolean allows(UUID userId, Perm perm, @Nullable UUID projectId, ProjectRole defaultRole) {
        return isAdmin(userId)
                || has(userId, perm, projectId)
                || (defaultRole != ProjectRole.NONE && userService.hasAtLeast(userId, projectId, defaultRole));
    }

    public boolean hasAny(UUID userId, Collection<Perm> perms, @Nullable UUID projectId) {
        return perms.stream().anyMatch(perm -> has(userId, perm, projectId));
    }

    public boolean hasAll(UUID userId, Collection<Perm> perms, @Nullable UUID projectId) {
        return perms.stream().allMatch(perm -> has(userId, perm, projectId));
    }

    public List<User> listUsersWith(Perm perm, @Nullable UUID projectId) {
        return entityManager.createQuery(
                        "select p.user from Permission p "
                                + "where p.id.permission = ?1 and p.id.projectId = ?2", User.class)
                .setParameter(1, perm.permission())
                .setParameter(2, requireScope(perm, projectId))
                .getResultList();
    }

    @Transactional
    public boolean grant(UUID userId, Perm perm, @Nullable UUID projectId) {
        var scope = requireScope(perm, projectId);
        if (Permission.findByIdOptional(Permission.idOf(userId, perm, scope)).isPresent()) {
            return false;
        }
        Permission.of(requireUser(userId), perm, scope).persist();
        invalidateAfterCompletion(userId);
        return true;
    }

    @Transactional
    public void grantAll(UUID userId, Collection<Perm> perms, @Nullable UUID projectId) {
        var granted = loadGrants(userId);
        var user = requireUser(userId);
        perms.stream()
                .distinct()
                .filter(perm -> !granted.contains(
                        Permission.idOf(userId, perm, requireScope(perm, projectId))))
                .forEach(perm -> Permission.of(user, perm, requireScope(perm, projectId)).persist());
        invalidateAfterCompletion(userId);
    }

    @Transactional
    public boolean revoke(UUID userId, Perm perm, @Nullable UUID projectId) {
        var removed = Permission.deleteById(
                Permission.idOf(userId, perm, requireScope(perm, projectId)));
        invalidateAfterCompletion(userId);
        return removed;
    }

    @Transactional
    public long revokeAll(UUID userId) {
        var removed = Permission.delete("id.userId", userId);
        invalidateAfterCompletion(userId);
        return removed;
    }

    /** Only what the user holds in {@code projectId}; grants made in another project are untouched. */
    @Transactional
    public long revokeAll(UUID userId, UUID projectId) {
        var removed = Permission.deleteByUserInProject(userId, projectId);
        invalidateAfterCompletion(userId);
        return removed;
    }

    /**
     * Every grant made in {@code projectId}, for a project being deleted. Nothing else removes them:
     * the project half of the key carries no foreign key. The holders are read first because the
     * cache is keyed by user.
     */
    @Transactional
    public long revokeAllInProject(UUID projectId) {
        var holders = Permission.listHolderIdsByProject(projectId);
        var removed = Permission.deleteByProject(projectId);
        holders.forEach(this::invalidateAfterCompletion);
        return removed;
    }

    public void invalidate(UUID userId) {
        cache.invalidate(userId).await().indefinitely();
    }

    public void invalidateAll() {
        cache.invalidateAll().await().indefinitely();
    }

    private void invalidateAfterCompletion(UUID userId) {
        transactionRegistry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                invalidate(userId);
            }
        });
    }

    private Set<Permission.Id> loadGrants(UUID userId) {
        return Set.copyOf(entityManager.createQuery(
                        "select p.id from Permission p where p.id.userId = ?1", Permission.Id.class)
                .setParameter(1, userId)
                .getResultList());
    }

    /** A project-scoped row without a project could never be matched by {@link #has}. */
    private static UUID requireScope(Perm perm, @Nullable UUID projectId) {
        var scope = perm.global() ? Permission.GLOBAL : projectId;
        if (scope == null) {
            throw new IllegalArgumentException("project is required for " + perm.permission());
        }
        return scope;
    }

    private User requireUser(UUID id) {
        return User.<User>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such user: " + id));
    }
}
