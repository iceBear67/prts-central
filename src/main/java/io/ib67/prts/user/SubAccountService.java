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
 * Sub-accounts of a project. Every method that names one goes through {@link #require}, so the
 * project in the path is what bounds the user id in it.
 */
@ApplicationScoped
public class SubAccountService {

    /**
     * A sub-account carries no address. The column is not null, so it is blank rather than absent —
     * and {@link User#findByEmail} refuses a blank one, so nothing resolves a sub-account by it.
     */
    private static final String NO_EMAIL = "";

    @Inject
    UserService userService;

    /** Flushed, not just persisted: {@code createdAt} is generated at insert, and the caller maps the
     *  returned entity straight into a view. */
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

    /** A user id that is not this project's sub-account is a 404, not a different answer. */
    public SubAccount require(UUID projectId, UUID userId) {
        return SubAccount.findInProject(projectId, userId)
                .orElseThrow(() -> new NotFoundException(
                        "no such sub-account in project " + projectId + ": " + userId));
    }

    /**
     * The containment check and then the ordinary write, like {@link #delete}: what a sub-account may
     * not be granted is {@link UserService#setPermissions}'s rule, not this one's, so it holds however
     * the write is reached.
     */
    @Transactional
    public void setPermissions(UUID projectId, UUID userId, Collection<Perm> perms) {
        require(projectId, userId);
        userService.setPermissions(userId, projectId, perms);
    }

    /** Deleting the user is the whole of it: the link and its token cascade at the database. */
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
