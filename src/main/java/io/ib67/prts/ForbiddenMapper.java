package io.ib67.prts;

import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps Quarkus's security {@link ForbiddenException} (HTTP 403) to a standard JSON error response.
 *
 * <p>{@link io.ib67.prts.auth.RequirePermissionInterceptor} throws Quarkus's {@link ForbiddenException}
 * (a {@link SecurityException} rather than a {@code WebApplicationException}), which is not handled by
 * {@link ClientErrorMapper}. This mapper ensures 403 errors consistently return a JSON body with the error message.
 *
 * <p>Note that 401 Unauthorized errors are intentionally left unmapped without a body, allowing Quarkus OIDC
 * to handle authentication challenges and provider redirects.
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
