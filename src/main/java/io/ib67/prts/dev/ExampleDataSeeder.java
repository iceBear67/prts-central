package io.ib67.prts.dev;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.auth.DevAdminSeeder;
import io.ib67.prts.job.JobLauncher;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobLog;
import io.ib67.prts.job.entity.JobRequest;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.Project;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.job.task.TaskScope;
import io.ib67.prts.job.task.TaskService;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskState;
import io.ib67.prts.job.task.entity.TaskVolume;
import io.ib67.prts.notification.entity.Notification;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.secret.SecretService;
import io.ib67.prts.storage.StorageConfig;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.SubAccountService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fills an empty dev database with example projects, workers and job history, so the API has
 * something to serve before anything real has run.
 *
 * <p>Only ever writes into a database holding no project, which is what keeps a live reload — and a
 * reused Dev Services container — from stacking a second copy on top of the developer's own data.
 */
@ApplicationScoped
@IfBuildProfile("dev")
@IfBuildProperty(name = "dev.seed-examples", stringValue = "true", enableIfMissing = true)
public class ExampleDataSeeder {
    private static final Logger LOG = Logger.getLogger(ExampleDataSeeder.class);

    private static final String SENDER = "prts";

    @Inject
    EntityManager entityManager;
    @Inject
    UserService userService;
    @Inject
    ProjectService projectService;
    @Inject
    PermissionService permissionService;
    @Inject
    SubAccountService subAccountService;
    @Inject
    SecretService secretService;
    @Inject
    TaskService taskService;
    @Inject
    StorageConfig storageConfig;
    @Inject
    S3Client s3;

    void seed(@Observes StartupEvent event) {
        try {
            if (QuarkusTransaction.requiringNew().call(Project::count) > 0) {
                return;
            }
            QuarkusTransaction.requiringNew().run(this::populate);
            uploadArtifacts();
            LOG.infof("seeded example data: %s", inventory());
        } catch (RuntimeException e) {
            // Example data is a convenience; never let it be the reason dev mode does not come up.
            LOG.error("could not seed example data", e);
        }
    }

    private void populate() {
        var now = Instant.now();
        // The two dev seeders observe the same event and neither is ordered before the other, so
        // whichever runs first registers the dev user and the other finds it.
        var dev = userService.findByEmail(DevAdminSeeder.EMAIL)
                .orElseGet(() -> userService.register(DevAdminSeeder.NAME, DevAdminSeeder.EMAIL));
        var alice = userService.register("alice", "alice@example.com");
        var bob = userService.register("bob", "bob@example.com");
        permissionService.grant(alice.getId(), Perm.PROJECT_CREATE, null);

        var small = resourceClass("small", 1, 1024, 8);
        var cast = new Cast(
                dev, alice, bob,
                small,
                resourceClass("standard", 4, 8192, 64),
                resourceClass("large", 16, 65536, 512),
                worker("worker-alpha", false),
                worker("worker-beta", true),
                template("shell", null, small, new JobSpec(
                        "alpine:3.20",
                        "Run a shell one-liner",
                        Map.of(),
                        Map.of(),
                        List.of("sh", "-lc", "echo hello"),
                        Map.of(), 300, "", Map.of())),
                now);

        aurora(cast);
        sandbox(cast);
        retired(cast);
        notification(dev, "Welcome to prts",
                "This database was filled with example data for local development. "
                        + "Delete a project and restart to see the empty state.",
                true, now.minus(Duration.ofDays(3)));
    }

