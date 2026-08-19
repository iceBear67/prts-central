package io.ib67.prts.auth;

import io.ib67.prts.Perms;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserContext;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

@Interceptor
@RequirePermission("")
@Priority(Interceptor.Priority.APPLICATION)
public class RequirePermissionInterceptor {

    @Inject
    UserContext userContext;

    @Inject
    PermissionService permissionService;

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
        var granted = permissionService.permissionsOf(user.getId());
        if (required.allowAdmin() && granted.contains(Perms.ADMIN_OF_ALL)) {
            return context.proceed();
        }
        if (granted.contains(required.value()) || required.defaultValue()) {
            return context.proceed();
        }
        throw new ForbiddenException("missing permission: " + required.value());
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
}
