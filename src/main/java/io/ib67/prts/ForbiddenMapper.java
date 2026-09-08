package io.ib67.prts;

import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Gives the 403 of {@code RequirePermissionInterceptor} the same body as every other 4xx.
 *
 * <p>The interceptor throws Quarkus' {@link ForbiddenException}, a {@link SecurityException} rather
 * than a {@code WebApplicationException}, so {@link ClientErrorMapper} never saw it and the permission
 * it names was dropped — while the handful of endpoints throwing the JAX-RS one answered with a body.
 *
 * <p>The matching 401 is deliberately left alone: it is the authentication challenge, and under the
 * {@code web-app} OIDC flow answering it here would replace the redirect to the provider.
 */
@Provider
public class ForbiddenMapper implements ExceptionMapper<ForbiddenException> {

    @Override
    public Response toResponse(ForbiddenException exception) {
        var message = exception.getMessage();
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ClientErrorMapper.ErrorView(message == null ? "" : message))
                .build();
    }
}
