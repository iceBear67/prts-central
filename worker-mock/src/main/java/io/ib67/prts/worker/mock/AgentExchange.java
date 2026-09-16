package io.ib67.prts.worker.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * One frame of a job's agent conversation, and the ways to answer it.
 *
 * <p>Frames are JSON-RPC 2.0 as the Agent Client Protocol spells them; the mock relays them
 * verbatim, so a behaviour is free to put whatever it likes in {@code result} or {@code params}.
 */
public interface AgentExchange {
    /** JSON-RPC: the method is not one this agent serves. */
    int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC: the frame is missing something the method needs. */
    int INVALID_PARAMS = -32602;

    UUID jobId();

    /** The root session id this agent attached under. */
    String sessionId();

    /** The frame the control plane forwarded. */
    JsonNode frame();

    /** Answers a request. */
    void reply(JsonNode id, ObjectNode result);

    /** Refuses a request. */
    void fail(JsonNode id, int code, String message);

    /** Sends a notification, which answers nothing. */
    void notify(String method, ObjectNode params);

    /** Ends the agent while the job keeps running. */
    void detach(String reason);
}
