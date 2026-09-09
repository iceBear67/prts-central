package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.job.entity.ProjectRole;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    @Nonbinding
    Perm value();

    /** Whether access is granted by default if no explicit permission or role matches. */
    @Nonbinding
    boolean defaultValue() default false;

    /** Minimum project role that satisfies this permission check. */
    @Nonbinding
    ProjectRole defaultRole() default ProjectRole.NONE;

    @Nonbinding
    boolean allowAdmin() default true;
}
