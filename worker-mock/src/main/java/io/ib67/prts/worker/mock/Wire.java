package io.ib67.prts.worker.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.ib67.prts.worker.mock.protocol.InboundEnvelope;
import io.ib67.prts.worker.mock.protocol.OutboundEnvelope;

/**
 * JSON codec for the worker protocol.
 *
 * <p>Unknown properties are ignored rather than rejected: the control plane may add fields to a
 * message at any time, and a worker that refused them would be the thing that breaks.
 */
public final class Wire {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Instants travel as ISO-8601 text; only this module's own debug output is affected.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private Wire() {
    }

    public static String write(OutboundEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot encode " + envelope, e);
        }
    }

    public static InboundEnvelope read(String json) {
        try {
            return MAPPER.readValue(json, InboundEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot decode " + json, e);
        }
    }

    /** A fresh tree node, for the JSON-RPC frames an agent exchanges. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
