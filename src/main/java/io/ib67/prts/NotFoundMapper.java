package io.ib67.prts;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.NoSuchElementException;

/**
 * Maps {@link NoSuchElementException} thrown by services to HTTP 404 Not Found responses.
 */
@Provider
public class NotFoundMapper implements ExceptionMapper<NoSuchElementException> {

    @Override
    public Response toResponse(NoSuchElementException exception) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
