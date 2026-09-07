package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Service managing project sub-accounts and their permissions.
 */
@ApplicationScoped
public class SubAccountService {

    private static final String NO_EMAIL = "";

    @Inject
    UserService userService;

    @Transactional
    public SubAccount create(UUID projectId, String name, UUID createdBy) {
        var account = SubAccount.of(
                userService.register(name, NO_EMAIL), requireProject(projectId), createdBy);
        account.persistAndFlush();
        return account;
    }

    public List<SubAccount> list(UUID projectId) {
        return SubAccount.listByProjectFetched(projectId);
    }

    /** Finds a sub-account in a project or throws {@link NotFoundException}. */
    public SubAccount require(UUID projectId, UUID userId) {
        return SubAccount.findInProject(projectId, userId)
                .orElseThrow(() -> new NotFoundException(
                        "no such sub-account in project " + projectId + ": " + userId));
    }

    @Transactional
    public void setPermissions(UUID projectId, UUID userId, Collection<Perm> perms) {
        require(projectId, userId);
        userService.setPermissions(userId, projectId, perms);
    }

    @Transactional
    public void delete(UUID projectId, UUID userId) {
        require(projectId, userId);
        userService.delete(userId);
    }

    private static Project requireProject(UUID id) {
        return Project.<Project>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such project: " + id));
    }
}