    /** The project with everything in it: members, secrets, volumes, tasks and a job history. */
    private void aurora(Cast cast) {
        var project = projectService.create("Aurora Pipeline",
                "Nightly builds, integration runs and release candidates for the Aurora service.",
                cast.dev().getId());
        userService.grant(cast.alice().getId(), project.getId(), ProjectRole.MEMBER);
        userService.grant(cast.bob().getId(), project.getId(), ProjectRole.VIEWER);
        permissionService.grant(cast.alice().getId(), Perm.JOB_SPEC_ENVIRONMENT, project.getId());
        permissionService.grant(cast.bob().getId(), Perm.JOB_LOG_READ, project.getId());

        secretService.create(project.getId(), "REGISTRY_TOKEN",
                "Pushes release images to the registry", "example-registry-token");
        secretService.create(project.getId(), "SLACK_WEBHOOK",
                "Where build failures are announced", "https://hooks.example.com/T000/B000");

        var ci = subAccountService.create(project.getId(), "ci-bot", cast.dev().getId());
        subAccountService.setPermissions(project.getId(), ci.getUserId(),
                List.of(Perm.JOB_CREATE, Perm.JOB_READ, Perm.JOB_LOG_READ, Perm.JOB_ARTIFACT_READ));

        var cache = volume(project, cast.alpha(), "gradle-cache",
                32L << 30, 19L << 30, VolumeState.READY);
        volume(project, cast.alpha(), "release-staging", 8L << 30, 0, VolumeState.PROVISIONING);

        var build = template("build", project, cast.standard(), new JobSpec(
                "ghcr.io/example/aurora-builder:1.4",
                "Build the Aurora service and publish its image",
                Map.of("GRADLE_OPTS", "-Xmx4g"),
                Map.of("prts.pipeline", "build"),
                List.of("sh", "-lc", "./gradlew --no-daemon build"),
                Map.of(), 1800, "aurora-build", Map.of()));
        var suite = template("integration-test", project, cast.large(), new JobSpec(
                "ghcr.io/example/aurora-it:1.4",
                "Run the integration suite against staging",
                Map.of("AURORA_ENV", "staging"),
                Map.of("prts.pipeline", "integration"),
                List.of("sh", "-lc", "./gradlew --no-daemon integrationTest"),
                Map.of(), 3600, "", Map.of()));

        var release = task(project, cast.dev(), "Release 1.4.0",
                "Cut the 1.4.0 release candidate and run the full suite before tagging.",
                "https://github.com/example/aurora/issues/482",
                new TaskScope(
                        Map.of("AURORA_RELEASE", "1.4.0"),
                        Map.of("prts.release", "1.4.0"),
                        cast.standard().getName()),
                cast.now().minus(Duration.ofDays(2)));
        TaskVolume.of(release, cache, "/home/build/.gradle").persistAndFlush();
        // Built the way JobLauncher builds it, so what the examples show is what a real job carries.
        var releaseSpec = TaskScope.bindTo(
                release.getScope().defaultsTo(build.getSpec()),
                release.getId(),
                taskService.mountsOf(release.getId()));

        var packaged = ran(Job.builder()
                        .project(project)
                        .requestedBy(cast.dev().getId())
                        .resourceClass(cast.standard())
                        .templateId(build.getId())
                        .taskId(release.getId())
                        .worker(cast.alpha().getId())
                        .spec(releaseSpec)
                        .build(),
                JobState.SUCCESS, cast.now().minus(Duration.ofHours(26)), Duration.ofMinutes(7));
        logs(packaged, cast.now().minus(Duration.ofHours(26)), "build",
                "+ ./gradlew --no-daemon build",
                "> Task :compileJava",
                "> Task :test — 214 tests, 0 failures",
                "> Task :bootJar",
                "BUILD SUCCESSFUL in 6m 48s");
        artifact(packaged, "build.log");
        artifact(packaged, "junit-report.xml");

        var failedAt = cast.now().minus(Duration.ofHours(5));
        var failed = ran(Job.builder()
                        .project(project)
                        .requestedBy(cast.dev().getId())
                        .resourceClass(cast.large())
                        .templateId(suite.getId())
                        .taskId(release.getId())
                        .worker(cast.alpha().getId())
                        .spec(TaskScope.bindTo(
                                release.getScope().defaultsTo(suite.getSpec()),
                                release.getId(),
                                taskService.mountsOf(release.getId())))
                        .build(),
                JobState.FAILED, failedAt, Duration.ofMinutes(12));
        logs(failed, failedAt, "integration",
                "+ ./gradlew --no-daemon integrationTest",
                "> Task :integrationTest",
                "LoginFlowIT > staleSessionIsRejected FAILED");
        log(failed, failedAt.plusSeconds(80), "integration",
                "expected status 401 but was 500 — see junit-report.xml", true);
        artifact(failed, "junit-report.xml");
        notification(cast.dev(), "Job failed in " + project.getName(),
                "Job " + failed.getId() + " (" + failed.getSpec().description() + ") failed.",
                false, failedAt.plus(Duration.ofMinutes(12)));

        // A caller's override, gated when the job was created and replayed on a re-run.
        var override = new JobSpecOverride(null, "Nightly build with debug logging",
                Map.of("AURORA_DEBUG", "1"), null, null, null, null, null);
        var runningAt = cast.now().minus(Duration.ofMinutes(4));
        var running = ran(Job.builder()
                        .project(project)
                        .requestedBy(ci.getUserId())
                        .resourceClass(cast.standard())
                        .templateId(build.getId())
                        .worker(cast.alpha().getId())
                        .createOverride(override)
                        .spec(override.applyTo(build.getSpec(), JobLauncher.PRE_AUTHORIZED))
                        .build(),
                JobState.RUNNING, runningAt, null);
        logs(running, runningAt, "build",
                "+ ./gradlew --no-daemon build",
                "> Task :compileJava");

        var cancelledAt = cast.now().minus(Duration.ofDays(2));
        var cancelled = ran(Job.builder()
                        .project(project)
                        .requestedBy(cast.alice().getId())
                        .resourceClass(cast.large())
                        .templateId(suite.getId())
                        .worker(cast.alpha().getId())
                        .spec(suite.getSpec())
                        .build(),
                JobState.CANCELLED, cancelledAt, Duration.ofMinutes(1));
        logs(cancelled, cancelledAt, "state", "RUNNING -> CANCELLED");
        log(cancelled, cancelledAt.plusSeconds(20), "cancel",
                "worker " + cast.alpha().getId() + " told to stop the job", false);

        var flaky = task(project, cast.alice(), "Chase the flaky login test",
                "Reproduce the intermittent 500 in the login flow and pin it down.",
                "https://github.com/example/aurora/pull/477",
                TaskScope.EMPTY,
                cast.now().minus(Duration.ofDays(5)));
        closed(flaky, cast.now().minus(Duration.ofDays(2)));
        var reproducedAt = cast.now().minus(Duration.ofDays(3));
        var reproduced = ran(Job.builder()
                        .project(project)
                        .requestedBy(cast.alice().getId())
                        .resourceClass(cast.large())
                        .templateId(suite.getId())
                        .taskId(flaky.getId())
                        .worker(cast.alpha().getId())
                        .spec(TaskScope.bindTo(suite.getSpec(), flaky.getId(), Map.of()))
                        .build(),
                JobState.SUCCESS, reproducedAt, Duration.ofMinutes(4));
        logs(reproduced, reproducedAt, "integration", "LoginFlowIT > staleSessionIsRejected PASSED");

        // Nothing is connected, so the dispatcher leaves this one alone until a worker registers.
        PendingJob.builder()
                .project(project)
                .requestedBy(cast.dev().getId())
                .request(new JobRequest(build.getId(), null, cast.standard().getName(), release.getId()))
                .state(PendingJobState.QUEUED)
                .expiresAt(cast.now().plus(Duration.ofHours(1)))
                .nextAttemptAt(cast.now())
                .build()
                .persist();
        PendingJob.builder()
                .project(project)
                .requestedBy(cast.alice().getId())
                .request(new JobRequest(suite.getId(), null, cast.large().getName(), null))
                .state(PendingJobState.EXPIRED)
                .attempts(14)
                .lastError("no worker could take the job yet")
                .expiresAt(cast.now().minus(Duration.ofHours(3)))
                .nextAttemptAt(cast.now().minus(Duration.ofHours(3)))
                .build()
                .persist();
    }

