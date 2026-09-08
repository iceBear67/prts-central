package io.ib67.prts;

import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.type.TypeFactory;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientErrorMapperTest {

    private final ClientErrorMapper mapper = new ClientErrorMapper();

    /**
     * Simulates deserialization failures where Jackson wraps the constructor exception and
     * {@code ServerJacksonMessageBodyReader} rewraps it into a WebApplicationException(400).
     */
    private static WebApplicationException asDeserializationFailure(RuntimeException thrownInConstructor) {
        var wrapped = ValueInstantiationException.from(
                null, "Cannot construct instance",
                TypeFactory.defaultInstance().constructType(Object.class), thrownInConstructor);
        return new WebApplicationException(wrapped, Response.Status.BAD_REQUEST);
    }

    private static String messageOf(Response response) {
        return assertInstanceOf(ClientErrorMapper.ErrorView.class, response.getEntity()).message();
    }

    @Test
    void aClientErrorBecomesItsMessage() {
        var response = mapper.toResponse(new BadRequestException("name is required"));

        assertEquals(400, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        assertEquals("name is required", messageOf(response));
    }

    @Test
    void aMissingMessageBecomesEmpty() {
        assertEquals("", messageOf(mapper.toResponse(new NotFoundException((String) null))));
    }

    @Test
    void aResponseThatAlreadyHasAnEntityIsLeftAlone() {
        var carried = Response.status(400).entity("mine").build();

        assertSame(carried, mapper.toResponse(new WebApplicationException(carried)));
    }

    @Test
    void aServerErrorIsLeftAlone() {
        var response = mapper.toResponse(new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR));

        assertEquals(500, response.getStatus());
        assertNull(response.getEntity());
    }

    /** Unwraps the underlying exception so the client receives the constructor validation message. */
    @Test
    void aConstructorRejectionSurvivesDeserialization() {
        var response = mapper.toResponse(
                asDeserializationFailure(new BadRequestException("name is required")));

        assertEquals(400, response.getStatus());
        assertEquals("name is required", messageOf(response));
    }

    /** The nested client exception determines the response status code. */
    @Test
    void aCarriedStatusOutranksTheReadersBadRequest() {
        var response = mapper.toResponse(asDeserializationFailure(
                new ClientErrorException("already taken", Response.Status.CONFLICT)));

        assertEquals(409, response.getStatus());
        assertEquals("already taken", messageOf(response));
    }

    /** Unrecognized deserialization errors retain the message body reader's 400 status. */
    @Test
    void aFailureCarryingNothingOfOursKeepsTheReadersStatus() {
        var response = mapper.toResponse(new WebApplicationException(
                new IllegalArgumentException("Unexpected character"), Response.Status.BAD_REQUEST));

        assertEquals(400, response.getStatus());
        assertEquals("HTTP 400 Bad Request", messageOf(response));
    }

    /** Specific WebApplicationException subclasses are returned directly without unwrapping causes. */
    @Test
    void aSubclassIsNeverUnwrapped() {
        var response = mapper.toResponse(
                new NotFoundException("no such job", new BadRequestException("name is required")));

        assertEquals(404, response.getStatus());
        assertEquals("no such job", messageOf(response));
    }
}
