package io.ib67.prts;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.NoSuchElementException;

/**
 * Maps {@link NoSuchElementException} thrown by services to HTTP 404 Not Found responses.
 *
 * <p>Carries the same {@code {"message": ...}} body every other 4xx uses: a caller telling a missing
 * project from a missing job reads it, and one 404 answering with nothing at all would be the only
 * error in the service without a body.
 */
@Provider
public class NotFoundMapper implements ExceptionMapper<NoSuchElementException> {

    @Override
    public Response toResponse(NoSuchElementException exception) {
        var message = exception.getMessage();
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ClientErrorMapper.ErrorView(message == null ? "" : message))
                .build();
    }
}
