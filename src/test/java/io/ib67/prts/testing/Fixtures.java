package io.ib67.prts.testing;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
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

    public UUID createProject(String name) {
        return projectService.create(name).getId();
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

    /** Creates a project-scoped or global resource class. */
    @Transactional
    public ResourceClass createResourceClass(String name, @Nullable UUID projectId) {
        var klass = ResourceClass.builder()
                .name(name)
                .projectId(ResourceClass.scopeOf(projectId))
                .numCpus(1)
                .memCount(512)
                .diskSize(1024)
                .build();
        klass.persistAndFlush();
        return klass;
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
        var job = Job.builder()
                .project(Project.<Project>findById(projectId))
                .requestedBy(requestedBy.id())
                .resourceClass(attach(klass))
                .spec(spec("alpine"))
                .worker(workerId)
                .templateId(templateId)
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
        var now = Instant.now();
        return createQueuedJob(projectId, requestedBy, templateId, resourceClass,
                now.plus(Duration.ofHours(1)), now);
    }

    /**
     * Creates a pending job directly in the database without requiring an active {@code UserContext}.
     */
    @Transactional
    public UUID createQueuedJob(UUID projectId, Actor requestedBy, UUID templateId, String resourceClass,
                                Instant expiresAt, Instant nextAttemptAt) {
        var pending = PendingJob.builder()
                .project(Project.<Project>findById(projectId))
                .requestedBy(requestedBy.id())
                // resource_class is required on pending_job.
                .request(new JobRequest(templateId, null, resourceClass))
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
        Worker.upsert(id, name);
        return id;
    }

    /** Creates a worker volume record. */
    @Transactional
    public UUID createVolume(UUID projectId, UUID workerId, String name) {
        var volume = WorkerVolume.builder()
                .name(name)
                .worker(Worker.<Worker>findById(workerId))
                .project(Project.<Project>findById(projectId))
                .length(1024)
                .used(0)
                .build();
        volume.persistAndFlush();
        return volume.getId();
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
        return new JobSpec(image, null, null, null, null, 0, "", null);
    }

    /** Re-attaches a detached entity to the current persistence context. */
    private static ResourceClass attach(ResourceClass klass) {
        return ResourceClass.findById(klass.key());
    }

    public record Actor(UUID id, String token) {
    }
}
