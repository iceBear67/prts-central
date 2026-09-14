package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;

import java.util.Objects;

/**
 * JSON-RPC 2.0 frame envelope for the Agent Client Protocol.
 *
 * <p>Central inspects only routing fields ({@code id}, {@code method}, {@code params.sessionId}) and
 * relays remaining payload verbatim. The underlying node is immutable; {@link #withId} creates a deep copy.
 */
public record AcpFrame(JsonNode json) {

    public static final String VERSION = "2.0";

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    /** Implementation-defined: caller lacks permission to route this frame. */
    public static final int FORBIDDEN = -32001;

    public AcpFrame {
        Objects.requireNonNull(json, "json");
    }

    /**
     * @throws IllegalArgumentException if the node is not a JSON-RPC 2.0 envelope
     */
    public static AcpFrame of(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("a JSON-RPC frame must be an object");
        }
        var version = json.get("jsonrpc");
        if (version == null || !VERSION.equals(version.asText(null))) {
            throw new IllegalArgumentException("a JSON-RPC frame must carry jsonrpc: " + VERSION);
        }
        var frame = new AcpFrame(json);
        if (frame.method() == null && frame.id() == null) {
            throw new IllegalArgumentException("a JSON-RPC frame must carry a method or an id");
        }
        return frame;
    }

    /** The request id, or null for a notification. */
    @Nullable
    public JsonNode id() {
        var id = json.get("id");
        return id == null || id.isNull() ? null : id;
    }

    @Nullable
    public String method() {
        var method = json.get("method");
        return method == null || !method.isTextual() ? null : method.asText();
    }

    /** Target session ID, or null if unspecified. */
    @Nullable
    public String sessionId() {
        var params = json.get("params");
        if (params == null || !params.isObject()) {
            return null;
        }
        var sessionId = params.get("sessionId");
        return sessionId == null || !sessionId.isTextual() ? null : sessionId.asText();
    }

    public boolean isRequest() {
        return method() != null && id() != null;
    }

    public boolean isNotification() {
        return method() != null && id() == null;
    }

    public boolean isResponse() {
        return method() == null && id() != null;
    }

    public AcpFrame withId(JsonNode id) {
        var copy = (ObjectNode) json.deepCopy();
        copy.set("id", Objects.requireNonNull(id, "id"));
        return new AcpFrame(copy);
    }

    /** Builds an error response for {@code id}, or null id if unreadable. */
    public static AcpFrame error(@Nullable JsonNode id, int code, String message) {
        var frame = JsonNodeFactory.instance.objectNode();
        frame.put("jsonrpc", VERSION);
        frame.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        var error = frame.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return new AcpFrame(frame);
    }

    public static AcpFrame result(JsonNode id, JsonNode result) {
        var frame = JsonNodeFactory.instance.objectNode();
        frame.put("jsonrpc", VERSION);
        frame.set("id", Objects.requireNonNull(id, "id"));
        frame.set("result", Objects.requireNonNull(result, "result"));
        return new AcpFrame(frame);
    }

    @Override
    public String toString() {
        return json.toString();
    }
}
