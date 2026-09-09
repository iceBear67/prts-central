package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.Nullable;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.UriInfo;

import java.util.UUID;

@Interceptor
@RequirePermission(Perm.ADMIN_OF_ALL)
@Priority(Interceptor.Priority.APPLICATION)
public class RequirePermissionInterceptor {

    /** Path parameter name used to extract the target project ID. */
    public static final String PROJECT_PATH_PARAM = "projectId";

    @Inject
    UserContext userContext;

    @Inject
    PermissionService permissionService;

    @Inject
    UserService userService;

    @Inject
    UriInfo uriInfo;

    @AroundInvoke
    public Object check(InvocationContext context) throws Exception {
        var required = binding(context);
        if (required == null) {
            return context.proceed();
        }
        var user = userContext.get();
        if (user == null) {
            throw new UnauthorizedException();
        }
        var perm = required.value();
        // A global ban disables the permission itself, overriding admin and role privileges,
        // but does not affect endpoints with defaultValue = true.
        var banned = permissionService.isBanned(perm);
        if (!banned) {
            if (required.allowAdmin() && permissionService.isAdmin(user.getId())) {
                return context.proceed();
            }
            var defaultRole = required.defaultRole();
            var projectId = projectId(context);
            if (projectId == null && (!perm.global() || defaultRole != ProjectRole.NONE)) {
                throw new IllegalStateException(
                        "no @ProjectId argument to scope " + perm.permission() + " to on " + context.getMethod());
            }
            if (permissionService.has(user.getId(), perm, projectId)) {
                return context.proceed();
            }
            if (defaultRole != ProjectRole.NONE
                    && userService.hasAtLeast(user.getId(), projectId, defaultRole)) {
                return context.proceed();
            }
        }
        if (required.defaultValue()) {
            return context.proceed();
        }
        throw new ForbiddenException(banned
                ? "permission is globally disabled: " + perm.permission()
                : "missing permission: " + perm.permission());
    }

    private RequirePermission binding(InvocationContext context) {
        var method = context.getMethod();
        if (method == null) {
            return null;
        }
        var onMethod = method.getAnnotation(RequirePermission.class);
        return onMethod != null
                ? onMethod
                : method.getDeclaringClass().getAnnotation(RequirePermission.class);
    }

    @Nullable
    private UUID projectId(InvocationContext context) {
        var explicit = annotatedArgument(context);
        return explicit != null ? explicit : fromRequestPath();
    }

    @Nullable
    private UUID annotatedArgument(InvocationContext context) {
        var method = context.getMethod();
        if (method == null) {
            return null;
        }
        var parameters = method.getParameters();
        var arguments = context.getParameters();
        for (var i = 0; i < parameters.length && i < arguments.length; i++) {
            if (parameters[i].isAnnotationPresent(ProjectId.class) && arguments[i] instanceof UUID projectId) {
                return projectId;
            }
        }
        return null;
    }

    /** Resolves the project ID from the REST request path parameters, if present. */
    @Nullable
    private UUID fromRequestPath() {
        String value;
        try {
            value = uriInfo.getPathParameters().getFirst(PROJECT_PATH_PARAM);
        } catch (RuntimeException e) {
            return null;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("malformed " + PROJECT_PATH_PARAM + ": " + value);
        }
    }
}
