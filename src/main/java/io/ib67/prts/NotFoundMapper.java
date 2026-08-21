package io.ib67.prts;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.NoSuchElementException;

/**
 * Services report a missing row as {@link NoSuchElementException} — {@code ProjectService.require},
 * {@code JobService.require}, {@code UserService.requireUser}. Translating it here is what lets an
 * endpoint call those directly instead of looking the row up a second time just to raise a 404.
 *
 * <p>The trade-off is that a stray {@code Optional#get} or {@code Iterator#next} also reads as 404
 * instead of 500; the message is dropped rather than returned, so nothing internal leaks either way.
 */
@Provider
public class NotFoundMapper implements ExceptionMapper<NoSuchElementException> {

    @Override
    public Response toResponse(NoSuchElementException exception) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
