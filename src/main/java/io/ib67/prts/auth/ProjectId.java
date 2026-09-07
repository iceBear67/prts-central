package io.ib67.prts.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Designates a method parameter as the project ID for {@link RequirePermission} evaluation.
 *
 * <p>Overrides the default project ID resolved from the request path.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectId {
}
