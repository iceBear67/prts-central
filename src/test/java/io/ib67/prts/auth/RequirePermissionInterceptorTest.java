package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequirePermissionInterceptorTest {

    private static final Object PROCEEDED = new Object();
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final UserContext userContext = mock(UserContext.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final UserService userService = mock(UserService.class);
    private final UriInfo uriInfo = mock(UriInfo.class);
    private final MultivaluedMap<String, String> pathParameters = new MultivaluedHashMap<>();

    private final RequirePermissionInterceptor interceptor = new RequirePermissionInterceptor();

    @BeforeEach
    void setUp() {
        interceptor.userContext = userContext;
        interceptor.permissionService = permissionService;
        interceptor.userService = userService;
        interceptor.uriInfo = uriInfo;

        var user = new User();
        user.setId(USER);
        when(userContext.get()).thenReturn(user);
        when(uriInfo.getPathParameters()).thenReturn(pathParameters);
    }

    private static InvocationContext invocationOf(String methodName, Object... arguments) throws Exception {
        var method = Arrays.stream(Target.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such method on Target: " + methodName));
        var context = mock(InvocationContext.class);
        when(context.getMethod()).thenReturn(method);
        when(context.getParameters()).thenReturn(arguments);
        when(context.proceed()).thenReturn(PROCEEDED);
        return context;
    }

    @Test
    void aMethodWithoutTheBindingProceeds() throws Exception {
        var context = invocationOf("unannotated");

        assertSame(PROCEEDED, interceptor.check(context));
        verifyNoInteractions(permissionService, userService);
    }

    @Test
    void anAnonymousCallerIsUnauthorized() throws Exception {
        when(userContext.get()).thenReturn(null);
        var context = invocationOf("projectScoped", PROJECT);

        assertThrows(UnauthorizedException.class, () -> interceptor.check(context));
        verify(context, never()).proceed();
    }

    @Test
    void anAdminSkipsTheRestOfTheCheck() throws Exception {
        when(permissionService.isAdmin(USER)).thenReturn(true);
        var context = invocationOf("projectScoped", PROJECT);

        assertSame(PROCEEDED, interceptor.check(context));
        verify(permissionService, never()).has(any(), any(), any());
        verifyNoInteractions(userService);
    }

    @Test
    void allowAdminFalseIgnoresAdminStatus() throws Exception {
        when(permissionService.isAdmin(USER)).thenReturn(true);
        var context = invocationOf("adminNotAllowed", PROJECT);

        assertThrows(ForbiddenException.class, () -> interceptor.check(context));
    }

    @Test
    void anExplicitGrantProceeds() throws Exception {
        when(permissionService.has(USER, Perm.JOB_CREATE, PROJECT)).thenReturn(true);
        var context = invocationOf("projectScoped", PROJECT);

        assertSame(PROCEEDED, interceptor.check(context));
    }

    @Test
    void aSufficientProjectRoleProceeds() throws Exception {
        when(userService.hasAtLeast(USER, PROJECT, ProjectRole.MEMBER)).thenReturn(true);
        var context = invocationOf("withDefaultRole", PROJECT);

        assertSame(PROCEEDED, interceptor.check(context));
    }

    @Test
    void anInsufficientProjectRoleIsForbidden() throws Exception {
        var context = invocationOf("withDefaultRole", PROJECT);

        assertThrows(ForbiddenException.class, () -> interceptor.check(context));
    }

    @Test
    void defaultValueGrantsWhenNothingElseMatches() throws Exception {
        var context = invocationOf("defaultAllowed", PROJECT);

        assertSame(PROCEEDED, interceptor.check(context));
    }

    @Test
    void aDeniedCallerIsForbidden() throws Exception {
        var context = invocationOf("projectScoped", PROJECT);

        assertThrows(ForbiddenException.class, () -> interceptor.check(context));
    }

    /** Verifies that a globally banned permission denies access even when explicitly granted. */
    @Test
    void aBannedPermissionIsForbiddenDespiteTheGrant() throws Exception {
        when(permissionService.isBanned(Perm.JOB_CREATE)).thenReturn(true);
        when(permissionService.has(USER, Perm.JOB_CREATE, PROJECT)).thenReturn(true);
        var context = invocationOf("projectScoped", PROJECT);

        var thrown = assertThrows(ForbiddenException.class, () -> interceptor.check(context));
        assertEquals("permission is globally disabled: " + Perm.JOB_CREATE.permission(),
                thrown.getMessage());
    }

    /** Verifies that a globally banned permission denies access to administrators. */
    @Test
    void aBannedPermissionStopsAnAdminToo() throws Exception {
        when(permissionService.isBanned(Perm.JOB_CREATE)).thenReturn(true);
        when(permissionService.isAdmin(USER)).thenReturn(true);
        var context = invocationOf("projectScoped", PROJECT);

        assertThrows(ForbiddenException.class, () -> interceptor.check(context));
    }

    /** Verifies that a globally banned permission denies access even if the user has an authorized role. */
    @Test
    void aBannedPermissionOutranksTheStandingInRole() throws Exception {
        when(permissionService.isBanned(Perm.JOB_CREATE)).thenReturn(true);
        when(userService.hasAtLeast(USER, PROJECT, ProjectRole.MEMBER)).thenReturn(true);
        var context = invocationOf("withDefaultRole", PROJECT);

        assertThrows(ForbiddenException.class, () -> interceptor.check(context));
    }

    /** Verifies that endpoints allowing default access remain accessible when unrelated permissions are banned. */
    @Test
    void aBannedPermissionLeavesAnEndpointOpenToEveryoneOpen() throws Exception {
        when(permissionService.isBanned(Perm.JOB_CREATE)).thenReturn(true);
        var context = invocationOf("defaultAllowed", PROJECT);

        assertSame(PROCEEDED, interceptor.check(context));
    }

    /** Project-scoped permissions without a target project throw IllegalStateException. */
    @Test
    void aProjectScopedPermissionWithoutAProjectFailsLoudly() throws Exception {
        var context = invocationOf("unscoped");

        assertThrows(IllegalStateException.class, () -> interceptor.check(context));
    }

    @Test
    void aGlobalPermissionNeedsNoProject() throws Exception {
        when(permissionService.has(USER, Perm.ADMIN_OF_ALL, null)).thenReturn(true);
        var context = invocationOf("globalScoped");

        assertSame(PROCEEDED, interceptor.check(context));
    }

    @Test
    void theProjectFallsBackToTheRequestPath() throws Exception {
        pathParameters.putSingle(RequirePermissionInterceptor.PROJECT_PATH_PARAM, PROJECT.toString());
        when(permissionService.has(USER, Perm.JOB_CREATE, PROJECT)).thenReturn(true);
        var context = invocationOf("unscoped");

        assertSame(PROCEEDED, interceptor.check(context));
    }

    @Test
    void anAnnotatedArgumentOutranksTheRequestPath() throws Exception {
        pathParameters.putSingle(RequirePermissionInterceptor.PROJECT_PATH_PARAM, OTHER_PROJECT.toString());
        when(permissionService.has(USER, Perm.JOB_CREATE, PROJECT)).thenReturn(true);
        var context = invocationOf("projectScoped", PROJECT);

        assertSame(PROCEEDED, interceptor.check(context));
    }

    @Test
    void aMalformedProjectInThePathIsNotFound() throws Exception {
        pathParameters.putSingle(RequirePermissionInterceptor.PROJECT_PATH_PARAM, "not-a-uuid");
        var context = invocationOf("unscoped");

        assertThrows(NotFoundException.class, () -> interceptor.check(context));
    }

    @SuppressWarnings("unused")
    static class Target {

        void unannotated() {
        }

        @RequirePermission(Perm.JOB_CREATE)
        void projectScoped(@ProjectId UUID projectId) {
        }

        @RequirePermission(Perm.JOB_CREATE)
        void unscoped() {
        }

        @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
        void withDefaultRole(@ProjectId UUID projectId) {
        }

        @RequirePermission(value = Perm.JOB_CREATE, defaultValue = true)
        void defaultAllowed(@ProjectId UUID projectId) {
        }

        @RequirePermission(value = Perm.JOB_CREATE, allowAdmin = false)
        void adminNotAllowed(@ProjectId UUID projectId) {
        }

        @RequirePermission(Perm.ADMIN_OF_ALL)
        void globalScoped() {
        }
    }
}