    /** A second project the dev user only takes part in, to tell roles apart in the UI. */
    private void sandbox(Cast cast) {
        var project = projectService.create("Sandbox",
                "Scratch space for trying a spec out before it goes into a pipeline.",
                cast.alice().getId());
        userService.grant(cast.dev().getId(), project.getId(), ProjectRole.MEMBER);

        var hello = template("hello-world", project, cast.small(), new JobSpec(
                "alpine:3.20",
                "Print a line and exit",
                Map.of(),
                Map.of(),
                List.of("sh", "-lc", "echo hello from prts"),
                Map.of(), 60, "", Map.of()));
        var at = cast.now().minus(Duration.ofHours(9));
        var job = ran(Job.builder()
                        .project(project)
                        .requestedBy(cast.alice().getId())
                        .resourceClass(cast.small())
                        .templateId(hello.getId())
                        .worker(cast.alpha().getId())
                        .spec(hello.getSpec())
                        .build(),
                JobState.SUCCESS, at, Duration.ofSeconds(9));
        logs(job, at, "run", "hello from prts");
    }

    /** An archived project: readable, and refusing every write with 409. */
    private void retired(Cast cast) {
        var project = projectService.create("Legacy Migration",
                "Moved the last batch off the old runner. Kept for its logs.",
                cast.dev().getId());
        project.setArchivedAt(cast.now().minus(Duration.ofDays(9)));

        var succeededAt = cast.now().minus(Duration.ofDays(12));
        var succeeded = ran(Job.builder()
                        .project(project)
                        .requestedBy(cast.dev().getId())
                        .resourceClass(cast.small())
                        .templateId(cast.shell().getId())
                        .worker(cast.beta().getId())
                        .spec(cast.shell().getSpec())
                        .build(),
                JobState.SUCCESS, succeededAt, Duration.ofSeconds(31));
        logs(succeeded, succeededAt, "run", "migrated 4182 rows");

        var brokeAt = cast.now().minus(Duration.ofDays(13));
        var broke = ran(Job.builder()
                        .project(project)
                        .requestedBy(cast.dev().getId())
                        .resourceClass(cast.small())
                        .templateId(cast.shell().getId())
                        .worker(cast.beta().getId())
                        .spec(cast.shell().getSpec())
                        .build(),
                JobState.FAILED, brokeAt, Duration.ofSeconds(4));
        log(broke, brokeAt, "run", "connection refused: legacy-db:5432", true);
    }

