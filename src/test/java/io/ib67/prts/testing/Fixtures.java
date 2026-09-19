package io.ib67.prts.testing;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.*;
import io.ib67.prts.job.task.TaskScope;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskVolume;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobLog;
import io.ib67.prts.job.entity.JobRequest;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.Project;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.secret.SecretService;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.SubAccountService;
import io.ib67.prts.user.UserService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Test fixtures for creating actors, projects, and entities for E2E tests.
 *
 * <p>Authentication uses real Personal Access Tokens to exercise the full authentication chain.
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
    @Inject
    SubAccountService subAccountService;
    @Inject
    SecretService secretService;

    /** Registers a user and issues them a token. */
    public Actor createActor(String name) {
        // Ensure unique emails per generated actor.
        var user = userService.register(name, name + COUNTER.incrementAndGet() + "@example.test");
        return new Actor(user.getId(), accessTokenService.issue(user.getId()).token());
    }

    /**
     * Creates a project owned by an unprivileged test user.
     */
    public UUID createProject(String name) {
        return createProject(name, createActor("nobody"));
    }

    public UUID createProject(String name, Actor owner) {
        return createProject(name, "", owner);
    }

    public UUID createProject(String name, String description, Actor owner) {
        return projectService.create(name, description, owner.id()).getId();
    }

    /** Archives a project directly via ProjectService. */
    public void archive(UUID projectId) {
        projectService.archive(projectId);
    }

    public void join(Actor actor, UUID projectId, ProjectRole role) {
        userService.grant(actor.id(), projectId, role);
    }

    /** Grants {@link Perm#ADMIN_OF_ALL} globally to the actor. */
    public void makeAdmin(Actor actor) {
        permissionService.grant(actor.id(), Perm.ADMIN_OF_ALL, null);
    }

    /** Grants a project-scoped permission to the actor. */
    public void grant(Actor actor, Perm perm, UUID projectId) {
        permissionService.grant(actor.id(), perm, projectId);
    }

    public void revoke(Actor actor, Perm perm, UUID projectId) {
        permissionService.revoke(actor.id(), perm, projectId);
    }

    /** Creates a sub-account for the project and returns an Actor with its token. */
    public Actor createSubAccount(UUID projectId, String name, Actor createdBy) {
        var account = subAccountService.create(projectId, name, createdBy.id());
        return new Actor(account.getUserId(), accessTokenService.issue(account.getUserId()).token());
    }

    public void createSecret(UUID projectId, String name, String value) {
        secretService.create(projectId, name, null, value);
    }

    public ResourceClass createResourceClass(String name) {
        return createResourceClass(name, true);
    }

    /** A class that is not shared is open only to the projects passed to {@link #allowResourceClass}. */
    @Transactional
    public ResourceClass createResourceClass(String name, boolean shared) {
        var klass = ResourceClass.builder()
                .name(name)
                .numCpus(1)
                .memCount(512)
                .diskSize(1024)
                .shared(shared)
                .build();
        klass.persistAndFlush();
        return klass;
    }

    @Transactional
    public void allowResourceClass(UUID projectId, ResourceClass klass) {
        ProjectResourceClass.of(Project.<Project>findById(projectId), attach(klass)).persistAndFlush();
    }

    /** Creates a project-scoped or global template. */
    @Transactional
    public UUID createTemplate(String name, @Nullable UUID projectId, ResourceClass klass) {
        var template = JobSpecTemplate.builder()
                .name(name)
                .spec(spec("alpine"))
                .resourceClass(attach(klass))
                .project(projectId == null ? null : Project.<Project>findById(projectId))
                .build();
        template.persistAndFlush();
        return template.getId();
    }

    /** Creates a job without a template. */
    @Transactional
    public UUID createJob(UUID projectId, Actor requestedBy, ResourceClass klass, JobState state,
                          @Nullable UUID workerId) {
        return createJob(projectId, requestedBy, klass, state, workerId, null);
    }

    @Transactional
    public UUID createJob(UUID projectId, Actor requestedBy, ResourceClass klass, JobState state,
                          @Nullable UUID workerId, @Nullable UUID templateId) {
        return createJob(projectId, requestedBy, klass, state, workerId, templateId, null);
    }

    @Transactional
    public UUID createJob(UUID projectId, Actor requestedBy, ResourceClass klass, JobState state,
                          @Nullable UUID workerId, @Nullable UUID templateId, @Nullable UUID taskId) {
        var job = Job.builder()
                .project(Project.<Project>findById(projectId))
                .requestedBy(requestedBy.id())
                .resourceClass(attach(klass))
                .spec(spec("alpine"))
                .worker(workerId)
                // Placement is what stamps startedAt, so a fixture that has a host has one too.
                .startedAt(workerId == null ? null : Instant.now())
                .templateId(templateId)
                .taskId(taskId)
                .build();
        // Update state via transitionTo to ensure timestamps are set properly.
        job.transitionTo(state);
        job.persistAndFlush();
        return job.getId();
    }

    @Transactional
    public UUID createArtifact(UUID jobId, String name) {
        var artifact = Artifact.builder()
                .name(name)
                .objectKey("test/" + jobId + "/" + name)
                .sizeBytes(1)
                .job(Job.<Job>findById(jobId))
                .build();
        artifact.persistAndFlush();
        return artifact.getId();
    }

    @Transactional
    public void createLog(UUID jobId, String topic, String message) {
        JobLog.builder()
                .job(Job.<Job>findById(jobId))
                .topic(topic)
                .message(message)
                .error(false)
                .build()
                .persistAndFlush();
    }

    /** Creates a queued pending job. */
    public UUID createQueuedJob(UUID projectId, Actor requestedBy, UUID templateId, String resourceClass) {
        return createQueuedJob(projectId, requestedBy, templateId, resourceClass, null);
    }

    /** Creates a queued pending job scoped to a task. */
    public UUID createQueuedJob(UUID projectId, Actor requestedBy, UUID templateId, String resourceClass,
                                @Nullable UUID taskId) {
        var now = Instant.now();
        return createQueuedJob(projectId, requestedBy, templateId, resourceClass, taskId,
                now.plus(Duration.ofHours(1)), now);
    }

    public UUID createQueuedJob(UUID projectId, Actor requestedBy, UUID templateId, String resourceClass,
                                Instant expiresAt, Instant nextAttemptAt) {
        return createQueuedJob(projectId, requestedBy, templateId, resourceClass, null,
                expiresAt, nextAttemptAt);
    }

    /**
     * Creates a pending job directly in the database without requiring an active {@code UserContext}.
     */
    @Transactional
    public UUID createQueuedJob(UUID projectId, Actor requestedBy, UUID templateId, String resourceClass,
                                @Nullable UUID taskId, Instant expiresAt, Instant nextAttemptAt) {
        var pending = PendingJob.builder()
                .project(Project.<Project>findById(projectId))
                .requestedBy(requestedBy.id())
                // resource_class is required on pending_job.
                .request(new JobRequest(templateId, null, resourceClass, taskId))
                .state(PendingJobState.QUEUED)
                .expiresAt(expiresAt)
                .nextAttemptAt(nextAttemptAt)
                .build();
        pending.persistAndFlush();
        return pending.getId();
    }

    /** Creates a worker record in the database. */
    @Transactional
    public UUID createWorker(String name) {
        var id = UUID.randomUUID();
        WorkerEntity.upsert(id, name);
        return id;
    }

    /** Creates a worker volume record in READY state. */
    @Transactional
    public UUID createVolume(UUID projectId, UUID workerId, String name) {
        return createVolume(projectId, workerId, name, VolumeState.READY);
    }

    @Transactional
    public UUID createVolume(UUID projectId, UUID workerId, String name, VolumeState state) {
        var volume = WorkerVolume.builder()
                .name(name)
                .worker(WorkerEntity.<WorkerEntity>findById(workerId))
                .project(Project.<Project>findById(projectId))
                .length(1024)
                .used(0)
                .state(state)
                .build();
        volume.persistAndFlush();
        return volume.getId();
    }

    /** Creates a task within a project. */
    @Transactional
    public UUID createTask(UUID projectId, Actor createdBy, String name, TaskScope scope) {
        var task = Task.builder()
                .project(Project.<Project>findById(projectId))
                .name(name)
                .scope(scope == null ? TaskScope.EMPTY : scope)
                .createdBy(createdBy.id())
                .build();
        task.persistAndFlush();
        return task.getId();
    }

    /** Associates a worker volume with a task at the given mount point. */
    @Transactional
    public void mountVolume(UUID taskId, UUID volumeId, String mountPoint) {
        TaskVolume.of(
                        Task.<Task>findById(taskId),
                        WorkerVolume.<WorkerVolume>findById(volumeId),
                        mountPoint)
                .persistAndFlush();
    }

    /** Returns a RestAssured RequestSpecification with the actor's bearer token. */
    public static RequestSpecification as(Actor actor) {
        return RestAssured.given().header("Authorization", "Bearer " + actor.token());
    }

    /**
     * Executes a supplier within a new transaction and returns the result.
     */
    public static <T> T inTx(Supplier<T> body) {
        return QuarkusTransaction.requiringNew().call(body::get);
    }

    /** Executes a runnable within a new transaction. */
    public static void inTx(Runnable body) {
        QuarkusTransaction.requiringNew().run(body);
    }

    public static JobSpec spec(String image) {
        return new JobSpec(image, null, null, null, null, null, 0, "", null);
    }

    /** Re-attaches a detached entity to the current persistence context. */
    private static ResourceClass attach(ResourceClass klass) {
        return ResourceClass.findById(klass.getName());
    }

    public record Actor(UUID id, String token) {
    }
}
