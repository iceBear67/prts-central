package io.ib67.prts.job;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.agent.job.JobSpecOverrideAuthorizer;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.entity.JobRequest;
import io.ib67.prts.job.entity.Project;
import io.ib67.prts.job.task.TaskScope;
import io.ib67.prts.job.task.TaskService;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.secret.SecretService;
import io.ib67.prts.testing.InlineTransactions;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobLauncherTest {

    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OTHER_PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID TEMPLATE = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-0000000000d6");
    private static final UUID REQUESTER = UUID.fromString("00000000-0000-0000-0000-0000000000d7");

    private final ProjectService projectService = mock(ProjectService.class);
    private final JobService jobService = mock(JobService.class);
    private final WorkerService workerService = mock(WorkerService.class);
    private final SecretService secretService = mock(SecretService.class);
    private final TaskService taskService = mock(TaskService.class);

    // Pass-through mock authorizer that returns arguments directly.
    private final JobSpecOverrideAuthorizer authorizer =
            mock(JobSpecOverrideAuthorizer.class, invocation -> invocation.getArgument(0));

    private final JobLauncher launcher = new JobLauncher();

    private final Project project = Project.builder().id(PROJECT).name("p").build();
    private final ResourceClass small = resourceClass("small", PROJECT);
    private final ResourceClass big = resourceClass("big", PROJECT);
    private final JobSpec spec = new JobSpec("img:1", null, null, null, null, null, 60L, null, null);
    private final JobSpecTemplate template =
            JobSpecTemplate.builder().id(TEMPLATE).name("t").spec(spec).resourceClass(small).build();

    private static ResourceClass resourceClass(String name, UUID projectId) {
        return ResourceClass.builder().name(name).projectId(projectId).build();
    }

    @BeforeEach
    void setUp() {
        launcher.projectService = projectService;
        launcher.jobService = jobService;
        launcher.workerService = workerService;
        launcher.secretService = secretService;
        launcher.taskService = taskService;
        when(projectService.findById(PROJECT)).thenReturn(Optional.of(project));
    }

    private static JobRequest request(String resourceClass) {
        return new JobRequest(TEMPLATE, null, resourceClass, null);
    }

    private static JobRequest inTask(String resourceClass) {
        return new JobRequest(TEMPLATE, null, resourceClass, TASK);
    }

    /** Registers an open task carrying the given scope, and the volumes it mounts. */
    private void openTask(TaskScope scope, Map<UUID, JobSpec.VolumeSpec> mounts) {
        var task = Task.builder().id(TASK).project(project).name("pr-42")
                .scope(scope).createdBy(REQUESTER).build();
        when(taskService.requireOpen(PROJECT, TASK)).thenReturn(task);
        when(taskService.mountsOf(TASK)).thenReturn(mounts);
    }

    /** Helper context mocking static entity methods and managing transactions for authorize() tests. */
    private final class Scope implements AutoCloseable {
        final InlineTransactions transactions = new InlineTransactions();
        final MockedStatic<JobSpecTemplate> templates = mockStatic(JobSpecTemplate.class);
        final MockedStatic<ResourceClass> classes = mockStatic(ResourceClass.class);

        Scope() {
            templates.when(() -> JobSpecTemplate.findVisibleFetched(PROJECT, TEMPLATE))
                    .thenReturn(Optional.of(template));
        }

        @Override
        public void close() {
            classes.close();
            templates.close();
            transactions.close();
        }
    }

    @Test
    void theTemplatesResourceClassIsPinnedOntoTheRequest() {
        try (var scope = new Scope()) {
            var authorized = launcher.authorize(PROJECT, request(null), authorizer);

            assertEquals("small", authorized.resourceClass());
            assertEquals(TEMPLATE, authorized.templateId());
            // The template's default resource class is not considered an override.
            verifyNoInteractions(authorizer);
            scope.classes.verifyNoInteractions();
        }
    }

    @Test
    void askingForTheTemplatesOwnClassIsNotAnOverride() {
        try (var scope = new Scope()) {
            var authorized = launcher.authorize(PROJECT, request("small"), authorizer);

            assertEquals("small", authorized.resourceClass());
            verifyNoInteractions(authorizer);
            scope.classes.verifyNoInteractions();
        }
    }

    @Test
    void overridingTheResourceClassIsGatedAndLookedUp() {
        try (var scope = new Scope()) {
            scope.classes.when(() -> ResourceClass.findVisible(PROJECT, "big")).thenReturn(Optional.of(big));

            var authorized = launcher.authorize(PROJECT, request("big"), authorizer);

            assertEquals("big", authorized.resourceClass());
            verify(authorizer).resourceClass("big");
        }
    }

    @Test
    void anOverrideResourceClassThatDoesNotExistIsNotFound() {
        try (var scope = new Scope()) {
            scope.classes.when(() -> ResourceClass.findVisible(PROJECT, "huge")).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> launcher.authorize(PROJECT, request("huge"), authorizer));
        }
    }

    @Test
    void anUnknownProjectIsNotFound() {
        when(projectService.findById(PROJECT)).thenReturn(Optional.empty());

        try (var ignored = new Scope()) {
            assertThrows(NotFoundException.class,
                    () -> launcher.authorize(PROJECT, request(null), authorizer));
        }
    }

    @Test
    void anUnknownTemplateIsNotFound() {
        try (var scope = new Scope()) {
            scope.templates.when(() -> JobSpecTemplate.findVisibleFetched(PROJECT, TEMPLATE))
                    .thenReturn(Optional.empty());

            assertThrows(NotFoundException.class,
                    () -> launcher.authorize(PROJECT, request(null), authorizer));
        }
    }

    @Test
    void aTemplateWithoutASpecIsRejected() {
        var specless = JobSpecTemplate.builder().id(TEMPLATE).name("t").resourceClass(small).build();

        try (var scope = new Scope()) {
            scope.templates.when(() -> JobSpecTemplate.findVisibleFetched(PROJECT, TEMPLATE))
                    .thenReturn(Optional.of(specless));

            assertThrows(BadRequestException.class,
                    () -> launcher.authorize(PROJECT, request(null), authorizer));
        }
    }

    @Test
    void aTemplateWithNoResourceClassAndNoRequestedOneIsRejected() {
        var classless = JobSpecTemplate.builder().id(TEMPLATE).name("t").spec(spec).build();

        try (var scope = new Scope()) {
            scope.templates.when(() -> JobSpecTemplate.findVisibleFetched(PROJECT, TEMPLATE))
                    .thenReturn(Optional.of(classless));

            assertThrows(BadRequestException.class,
                    () -> launcher.authorize(PROJECT, request(null), authorizer));
        }
    }

    /** Resource classes referenced by a template must belong to the template's project or be global. */
    @Test
    void aTemplateNamingAnotherProjectsResourceClassIsRejected() {
        var foreign = JobSpecTemplate.builder().id(TEMPLATE).name("t").spec(spec)
                .resourceClass(resourceClass("small", OTHER_PROJECT)).build();

        try (var scope = new Scope()) {
            scope.templates.when(() -> JobSpecTemplate.findVisibleFetched(PROJECT, TEMPLATE))
                    .thenReturn(Optional.of(foreign));

            assertThrows(BadRequestException.class,
                    () -> launcher.authorize(PROJECT, request(null), authorizer));
        }
    }

    @Test
    void aGlobalResourceClassIsVisibleToEveryProject() {
        var global = JobSpecTemplate.builder().id(TEMPLATE).name("t").spec(spec)
                .resourceClass(resourceClass("shared", ResourceClass.GLOBAL)).build();

        try (var scope = new Scope()) {
            scope.templates.when(() -> JobSpecTemplate.findVisibleFetched(PROJECT, TEMPLATE))
                    .thenReturn(Optional.of(global));

            assertEquals("shared", launcher.authorize(PROJECT, request(null), authorizer).resourceClass());
        }
    }

    /** Volumes introduced via override must belong to the project. */
    @Test
    void aVolumeAddedByAnOverrideIsStillCheckedAgainstTheProject() {
        var override = new JobSpecOverride(null, null, null, null, null,
                Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)), null, null);
        var borrowed = new WorkerVolume();
        borrowed.setId(VOLUME);
        borrowed.setProject(Project.builder().id(OTHER_PROJECT).name("other").build());

        try (var ignored = new Scope(); var volumes = mockStatic(WorkerVolume.class)) {
            volumes.when(() -> WorkerVolume.listByIds(any())).thenReturn(List.of(borrowed));

            assertThrows(ForbiddenException.class, () -> launcher
                    .authorize(PROJECT, new JobRequest(TEMPLATE, override, null, null), authorizer));
        }
    }

    /** authorize() performs validation only and does not persist entities or schedule jobs. */
    @Test
    void authorizingNeitherPersistsNorSchedules() {
        try (var ignored = new Scope()) {
            launcher.authorize(PROJECT, request(null), authorizer);
        }

        verifyNoInteractions(workerService, jobService, secretService);
    }

    /**
     * A task's values were authorized when the task was written, so they must not be routed through the
     * override path — that gates every supplied field against the caller's own {@code job:spec:*}.
     */
    @Test
    void aTasksContributionIsNotGatedAgainstTheCaller() {
        openTask(new TaskScope(Map.of("SHARED", "1"), Map.of("tier", "task"), null), Map.of());

        try (var ignored = new Scope()) {
            launcher.authorize(PROJECT, inTask(null), authorizer);
        }

        verifyNoInteractions(authorizer);
    }

    /** A task's class stands in for the template's, and costs no job:resource-class either. */
    @Test
    void aTasksResourceClassReplacesTheTemplates() {
        openTask(new TaskScope(Map.of(), Map.of(), "big"), Map.of());

        try (var scope = new Scope()) {
            scope.classes.when(() -> ResourceClass.findVisible(PROJECT, "big")).thenReturn(Optional.of(big));

            assertEquals("big", launcher.authorize(PROJECT, inTask(null), authorizer).resourceClass());
            verifyNoInteractions(authorizer);
        }
    }

    /** Asking for something other than the task's default is still an override. */
    @Test
    void namingAnotherClassUnderATaskIsStillGated() {
        openTask(new TaskScope(Map.of(), Map.of(), "big"), Map.of());

        try (var scope = new Scope()) {
            scope.classes.when(() -> ResourceClass.findVisible(PROJECT, "small")).thenReturn(Optional.of(small));

            assertEquals("small", launcher.authorize(PROJECT, inTask("small"), authorizer).resourceClass());
            verify(authorizer).resourceClass("small");
        }
    }

    /** The task's mounts join the spec and are validated like any other volume. */
    @Test
    void aTasksVolumesAreBoundAndChecked() {
        openTask(TaskScope.EMPTY, Map.of(VOLUME, new JobSpec.VolumeSpec("/shared", 4096L)));
        // Built before the static mock: @Builder.Default routes the state initializer through a static
        // $default$state(), so even `new WorkerVolume()` would interact with it.
        var mounted = new WorkerVolume();
        mounted.setId(VOLUME);
        mounted.setProject(project);
        mounted.setWorker(Worker.builder().id(WORKER).name("w").build());
        mounted.setState(VolumeState.READY);

        try (var ignored = new Scope(); var volumes = mockStatic(WorkerVolume.class)) {
            volumes.when(() -> WorkerVolume.listByIds(any())).thenReturn(List.of(mounted));

            launcher.authorize(PROJECT, inTask(null), authorizer);

            volumes.verify(() -> WorkerVolume.listByIds(Set.of(VOLUME)));
        }
    }

    @Test
    void aClosedTaskRefusesTheJob() {
        when(taskService.requireOpen(PROJECT, TASK))
                .thenThrow(new ClientErrorException("task is CLOSED", Response.Status.CONFLICT));

        try (var ignored = new Scope()) {
            assertThrows(ClientErrorException.class,
                    () -> launcher.authorize(PROJECT, inTask(null), authorizer));
        }
    }
}
