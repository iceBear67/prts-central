package io.ib67.prts.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@link java.util.UUID} parameter naming the project a {@link RequirePermission} check
 * applies to. Only needed where the project is not the one in the request path: without it, the
 * check falls back to the {@link RequirePermissionInterceptor#PROJECT_PATH_PARAM} path variable.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectId {
}
