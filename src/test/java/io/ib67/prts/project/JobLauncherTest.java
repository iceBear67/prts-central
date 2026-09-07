package io.ib67.prts.project;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.agent.job.JobSpecOverrideAuthorizer;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.project.entity.JobRequest;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.secret.SecretService;
import io.ib67.prts.testing.InlineTransactions;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobLauncherTest {

    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OTHER_PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID TEMPLATE = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

    private final ProjectService projectService = mock(ProjectService.class);
    private final JobService jobService = mock(JobService.class);
    private final WorkerService workerService = mock(WorkerService.class);
    private final SecretService secretService = mock(SecretService.class);

    // Pass-through: every gated value is accepted, so a verify() shows what was submitted for gating.
    private final JobSpecOverrideAuthorizer authorizer =
            mock(JobSpecOverrideAuthorizer.class, invocation -> invocation.getArgument(0));

    private final JobLauncher launcher = new JobLauncher();

    // Built here, not inside a test: ResourceClass.builder() is itself a static that mockStatic would stub out.
    private final Project project = Project.builder().id(PROJECT).name("p").build();
    private final ResourceClass small = resourceClass("small", PROJECT);
    private final ResourceClass big = resourceClass("big", PROJECT);
    private final JobSpec spec = new JobSpec("img:1", null, null, null, null, 60L, null, null);
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
        when(projectService.findById(PROJECT)).thenReturn(Optional.of(project));
    }

    private static JobRequest request(String resourceClass) {
        return new JobRequest(TEMPLATE, null, resourceClass);
    }

    /** Opens the statics {@code resolve()} reaches, with the happy-path template already stubbed. */
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
            // The template's own class is not an override, so it is neither gated nor looked up.
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

    /** A template may only be scoped to its own project or to the global scope. */
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

    /** Volumes are checked against the merged spec, so an override cannot smuggle one past the check. */
    @Test
    void aVolumeAddedByAnOverrideIsStillCheckedAgainstTheProject() {
        var override = new JobSpecOverride(null, null, null, null,
                Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)), null, null);
        var borrowed = new WorkerVolume();
        borrowed.setId(VOLUME);
        borrowed.setProject(Project.builder().id(OTHER_PROJECT).name("other").build());

        try (var ignored = new Scope(); var volumes = mockStatic(WorkerVolume.class)) {
            volumes.when(() -> WorkerVolume.listByIds(any())).thenReturn(List.of(borrowed));

            assertThrows(ForbiddenException.class, () -> launcher
                    .authorize(PROJECT, new JobRequest(TEMPLATE, override, null), authorizer));
        }
    }

    /** authorize() only validates: nothing may be persisted or dispatched. */
    @Test
    void authorizingNeitherPersistsNorSchedules() {
        try (var ignored = new Scope()) {
            launcher.authorize(PROJECT, request(null), authorizer);
        }

        verifyNoInteractions(workerService, jobService, secretService);
    }
}
