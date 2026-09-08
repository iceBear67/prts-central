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
     * The chain a throw from a request record's constructor actually arrives in: Jackson wraps the
     * constructor's exception, and {@code ServerJacksonMessageBodyReader} rewraps that as a bare 400.
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

    /** Without unwrapping this reads "HTTP 400 Bad Request", which tells the caller nothing. */
    @Test
    void aConstructorRejectionSurvivesDeserialization() {
        var response = mapper.toResponse(
                asDeserializationFailure(new BadRequestException("name is required")));

        assertEquals(400, response.getStatus());
        assertEquals("name is required", messageOf(response));
    }

    /** The carried exception decides the status too, not just the message. */
    @Test
    void aCarriedStatusOutranksTheReadersBadRequest() {
        var response = mapper.toResponse(asDeserializationFailure(
                new ClientErrorException("already taken", Response.Status.CONFLICT)));

        assertEquals(409, response.getStatus());
        assertEquals("already taken", messageOf(response));
    }

    /** Malformed JSON carries no exception of ours, so the reader's own 400 stands. */
    @Test
    void aFailureCarryingNothingOfOursKeepsTheReadersStatus() {
        var response = mapper.toResponse(new WebApplicationException(
                new IllegalArgumentException("Unexpected character"), Response.Status.BAD_REQUEST));

        assertEquals(400, response.getStatus());
        assertEquals("HTTP 400 Bad Request", messageOf(response));
    }

    /** Only the reader raises a plain WebApplicationException; a subclass already says what it means. */
    @Test
    void aSubclassIsNeverUnwrapped() {
        var response = mapper.toResponse(
                new NotFoundException("no such job", new BadRequestException("name is required")));

        assertEquals(404, response.getStatus());
        assertEquals("no such job", messageOf(response));
    }
}
