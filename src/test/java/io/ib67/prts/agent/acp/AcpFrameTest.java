package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a JSON-RPC envelope: what routing and recording need, and nothing else. */
class AcpFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Only malformed JSON is a broken test; a refused envelope is what several of these assert on. */
    private static AcpFrame parse(String json) {
        try {
            return AcpFrame.of(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new AssertionError(json, e);
        }
    }

    private static IllegalArgumentException refused(String json) {
        return assertThrows(IllegalArgumentException.class, () -> parse(json));
    }

    @Test
    void aRequestCarriesAnIdAndAMethod() {
        var frame = parse("""
                {"jsonrpc":"2.0","id":7,"method":"session/prompt","params":{"sessionId":"s1"}}""");

        assertTrue(frame.isRequest());
        assertFalse(frame.isNotification());
        assertFalse(frame.isResponse());
        assertEquals(7, frame.id().asInt());
        assertEquals("session/prompt", frame.method());
    }

    @Test
    void aNotificationHasAMethodAndNoId() {
        var frame = parse("""
                {"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"s1"}}""");

        assertTrue(frame.isNotification());
        assertFalse(frame.isRequest());
        assertNull(frame.id());
    }

    /** An explicit null id is absent, not an id of its own. */
    @Test
    void aNullIdIsNoId() {
        assertTrue(parse("""
                {"jsonrpc":"2.0","id":null,"method":"session/cancel"}""").isNotification());
    }

    @Test
    void aResultIsAResponse() {
        var frame = parse("""
                {"jsonrpc":"2.0","id":7,"result":{"stopReason":"end_turn"}}""");

        assertTrue(frame.isResponse());
        assertNull(frame.method());
    }

    @Test
    void anErrorIsAResponseToo() {
        assertTrue(parse("""
                {"jsonrpc":"2.0","id":7,"error":{"code":-32601,"message":"nope"}}""").isResponse());
    }

    /** A string id is as valid as a number, and must survive being handed back. */
    @Test
    void aStringIdIsKept() {
        var frame = parse("""
                {"jsonrpc":"2.0","id":"a-7","method":"session/prompt"}""");

        assertEquals("a-7", frame.id().asText());
    }

    @Test
    void theSessionComesFromTheParameters() {
        assertEquals("s1", parse("""
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{}}}""")
                .sessionId());
    }

    @Test
    void aFrameNamingNoSessionHasNone() {
        assertNull(parse("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}""")
                .sessionId());
        assertNull(parse("""
                {"jsonrpc":"2.0","id":1,"method":"initialize"}""").sessionId());
    }

    @Test
    void anEnvelopeWithoutAVersionIsRefused() {
        assertTrue(refused("""
                {"id":1,"method":"initialize"}""").getMessage().contains("jsonrpc"));
    }

    @Test
    void anEnvelopeOfAnotherVersionIsRefused() {
        refused("""
                {"jsonrpc":"1.0","id":1,"method":"initialize"}""");
    }

    /** ACP does not batch, so an array is not an envelope this proxy knows how to route. */
    @Test
    void anArrayIsRefused() {
        refused("""
                [{"jsonrpc":"2.0","id":1,"method":"initialize"}]""");
    }

    @Test
    void anEnvelopeThatIsNeitherACallNorAnAnswerIsRefused() {
        refused("""
                {"jsonrpc":"2.0","params":{}}""");
    }

    @Test
    void replacingTheIdLeavesTheOriginalAlone() {
        var original = parse("""
                {"jsonrpc":"2.0","id":7,"method":"session/prompt"}""");

        var rewritten = original.withId(IntNode.valueOf(42));

        assertEquals(42, rewritten.id().asInt());
        assertEquals(7, original.id().asInt(), "the frame handed in was modified");
        assertEquals("session/prompt", rewritten.method());
    }

    @Test
    void anErrorCarriesItsCodeMessageAndTheIdItAnswers() {
        var frame = AcpFrame.error(TextNode.valueOf("a-7"), AcpFrame.METHOD_NOT_FOUND, "nope");

        assertTrue(frame.isResponse());
        assertEquals("a-7", frame.id().asText());
        assertEquals(AcpFrame.METHOD_NOT_FOUND, frame.json().get("error").get("code").asInt());
        assertEquals("nope", frame.json().get("error").get("message").asText());
    }

    /** A frame whose id could not be read is still answered, with the null id JSON-RPC reserves. */
    @Test
    void anErrorWithoutAnIdAnswersNull() {
        var frame = AcpFrame.error(null, AcpFrame.PARSE_ERROR, "broken");

        assertTrue(frame.json().get("id").isNull());
        assertNull(frame.id());
    }

    @Test
    void aResultIsBuiltAroundTheIdItAnswers() {
        var payload = MAPPER.createObjectNode().put("stopReason", "end_turn");

        var frame = AcpFrame.result(IntNode.valueOf(3), payload);

        assertTrue(frame.isResponse());
        assertEquals(3, frame.id().asInt());
        assertEquals("end_turn", frame.json().get("result").get("stopReason").asText());
    }
}