    private ResourceClass resourceClass(String name, int cpus, int memory, int disk) {
        var klass = ResourceClass.builder()
                .name(name)
                .numCpus(cpus)
                .memCount(memory)
                .diskSize(disk)
                .build();
        klass.persistAndFlush();
        return klass;
    }

    private Worker worker(String name, boolean disabled) {
        var worker = Worker.builder().id(UUID.randomUUID()).name(name).disabled(disabled).build();
        worker.persistAndFlush();
        return worker;
    }

    private WorkerVolume volume(
            Project project, Worker worker, String name, long length, long used, VolumeState state) {
        var volume = WorkerVolume.builder()
                .project(project)
                .worker(worker)
                .name(name)
                .length(length)
                .used(used)
                .state(state)
                .build();
        volume.persistAndFlush();
        return volume;
    }

    /** Creates a template; a null project makes it global. */
    private JobSpecTemplate template(
            String name, @Nullable Project project, ResourceClass klass, JobSpec spec) {
        var template = JobSpecTemplate.builder()
                .name(name)
                .project(project)
                .resourceClass(klass)
                .spec(spec)
                .build();
        template.persistAndFlush();
        return template;
    }

    private Task task(
            Project project,
            User createdBy,
            String name,
            String description,
            String trackedAt,
            TaskScope scope,
            Instant at) {
        var task = Task.builder()
                .project(project)
                .name(name)
                .description(description)
                .trackedAt(trackedAt)
                .scope(scope)
                .createdBy(createdBy.getId())
                .build();
        task.persistAndFlush();
        backdate("task", task.getId(), at);
        return task;
    }

