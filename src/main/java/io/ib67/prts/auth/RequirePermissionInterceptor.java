package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
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

/** The binding value is irrelevant here: every member of the annotation is {@code @Nonbinding}. */
@Interceptor
@RequirePermission(Perm.ADMIN_OF_ALL)
@Priority(Interceptor.Priority.APPLICATION)
public class RequirePermissionInterceptor {

    /**
     * Path template variable naming the project. Every project-scoped endpoint spells it exactly
     * this way, which is how a check deep inside a request — spec override gating, say — finds its
     * project without each method along the way taking one as an argument.
     */
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
        if (required.allowAdmin() && permissionService.isAdmin(user.getId())) {
            return context.proceed();
        }
        var perm = required.value();
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
        if (required.defaultValue()) {
            return context.proceed();
        }
        throw new ForbiddenException("missing permission: " + perm.permission());
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

    /** An argument wins over the request: it names the project regardless of how we got here. */
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

    /**
     * Reads {@link #PROJECT_PATH_PARAM} off the matched path. Anything not serving a REST request —
     * a worker message, the scheduler tick — has no path to read and so gets no project.
     */
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
            // What JAX-RS itself does with a path parameter it cannot convert.
            throw new NotFoundException("malformed " + PROJECT_PATH_PARAM + ": " + value);
        }
    }
}
