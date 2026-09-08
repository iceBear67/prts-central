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

    /** Maximum traversal depth for cause chains to avoid circular references. */
    private static final int MAX_UNWRAP = 8;

    public record ErrorView(String message) {
        public ErrorView {
            Objects.requireNonNull(message, "message");
        }
    }

    @Override
    public Response toResponse(WebApplicationException exception) {
        var thrown = unwrapped(exception);
        var response = thrown.getResponse();
        var status = response.getStatus();
        if (response.hasEntity() || status < 400 || status >= 500) {
            return response;
        }
        var message = thrown.getMessage();
        return Response.fromResponse(response)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorView(message == null ? "" : message))
                .build();
    }

    /**
     * Unwraps the underlying application exception from deserialization failures.
     *
     * <p>When a request DTO constructor throws an exception, Jackson catches it as a
     * {@code DatabindException}, and {@code ServerJacksonMessageBodyReader} wraps it in a generic
     * {@link WebApplicationException} (HTTP 400). Unwrapping the cause chain recovers the original
     * exception's status and message.
     *
     * <p>Only exact instances of {@code WebApplicationException} are unwrapped; subclasses thrown
     * directly by the application are returned as-is.
     */
    private static WebApplicationException unwrapped(WebApplicationException thrown) {
        if (thrown.getClass() != WebApplicationException.class) {
            return thrown;
        }
        var cause = thrown.getCause();
        for (var depth = 0; cause != null && depth < MAX_UNWRAP; depth++) {
            if (cause instanceof WebApplicationException carried) {
                return carried;
            }
            cause = cause.getCause();
        }
        return thrown;
    }
}
