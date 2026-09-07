package io.ib67.prts;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Objects;

/**
 * Formats 4xx client errors into JSON error responses containing the exception message.
 *
 * <p>Passes through responses that already have an entity, and leaves 5xx errors untouched.
 */
@Provider
public class ClientErrorMapper implements ExceptionMapper<WebApplicationException> {

    public record ErrorView(String message) {
        public ErrorView {
            Objects.requireNonNull(message, "message");
        }
    }

    @Override
    public Response toResponse(WebApplicationException exception) {
        var response = exception.getResponse();
        var status = response.getStatus();
        if (response.hasEntity() || status < 400 || status >= 500) {
            return response;
        }
        var message = exception.getMessage();
        return Response.fromResponse(response)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorView(message == null ? "" : message))
                .build();
    }
}
