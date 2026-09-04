package io.ib67.prts;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Objects;

/**
 * Gives a 4xx the message it was thrown with. {@code WebApplicationException(String, Status)} builds
 * an entity-less response, so without this every {@code throw new BadRequestException("name is
 * required")} in the tree reaches the client as a bare status — two different 409s become
 * indistinguishable.
 *
 * <p>Only client errors are touched: a 5xx keeps whatever the runtime decided, so an internal message
 * never leaks, and a response that already carries an entity is passed through untouched.
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
