package io.ib67.prts.user;

import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.project.Project;
import io.ib67.prts.auth.OAuthIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UserService {

    @Inject
    EntityManager entityManager;

    public Optional<User> findById(UUID id) {
        return User.findByIdOptional(id);
    }

    public Optional<User> findByEmail(String email) {
        return User.findByEmail(email);
    }

    public Optional<User> findByIssuerAndSubject(String issuer, String subject) {
        return entityManager.createQuery(
                        "select i.user from OAuthIdentity i where i.id.issuer = ?1 and i.id.subject = ?2",
                        User.class)
                .setParameter(1, issuer)
                .setParameter(2, subject)
                .getResultStream()
                .findFirst();
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

    public ProjectRole permissionOf(UUID userId, UUID projectId) {
        return UserToProject.findByUserAndProject(userId, projectId)
                .map(UserToProject::getProjectRole)
                .orElse(ProjectRole.NONE);
    }

    public boolean hasAtLeast(UUID userId, UUID projectId, ProjectRole required) {
        return permissionOf(userId, projectId).ordinal() <= required.ordinal();
    }

    public List<Project> listProjects(UUID userId) {
        return entityManager.createQuery(
                        "select l.project from UserToProject l where l.id.userId = ?1", Project.class)
                .setParameter(1, userId)
                .getResultList();
    }

    @Transactional
    public UserToProject grant(UUID userId, UUID projectId, ProjectRole projectRole) {
        var existing = UserToProject.findByUserAndProject(userId, projectId);
        if (existing.isPresent()) {
            var link = existing.get();
            link.setProjectRole(projectRole);
            return link;
        }
        var link = UserToProject.of(requireUser(userId), requireProject(projectId), projectRole);
        link.persist();
        return link;
    }

    @Transactional
    public boolean revoke(UUID userId, UUID projectId) {
        return UserToProject.deleteById(new UserToProject.Id(userId, projectId));
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