    private void closed(Task task, Instant at) {
        task.transitionTo(TaskState.CLOSED);
        task.setClosedAt(at);
    }

    /** Persists a job as though it had been created at {@code at} and had run for {@code took}. */
    private Job ran(Job job, JobState state, Instant at, @Nullable Duration took) {
        job.transitionTo(state);
        if (job.isCompleted()) {
            job.setCompletedAt(at.plus(Objects.requireNonNull(took, "took")));
        }
        job.persistAndFlush();
        backdate("job", job.getId(), at);
        return job;
    }

    /** Writes one line every 20 seconds from {@code from}. */
    private void logs(Job job, Instant from, String topic, String... messages) {
        var at = from;
        for (var message : messages) {
            log(job, at, topic, message, false);
            at = at.plusSeconds(20);
        }
    }

    private void log(Job job, Instant at, String topic, String message, boolean error) {
        var entry = JobLog.builder()
                .job(job)
                .topic(topic)
                .message(message)
                .error(error)
                .build();
        entry.persistAndFlush();
        backdate("job_log", entry.getId(), at);
    }

    private void artifact(Job job, String name) {
        Artifact.builder()
                .job(job)
                .name(name)
                // Same layout as ArtifactService.destKey, so the object sits where a real upload would.
                .objectKey("jobs/" + job.getId() + "/" + UUID.randomUUID() + "/" + name)
                .sizeBytes(body(name).getBytes(StandardCharsets.UTF_8).length)
                .build()
                .persist();
    }

    private void notification(User recipient, String title, String content, boolean read, Instant at) {
        var notification = Notification.builder()
                .recipient(recipient)
                .sender(SENDER)
                .title(title)
                .content(content)
                .read(read)
                .build();
        notification.persistAndFlush();
        backdate("notification", notification.getId(), at);
    }

    /**
     * Rewrites a row's {@code created_at}.
     *
     * <p>The column is {@code @CreationTimestamp} and {@code updatable = false}, so a history stretching
     * back further than this startup can only be written straight to it.
     */
    private void backdate(String table, Object id, Instant at) {
        entityManager.createNativeQuery("update " + table + " set created_at = :at where id = :id")
                .setParameter("at", at.atOffset(ZoneOffset.UTC))
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Puts an object behind every example artifact, so its download link resolves to something. */
    private void uploadArtifacts() {
        var objects = QuarkusTransaction.requiringNew().call(() -> Artifact.<Artifact>listAll().stream()
                .collect(Collectors.toMap(Artifact::getObjectKey, Artifact::getName)));
        try {
            objects.forEach((key, name) -> s3.putObject(
                    request -> request.bucket(storageConfig.bucket()).key(key),
                    RequestBody.fromString(body(name), StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            LOG.warnf("the example artifacts have no objects behind them: %s", e.toString());
        }
    }

    private static String body(String name) {
        return "prts dev example artifact: " + name + "\n";
    }

    private String inventory() {
        return QuarkusTransaction.requiringNew().call(() -> Project.<Project>listAll().stream()
                .map(project -> project.getName() + " (" + project.getId() + ")")
                .collect(Collectors.joining(", ")));
    }

    /** What the example projects are built out of: the people, the classes and the workers. */
    private record Cast(
            User dev,
            User alice,
            User bob,
            ResourceClass small,
            ResourceClass standard,
            ResourceClass large,
            Worker alpha,
            Worker beta,
            JobSpecTemplate shell,
            Instant now
    ) {
    }
}
