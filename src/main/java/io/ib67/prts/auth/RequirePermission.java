package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.project.ProjectRole;
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

    /** Allowed even without the permission and without a qualifying project role. */
    @Nonbinding
    boolean defaultValue() default false;

    /**
     * Project role that stands in for the permission: a caller holding at least this role in the
     * {@link ProjectId} project is allowed. {@link ProjectRole#NONE} turns the rule off — it is the
     * absence of a role, so it can never stand in for a grant.
     */
    @Nonbinding
    ProjectRole defaultRole() default ProjectRole.NONE;

    @Nonbinding
    boolean allowAdmin() default true;
}
