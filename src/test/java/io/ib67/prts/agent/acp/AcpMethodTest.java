package io.ib67.prts.agent.acp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the proxy carries, and which way. */
class AcpMethodTest {

    @Test
    void initializeIsAnsweredByCentralItself() {
        assertEquals(AcpMethod.Route.LOCAL, AcpMethod.byMethod("initialize").orElseThrow().route());
    }

    @Test
    void aPromptGoesToTheAgent() {
        assertEquals(AcpMethod.Route.TO_AGENT, AcpMethod.byMethod("session/prompt").orElseThrow().route());
    }

    @Test
    void anUpdateGoesToTheViewers() {
        assertEquals(AcpMethod.Route.TO_CLIENT, AcpMethod.byMethod("session/update").orElseThrow().route());
    }

    /** A viewer forging an update would write into the transcript as if the agent had said it. */
    @Test
    void aViewerCannotSendWhatOnlyTheAgentSends() {
        assertFalse(AcpMethod.SESSION_UPDATE.route().acceptsFromClient());
        assertFalse(AcpMethod.SESSION_REQUEST_PERMISSION.route().acceptsFromClient());
    }

    @Test
    void theAgentCannotSendWhatOnlyAViewerSends() {
        assertFalse(AcpMethod.SESSION_PROMPT.route().acceptsFromAgent());
        assertFalse(AcpMethod.SESSION_CANCEL.route().acceptsFromAgent());
    }

    @Test
    void aViewerMayAskForWhatCentralServesItself() {
        assertTrue(AcpMethod.INITIALIZE.route().acceptsFromClient());
        assertFalse(AcpMethod.INITIALIZE.route().acceptsFromAgent());
    }

    /**
     * The refusals that matter: the filesystem and terminals belong to the worker, credentials belong
     * to nobody on this side, and a session belongs to its job rather than to whoever asks for one.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "fs/read_text_file",
            "fs/write_text_file",
            "terminal/create",
            "terminal/output",
            "terminal/wait_for_exit",
            "terminal/kill",
            "terminal/release",
            "authenticate",
            "logout",
            "session/new",
            "session/load",
            "session/resume",
            "session/close",
            "session/delete",
            "session/list",
            "$/cancel_request",
            "",
            "SESSION/PROMPT",
    })
    void nothingElseIsCarried(String method) {
        assertTrue(AcpMethod.byMethod(method).isEmpty(), method + " is on the allowlist");
    }

    @Test
    void anAbsentMethodResolvesToNothing() {
        assertTrue(AcpMethod.byMethod(null).isEmpty());
    }
}
