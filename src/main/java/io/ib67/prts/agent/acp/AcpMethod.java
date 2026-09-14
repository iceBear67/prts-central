package io.ib67.prts.agent.acp;

import jakarta.annotation.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Allowlist of supported ACP methods and their routing targets.
 *
 * <p>Unrecognized methods are rejected to prevent unvetted protocol extensions from reaching agents
 * or clients. See {@code agent-docs/agent-acp.md}.
 */
public enum AcpMethod {
    /** Answered locally from the worker's attach snapshot; never forwarded. */
    INITIALIZE("initialize", Route.LOCAL),

    SESSION_PROMPT("session/prompt", Route.TO_AGENT),
    SESSION_CANCEL("session/cancel", Route.TO_AGENT),
    SESSION_SET_MODE("session/set_mode", Route.TO_AGENT),
    SESSION_SET_CONFIG_OPTION("session/set_config_option", Route.TO_AGENT),

    SESSION_UPDATE("session/update", Route.TO_CLIENT),
    SESSION_REQUEST_PERMISSION("session/request_permission", Route.TO_CLIENT),
    ELICITATION_CREATE("elicitation/create", Route.TO_CLIENT),
    ELICITATION_COMPLETE("elicitation/complete", Route.TO_CLIENT);

    public enum Route {
        /** Served by prts-central itself. */
        LOCAL,
        /** Viewer to the job's agent. */
        TO_AGENT,
        /** The job's agent to every attached viewer. */
        TO_CLIENT;

        /** Whether a viewer may send a method routed this way. */
        public boolean acceptsFromClient() {
            return this != TO_CLIENT;
        }

        /** Whether the agent may send a method routed this way. */
        public boolean acceptsFromAgent() {
            return this == TO_CLIENT;
        }
    }

    private final String method;
    private final Route route;

    private static final Map<String, AcpMethod> BY_METHOD = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(AcpMethod::method, Function.identity()));

    AcpMethod(String method, Route route) {
        this.method = method;
        this.route = route;
    }

    public String method() {
        return method;
    }

    public Route route() {
        return route;
    }

    public static Optional<AcpMethod> byMethod(@Nullable String method) {
        return method == null ? Optional.empty() : Optional.ofNullable(BY_METHOD.get(method));
    }
}
