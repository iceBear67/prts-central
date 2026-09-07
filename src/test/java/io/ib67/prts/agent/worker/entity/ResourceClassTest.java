package io.ib67.prts.agent.worker.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceClassTest {

    /**
     * The sentinel is a key value, not a project; wiping it would take every project's fallback. The
     * guard fires before any query is built, which is what keeps this out of tier C.
     */
    @Test
    void theGlobalScopeCannotBeDeletedAsIfItWereAProject() {
        assertThrows(IllegalArgumentException.class, () -> ResourceClass.deleteByProject(ResourceClass.GLOBAL));
        assertThrows(IllegalArgumentException.class, () -> ResourceClass.deleteByProject(null));
    }
}
