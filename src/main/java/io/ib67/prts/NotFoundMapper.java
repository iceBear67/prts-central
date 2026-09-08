package io.ib67.prts;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.NoSuchElementException;

/**
 * Maps {@link NoSuchElementException} thrown by services to HTTP 404 Not Found responses.
 *
 * <p>Returns a standard JSON error response ({@code {"message": ...}}) containing the exception message.
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
