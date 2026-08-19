package io.ib67.prts.user;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
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

    @CacheName(CACHE_NAME)
    Cache cache;

    public Set<String> permissionsOf(UUID userId) {
        return cache.<UUID, Set<String>>get(userId, this::loadPermissions).await().indefinitely();
    }

    public boolean has(UUID userId, String permission) {
        return permissionsOf(userId).contains(permission);
    }

    public boolean hasAny(UUID userId, Collection<String> permissions) {
        var granted = permissionsOf(userId);
        return permissions.stream().anyMatch(granted::contains);
    }

    public boolean hasAll(UUID userId, Collection<String> permissions) {
        return permissionsOf(userId).containsAll(permissions);
    }

    public List<User> listUsersWith(String permission) {
        return entityManager.createQuery(
                        "select p.user from Permission p where p.id.permission = ?1", User.class)
                .setParameter(1, permission)
                .getResultList();
    }

    @Transactional
    public boolean grant(UUID userId, String permission) {
        if (Permission.findByIdOptional(new Permission.Id(userId, permission)).isPresent()) {
            return false;
        }
        Permission.of(requireUser(userId), permission).persist();
        invalidateAfterCompletion(userId);
        return true;
    }

    @Transactional
    public void grantAll(UUID userId, Collection<String> permissions) {
        var granted = loadPermissions(userId);
        var user = requireUser(userId);
        permissions.stream()
                .distinct()
                .filter(permission -> !granted.contains(permission))
                .forEach(permission -> Permission.of(user, permission).persist());
        invalidateAfterCompletion(userId);
    }

    @Transactional
    public boolean revoke(UUID userId, String permission) {
        var removed = Permission.deleteById(new Permission.Id(userId, permission));
        invalidateAfterCompletion(userId);
        return removed;
    }

    @Transactional
    public long revokeAll(UUID userId) {
        var removed = Permission.delete("id.userId", userId);
        invalidateAfterCompletion(userId);
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

    private Set<String> loadPermissions(UUID userId) {
        return Set.copyOf(entityManager.createQuery(
                        "select p.id.permission from Permission p where p.id.userId = ?1", String.class)
                .setParameter(1, userId)
                .getResultList());
    }

    private User requireUser(UUID id) {
        return User.<User>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such user: " + id));
    }
}
