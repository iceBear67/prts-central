package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.auth.OAuthIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class UserService {

    /** Both beans are normal-scoped, so the cycle with {@link PermissionService} goes through a proxy. */
    @Inject
    PermissionService permissionService;

    public Optional<User> findById(UUID id) {
        return User.findByIdOptional(id);
    }

    public Optional<User> findByEmail(String email) {
        return User.findByEmail(email);
    }

    public Optional<User> findByIssuerAndSubject(String issuer, String subject) {
        return OAuthIdentity.findUser(issuer, subject);
    }

    @Transactional
    public User register(String name, String email) {
        var user = User.builder().name(name).email(email).build();
        user.persist();
        return user;
    }

    @Transactional
    public User provision(String issuer, String subject, String name, String email) {
        var existing = findByIssuerAndSubject(issuer, subject);
        if (existing.isPresent()) {
            return existing.get();
        }
        var user = register(name, email);
        OAuthIdentity.of(issuer, subject, user).persist();
        return user;
    }

    @Transactional
    public OAuthIdentity linkIdentity(UUID userId, String issuer, String subject) {
        requireNotSubAccount(userId, "be linked to a login");
        var identity = OAuthIdentity.of(issuer, subject, requireUser(userId));
        identity.persist();
        return identity;
    }

    @Transactional
    public boolean unlinkIdentity(String issuer, String subject) {
        return OAuthIdentity.deleteById(new OAuthIdentity.Id(issuer, subject));
    }

    @Transactional
    public User updateProfile(UUID userId, String name, String email) {
        var user = requireUser(userId);
        user.setName(name);
        user.setEmail(email);
        return user;
    }

    public ProjectRole roleOf(UUID userId, UUID projectId) {
        return UserToProject.findByUserAndProject(userId, projectId)
                .map(UserToProject::getProjectRole)
                .orElse(ProjectRole.NONE);
    }

    public boolean hasAtLeast(UUID userId, UUID projectId, ProjectRole required) {
        return roleOf(userId, projectId).ordinal() <= required.ordinal();
    }

    /**
     * Every {@link Perm} the user holds in one project — {@link PermissionService#has} the other way
     * round, for a caller listing grants rather than asking about one. {@link Permission#GLOBAL} lists
     * the global ones.
     */
    public Set<Perm> permissionsOf(UUID userId, UUID projectId) {
        return permissionService.grantsOf(userId).stream()
                .filter(grant -> projectId.equals(grant.getProjectId()))
                .flatMap(grant -> Perm.byPermission(grant.getPermission()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Declarative, and the counterpart of {@link #grant} for what a role cannot express: whatever the
     * user held in {@code projectId} is replaced by {@code perms}, and what they hold in another
     * project is untouched.
     *
     * <p>A {@link Perm#global()} one is refused whoever the user is — {@link Permission#idOf} would
     * scope it to {@link Permission#GLOBAL}, so it would leave the project this is setting. A
     * sub-account is refused {@link Perm#PROJECT_SUBACCOUNT_MANAGE} on top, which is what stops it
     * minting further ones.
     */
    @Transactional
    public void setPermissions(UUID userId, UUID projectId, Collection<Perm> perms) {
        var subAccount = SubAccount.isSubAccount(userId);
        perms.forEach(perm -> requireGrantable(perm, subAccount));
        permissionService.revokeAll(userId, projectId);
        permissionService.grantAll(userId, perms, projectId);
    }

    /** Memberships rather than projects, so callers can show the role along with each project. */
    public List<UserToProject> listMemberships(UUID userId) {
        return UserToProject.listByUserFetched(userId);
    }

    public List<UserToProject> listMembers(UUID projectId) {
        return UserToProject.listByProjectFetched(projectId);
    }

    @Transactional
    public UserToProject grant(UUID userId, UUID projectId, ProjectRole projectRole) {
        requireNotSubAccount(userId, "hold a project role");
        lockRoster(projectId);
        var existing = UserToProject.findByUserAndProject(userId, projectId);
        if (existing.isPresent()) {
            var link = existing.get();
            if (link.getProjectRole() == ProjectRole.OWNER && projectRole != ProjectRole.OWNER) {
                requireAnotherOwner(userId, projectId);
            }
            link.setProjectRole(projectRole);
            return link;
        }
        var link = UserToProject.of(requireUser(userId), requireProject(projectId), projectRole);
        link.persist();
        return link;
    }

    @Transactional
    public boolean revoke(UUID userId, UUID projectId) {
        lockRoster(projectId);
        UserToProject.findByUserAndProject(userId, projectId)
                .filter(link -> link.getProjectRole() == ProjectRole.OWNER)
                .ifPresent(link -> requireAnotherOwner(userId, projectId));
        return UserToProject.deleteById(new UserToProject.Id(userId, projectId));
    }

    /**
     * Everything the user owns, in the order the foreign keys allow. Their token and, for a
     * sub-account, the {@link SubAccount} row go with them at the database.
     *
     * <p>No external resources of its own: jobs belong to projects, and {@code job.requested_by} is a
     * bare column with no FK, so deleting whoever asked for a run leaves the run alone.
     */
    @Transactional
    public boolean delete(UUID userId) {
        var user = User.<User>findByIdOptional(userId);
        if (user.isEmpty()) {
            return false;
        }
        listMemberships(userId).stream()
                .filter(link -> link.getProjectRole() == ProjectRole.OWNER)
                .forEach(link -> requireAnotherOwner(userId, link.getProject().getId()));
        permissionService.revokeAll(userId);
        UserToProject.delete("id.userId", userId);
        OAuthIdentity.delete("user.id", userId);
        user.get().delete();
        return true;
    }

    private static void requireGrantable(Perm perm, boolean subAccount) {
        if (perm.global()) {
            throw new BadRequestException(
                    "not a project's to grant: " + perm.permission());
        }
        if (subAccount && perm == Perm.PROJECT_SUBACCOUNT_MANAGE) {
            throw new BadRequestException(
                    "a sub-account may not hold " + perm.permission());
        }
    }

    /**
     * A sub-account is project property, not a person: giving it a login or a project role would make
     * it one, and a role would also put it on a roster the last-owner rule counts.
     */
    private void requireNotSubAccount(UUID userId, String what) {
        if (SubAccount.isSubAccount(userId)) {
            throw new ClientErrorException(
                    "a sub-account cannot " + what + ": " + userId, Response.Status.CONFLICT);
        }
    }

    /**
     * Serializes roster changes on one project. {@link #requireAnotherOwner} counts owners and then
     * mutates, so without this two owners leaving at the same moment would each see the other and both
     * succeed, emptying the project of owners.
     */
    private void lockRoster(UUID projectId) {
        if (Project.findById(projectId, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new NoSuchElementException("no such project: " + projectId);
        }
    }

    /**
     * A project whose last owner steps down can never be administered again — no one left could grant
     * the role back — so both demotion and removal stop here.
     */
    private void requireAnotherOwner(UUID userId, UUID projectId) {
        if (!UserToProject.hasOtherOwner(projectId, userId)) {
            throw new ClientErrorException(
                    "the last owner of project " + projectId + " cannot step down",
                    Response.Status.CONFLICT);
        }
    }

    private User requireUser(UUID id) {
        return User.<User>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such user: " + id));
    }

    private Project requireProject(UUID id) {
        return Project.<Project>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such project: " + id));
    }
}
