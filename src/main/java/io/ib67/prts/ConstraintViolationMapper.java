package io.ib67.prts;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Maps {@link ConstraintViolationException} to standard JSON error responses ({@code {"message": ...}}).
 *
 * <p>Registered specifically for {@link ConstraintViolationException} to take precedence over Quarkus's
 * default {@code ResteasyReactiveViolationExceptionMapper} (which is registered for the broader
 * {@link jakarta.validation.ValidationException}).
 */
@Provider
public class ConstraintViolationMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        if (exception.getConstraintViolations().stream().anyMatch(ConstraintViolationMapper::onReturnValue)) {
            // Return value violations indicate internal server errors rather than client bad requests.
            // Rethrowing allows Quarkus to handle them as HTTP 500.
            throw exception;
        }
        // Sort by property path to produce deterministic error message ordering.
        var message = exception.getConstraintViolations().stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ClientErrorMapper.ErrorView(message))
                .build();
    }

    private static boolean onReturnValue(ConstraintViolation<?> violation) {
        var nodes = violation.getPropertyPath().iterator();
        if (!nodes.hasNext() || nodes.next().getKind() != ElementKind.METHOD) {
            return false;
        }
        return nodes.hasNext() && nodes.next().getKind() == ElementKind.RETURN_VALUE;
    }
}
