package io.ib67.prts.agent.worker.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceClassTest {

    /**
     * Prevents deletion of the global resource class scope.
     */
    @Test
    void theGlobalScopeCannotBeDeletedAsIfItWereAProject() {
        assertThrows(IllegalArgumentException.class, () -> ResourceClass.deleteByProject(ResourceClass.GLOBAL));
        assertThrows(IllegalArgumentException.class, () -> ResourceClass.deleteByProject(null));
    }
}
