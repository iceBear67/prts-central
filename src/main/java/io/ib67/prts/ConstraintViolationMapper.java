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
 * Formats Bean Validation failures into the same {@code {"message": ...}} body every other 4xx uses.
 *
 * <p>Takes precedence over Quarkus' own {@code ResteasyReactiveViolationExceptionMapper}, which is
 * registered for {@link jakarta.validation.ValidationException}: the thrown
 * {@code ResteasyReactiveViolationException} extends {@link ConstraintViolationException}, and
 * {@code RuntimeExceptionMapper} walks up from the thrown class and takes the first mapper it finds,
 * so the nearer registration wins.
 */
@Provider
public class ConstraintViolationMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        if (exception.getConstraintViolations().stream().anyMatch(ConstraintViolationMapper::onReturnValue)) {
            // A violated return value is this service breaking its own contract, not a bad request.
            // Rethrowing leaves it a 500, which is what the built-in mapper does with one too.
            throw exception;
        }
        // Sorted by path so a payload failing several constraints always reads the same way.
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
