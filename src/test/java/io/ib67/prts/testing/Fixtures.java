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
import io.ib67.prts.project.entity.Artifact;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobLog;
import io.ib67.prts.project.entity.JobRequest;
import io.ib67.prts.project.entity.JobState;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.project.entity.ProjectRole;
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
 * Builds the actors, projects and rows a tier C test needs.
 *
 * <p>Authentication goes through a real personal access token rather than {@code @TestSecurity},
 * which yields a bare principal that {@code UserIdentityAugmenter} cannot map to a {@code User}.
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
    public Actor actor(String name) {
        // Emails are unique per user; the counter keeps two actors of the same name apart.
        var user = userService.register(name, name + COUNTER.incrementAndGet() + "@example.test");
        return new Actor(user.getId(), accessTokenService.issue(user.getId()).token());
    }

    public UUID project(String name) {
        return projectService.create(name).getId();
    }

    public void join(Actor actor, UUID projectId, ProjectRole role) {
        userService.grant(actor.id(), projectId, role);
    }

    /** Grants {@link Perm#ADMIN_OF_ALL}, which is global and so takes no project. */
    public void makeAdmin(Actor actor) {
        permissionService.grant(actor.id(), Perm.ADMIN_OF_ALL, null);
    }

    /** Grants one project-scoped permission, the way a sub-account holds its rights. */
    public void grant(Actor actor, Perm perm, UUID projectId) {
        permissionService.grant(actor.id(), perm, projectId);
    }

    /** Mints a sub-account of the project and issues it a token, so it can be an {@link Actor}. */
    public Actor subAccount(UUID projectId, String name, Actor createdBy) {
        var account = subAccountService.create(projectId, name, createdBy.id());
        return new Actor(account.getUserId(), accessTokenService.issue(account.getUserId()).token());
    }

    public void secret(UUID projectId, String name, String value) {
        secretService.create(projectId, name, null, value);
    }

    /** A resource class of the project, or a global one when {@code projectId} is null. */
    @Transactional
    public ResourceClass resourceClass(String name, @Nullable UUID projectId) {
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

    /** A template of the project, or a global one when {@code projectId} is null. */
    @Transactional
    public UUID template(String name, @Nullable UUID projectId, ResourceClass klass) {
        var template = JobSpecTemplate.builder()
                .name(name)
                .spec(spec("alpine"))
                .resourceClass(attach(klass))
                .project(projectId == null ? null : Project.<Project>findById(projectId))
                .build();
        template.persistAndFlush();
        return template.getId();
    }

    @Transactional
    public UUID job(UUID projectId, Actor requestedBy, ResourceClass klass, JobState state,
                    @Nullable UUID worker) {
        var job = Job.builder()
                .project(Project.<Project>findById(projectId))
                .requestedBy(requestedBy.id())
                .resourceClass(attach(klass))
                .spec(spec("alpine"))
                .worker(worker)
                .build();
        // Through transitionTo, so completed_at satisfies the job_completion_consistency check.
        job.transitionTo(state);
        job.persistAndFlush();
        return job.getId();
    }

    @Transactional
    public UUID artifact(UUID jobId, String name) {
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
    public void log(UUID jobId, String topic, String message) {
        JobLog.builder()
                .job(Job.<Job>findById(jobId))
                .topic(topic)
                .message(message)
                .error(false)
                .build()
                .persistAndFlush();
    }

    /** A queue entry that is due now and expires in an hour. */
    public UUID queued(UUID projectId, Actor requestedBy, UUID templateId, String resourceClass) {
        var now = Instant.now();
        return queued(projectId, requestedBy, templateId, resourceClass,
                now.plus(Duration.ofHours(1)), now);
    }

    /**
     * Built here rather than through {@code PendingJobService.enqueue}, which needs a
     * {@code UserContext} and so only exists inside a request.
     */
    @Transactional
    public UUID queued(UUID projectId, Actor requestedBy, UUID templateId, String resourceClass,
                       Instant expiresAt, Instant nextAttemptAt) {
        var pending = PendingJob.builder()
                .project(Project.<Project>findById(projectId))
                .requestedBy(requestedBy.id())
                // resource_class is not null on pending_job, whatever JobRequest allows elsewhere.
                .request(new JobRequest(templateId, null, resourceClass))
                .state(PendingJobState.QUEUED)
                .expiresAt(expiresAt)
                .nextAttemptAt(nextAttemptAt)
                .build();
        pending.persistAndFlush();
        return pending.getId();
    }

    /** A worker row, as a connecting worker would leave behind; nothing is registered as live. */
    @Transactional
    public UUID worker(String name) {
        var id = UUID.randomUUID();
        Worker.upsert(id, name);
        return id;
    }

    /** A volume the project holds on a worker. */
    @Transactional
    public UUID volume(UUID projectId, UUID workerId, String name) {
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

    /** A request carrying the actor's bearer token, which is the only way into an authenticated route. */
    public static RequestSpecification as(Actor actor) {
        return RestAssured.given().header("Authorization", "Bearer " + actor.token());
    }

    /**
     * Calls a finder in a transaction of its own, which is what Panache needs and a test method has not
     * got. Read what the assertion wants inside the body: what comes back out is detached.
     */
    public static <T> T inTx(Supplier<T> body) {
        return QuarkusTransaction.requiringNew().call(body::get);
    }

    /** {@link #inTx(Supplier)} for a write that yields nothing. */
    public static void inTx(Runnable body) {
        QuarkusTransaction.requiringNew().run(body);
    }

    public static JobSpec spec(String image) {
        return new JobSpec(image, null, null, null, null, 0, "", null);
    }

    /** Fixtures hand out detached rows; a builder needs one this persistence context owns. */
    private static ResourceClass attach(ResourceClass klass) {
        return ResourceClass.findById(klass.key());
    }

    /**
     * @param token the plaintext token, to be sent as {@code Authorization: Bearer <token>}
     */
    public record Actor(UUID id, String token) {
    }
}
