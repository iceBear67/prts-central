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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests resolution order for {@link ResourceClass#findVisible}:
 * project-scoped definitions shadow global ones.
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
        mine = fixtures.createProject("mine");
        theirs = fixtures.createProject("theirs");
    }

    @Test
    void aProjectsOwnDefinitionShadowsTheGlobalOne() {
        fixtures.createResourceClass("small", null);
        fixtures.createResourceClass("small", mine);

        var found = inTx(() -> ResourceClass.findVisible(mine, "small")).orElseThrow();

        assertEquals(mine, found.getProjectId());
        assertFalse(found.isGlobal());
    }

    @Test
    void theGlobalDefinitionIsTheFallback() {
        fixtures.createResourceClass("small", null);

        var found = inTx(() -> ResourceClass.findVisible(mine, "small")).orElseThrow();

        assertTrue(found.isGlobal());
    }

    @Test
    void anotherProjectsDefinitionIsInvisible() {
        fixtures.createResourceClass("small", theirs);

        assertTrue(inTx(() -> ResourceClass.findVisible(mine, "small")).isEmpty());
    }

    /** Global scope queries do not look into project scopes. */
    @Test
    void theGlobalScopeDoesNotFallBackToAProject() {
        fixtures.createResourceClass("small", mine);

        assertTrue(inTx(() -> ResourceClass.findVisible(null, "small")).isEmpty());
    }

    @Test
    void aNameNobodyDefinedIsAbsent() {
        assertTrue(inTx(() -> ResourceClass.findVisible(mine, "huge")).isEmpty());
    }

    @Test
    void deletingAProjectsClassesLeavesTheGlobalOnesStanding() {
        fixtures.createResourceClass("small", null);
        fixtures.createResourceClass("small", mine);
        fixtures.createResourceClass("small", theirs);

        assertEquals(1L, (long) inTx(() -> ResourceClass.deleteByProject(mine)));

        assertTrue(inTx(() -> ResourceClass.findVisible(mine, "small")).orElseThrow().isGlobal());
        assertFalse(inTx(() -> ResourceClass.findVisible(theirs, "small")).orElseThrow().isGlobal());
    }
}
