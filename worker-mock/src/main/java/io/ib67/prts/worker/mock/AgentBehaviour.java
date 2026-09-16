package io.ib67.prts.worker.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A job's agent, as far as the mock is concerned: what it announces on attach, and how it answers
 * the frames the control plane relays to it.
 */
public interface AgentBehaviour {

    /** The verbatim {@code initialize} result this agent reports; the control plane replays it to viewers. */
    default JsonNode initialize() {
        return JsonNodeFactory.instance.objectNode().put("protocolVersion", 1);
    }

    /** The root session id this agent's job is attached under. */
    default String rootSessionId() {
        return "agent-session";
    }

    /**
     * Handles one frame addressed to this agent.
     *
     * <p>Called on the mock's inbound dispatch thread, so a behaviour that blocks stalls every later
     * message from the control plane. Answer, or record what to answer with.
     */
    void onFrame(AgentExchange exchange);

    /**
     * An agent that does the least a viewer can expect: answers a prompt with one message chunk and an
     * {@code end_turn} stop reason, and refuses anything else it is asked.
     */
    static AgentBehaviour echoing() {
        return exchange -> {
            var frame = exchange.frame();
            var method = frame.path("method").asText("");
            var id = frame.get("id");
            switch (method) {
                case "session/update" -> {
                    // A notification from the proxy; nothing to answer.
                }
                case "session/cancel" -> answer(exchange, id, "cancelled");
                case "session/prompt" -> {
                    var session = frame.path("params").path("sessionId").asText(exchange.sessionId());
                    exchange.notify("session/update", update(session, textOf(frame)));
                    answer(exchange, id, "end_turn");
                }
                default -> {
                    // No method means a reply to a request this agent never sent, so there is
                    // nothing to answer; anything else is a method it does not carry.
                    if (id != null && !method.isEmpty()) {
                        exchange.fail(id, AgentExchange.METHOD_NOT_FOUND,
                                "the mock agent does not carry " + method);
                    }
                }
            }
        };
    }

    private static void answer(AgentExchange exchange, JsonNode id, String stopReason) {
        if (id != null) {
            exchange.reply(id, JsonNodeFactory.instance.objectNode().put("stopReason", stopReason));
        }
    }

    private static ObjectNode update(String sessionId, String text) {
        var params = JsonNodeFactory.instance.objectNode();
        params.put("sessionId", sessionId);
        var update = params.putObject("update");
        update.put("sessionUpdate", "agent_message_chunk");
        var content = update.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return params;
    }

    /** Echoes back the text the viewer sent, so a test can follow its own prompt through the proxy. */
    private static String textOf(JsonNode frame) {
        for (var block : frame.path("params").path("prompt")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText("");
            }
        }
        return "";
    }
}
