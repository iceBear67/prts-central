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

    /** A cause chain deeper than this is a cycle, not a wrapping. */
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
     * Recovers the exception a deserialization failure is carrying.
     *
     * <p>A throw from a request record's constructor reaches the reader as a Jackson
     * {@code DatabindException}, which {@code ServerJacksonMessageBodyReader} rewraps in a bare
     * {@link WebApplicationException} whose status is always 400 and whose message is generated from
     * that status. The status and message actually thrown survive only in the cause chain.
     *
     * <p>Keyed on the exact type because the reader is the only thing that raises a plain
     * {@code WebApplicationException} — everything this service throws is a subclass, which already says
     * what it means and is left alone.
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
