package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.project.SubAccountView;
import io.ib67.prts.job.entity.Project;
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

    /** Lists all sub-accounts of a project (used by {@code ProjectService.delete}). */
    public List<SubAccount> list(UUID projectId) {
        return SubAccount.listByProjectFetched(projectId);
    }

    /** Lists a paginated slice of sub-accounts of a project. */
    public List<SubAccount> list(UUID projectId, int offset, int length) {
        return SubAccount.listByProjectFetched(projectId, offset, length);
    }

    /** Finds a sub-account in a project or throws {@link NotFoundException}. */
    public SubAccount require(UUID projectId, UUID userId) {
        return SubAccount.findInProject(projectId, userId)
                .orElseThrow(() -> new NotFoundException(
                        "no such sub-account in project " + projectId + ": " + userId));
    }

    /** Builds a view for a sub-account, resolving its current permissions. */
    public SubAccountView viewOf(UUID projectId, SubAccount account) {
        return viewOf(account, userService.permissionsOf(account.getUserId(), projectId));
    }

    /**
     * Builds a view for a sub-account with pre-supplied permissions to avoid stale cache reads before commit.
     */
    public SubAccountView viewOf(SubAccount account, Collection<Perm> permissions) {
        return view(account, permissions, UserInfo.of(account.getCreatedBy()));
    }

    /** Builds views for a list of sub-accounts, batch-resolving creator details. */
    public List<SubAccountView> viewOf(UUID projectId, List<SubAccount> accounts) {
        var users = User.mapByIds(accounts.stream().map(SubAccount::getCreatedBy).distinct().toList());
        return accounts.stream()
                .map(account -> view(
                        account,
                        userService.permissionsOf(account.getUserId(), projectId),
                        UserInfo.of(account.getCreatedBy(), users.get(account.getCreatedBy()))))
                .toList();
    }

    private static SubAccountView view(SubAccount account, Collection<Perm> permissions, UserInfo createdBy) {
        return new SubAccountView(
                account.getUserId(),
                account.getUser().getName(),
                permissions.stream().map(Perm::permission).sorted().toList(),
                createdBy,
                account.getCreatedAt());
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
