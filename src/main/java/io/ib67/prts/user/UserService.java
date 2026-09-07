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

    /** Lists all permissions granted to a user within a specific project. */
    public Set<Perm> permissionsOf(UUID userId, UUID projectId) {
        return permissionService.grantsOf(userId).stream()
                .filter(grant -> projectId.equals(grant.getProjectId()))
                .flatMap(grant -> Perm.byPermission(grant.getPermission()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Sets the user's project permissions to the given set, replacing existing grants in that project.
     * Global permissions and sub-account management by sub-accounts are disallowed.
     */
    @Transactional
    public void setPermissions(UUID userId, UUID projectId, Collection<Perm> perms) {
        var subAccount = SubAccount.isSubAccount(userId);
        perms.forEach(perm -> requireGrantable(perm, subAccount));
        permissionService.revokeAll(userId, projectId);
        permissionService.grantAll(userId, perms, projectId);
    }

    /** Lists project memberships for a user. */
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
     * Deletes a user and their memberships, permissions, identities, and tokens.
     * Ensures the user is not the sole owner of any project.
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

    private void requireNotSubAccount(UUID userId, String what) {
        if (SubAccount.isSubAccount(userId)) {
            throw new ClientErrorException(
                    "a sub-account cannot " + what + ": " + userId, Response.Status.CONFLICT);
        }
    }

    /** Locks the project row to serialize roster changes and prevent concurrent owner departures. */
    private void lockRoster(UUID projectId) {
        if (Project.findById(projectId, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new NoSuchElementException("no such project: " + projectId);
        }
    }

    /** Ensures the project retains at least one owner. */
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
