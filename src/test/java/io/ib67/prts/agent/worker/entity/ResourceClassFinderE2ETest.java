package io.ib67.prts.agent.worker.entity;

import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.inTx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceClass#findVisible} resolves a name in two steps, and the order of the two is the rule:
 * a project's own definition shadows the global one of the same name.
 */
@QuarkusTest
@Tag("e2e")
class ResourceClassFinderE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private UUID mine;
    private UUID theirs;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        mine = fixtures.project("mine");
        theirs = fixtures.project("theirs");
    }

    @Test
    void aProjectsOwnDefinitionShadowsTheGlobalOne() {
        fixtures.resourceClass("small", null);
        fixtures.resourceClass("small", mine);

        var found = inTx(() -> ResourceClass.findVisible(mine, "small")).orElseThrow();

        assertEquals(mine, found.getProjectId());
        assertFalse(found.isGlobal());
    }

    @Test
    void theGlobalDefinitionIsTheFallback() {
        fixtures.resourceClass("small", null);

        var found = inTx(() -> ResourceClass.findVisible(mine, "small")).orElseThrow();

        assertTrue(found.isGlobal());
    }

    @Test
    void anotherProjectsDefinitionIsInvisible() {
        fixtures.resourceClass("small", theirs);

        assertTrue(inTx(() -> ResourceClass.findVisible(mine, "small")).isEmpty());
    }

    /** A null project is already the global scope, so there is nothing further to fall back to. */
    @Test
    void theGlobalScopeDoesNotFallBackToAProject() {
        fixtures.resourceClass("small", mine);

        assertTrue(inTx(() -> ResourceClass.findVisible(null, "small")).isEmpty());
    }

    @Test
    void aNameNobodyDefinedIsAbsent() {
        assertTrue(inTx(() -> ResourceClass.findVisible(mine, "huge")).isEmpty());
    }

    @Test
    void deletingAProjectsClassesLeavesTheGlobalOnesStanding() {
        fixtures.resourceClass("small", null);
        fixtures.resourceClass("small", mine);
        fixtures.resourceClass("small", theirs);

        assertEquals(1L, (long) inTx(() -> ResourceClass.deleteByProject(mine)));

        assertTrue(inTx(() -> ResourceClass.findVisible(mine, "small")).orElseThrow().isGlobal());
        assertFalse(inTx(() -> ResourceClass.findVisible(theirs, "small")).orElseThrow().isGlobal());
    }

    /** The sentinel is a key value, not a project; wiping it would take every project's fallback. */
    @Test
    void theGlobalScopeCannotBeDeletedAsIfItWereAProject() {
        assertThrows(IllegalArgumentException.class,
                () -> inTx(() -> ResourceClass.deleteByProject(ResourceClass.GLOBAL)));
        assertThrows(IllegalArgumentException.class,
                () -> inTx(() -> ResourceClass.deleteByProject(null)));
    }
}
