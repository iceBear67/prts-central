package io.ib67.prts.agent.job.entity;

import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.inTx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests visibility rules for {@link JobSpecTemplate}.
 *
 * <p>A project can access its own templates and global templates.
 */
@QuarkusTest
@Tag("e2e")
class JobSpecTemplateFinderE2ETest {

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
    void aProjectSeesItsOwnAndTheGlobalOnes() {
        var small = fixtures.createResourceClass("small");
        fixtures.createTemplate("ours", mine, small);
        fixtures.createTemplate("shared", null, small);
        fixtures.createTemplate("theirs", theirs, small);

        var names = inTx(() -> JobSpecTemplate.listVisibleFetched(mine, 0, 50).stream()
                .map(JobSpecTemplate::getName)
                .sorted()
                .toList());

        assertEquals(List.of("ours", "shared"), names);
    }

    @Test
    void aProjectWithNoTemplatesOfItsOwnStillSeesTheGlobalOnes() {
        var small = fixtures.createResourceClass("small");
        fixtures.createTemplate("shared", null, small);

        var names = inTx(() -> JobSpecTemplate.listVisibleFetched(theirs, 0, 50).stream()
                .map(JobSpecTemplate::getName)
                .toList());

        assertEquals(List.of("shared"), names);
    }

    @Test
    void oneOfAnothersIsNotVisible() {
        var small = fixtures.createResourceClass("small");
        var id = fixtures.createTemplate("theirs", theirs, small);

        assertTrue(inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).isEmpty());
    }

    @Test
    void aGlobalOneIsVisibleById() {
        var small = fixtures.createResourceClass("small");
        var id = fixtures.createTemplate("shared", null, small);

        assertTrue(inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).isPresent());
    }

    @Test
    void aProjectFindsItsOwnById() {
        var small = fixtures.createResourceClass("small");
        var id = fixtures.createTemplate("ours", mine, small);

        assertTrue(inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).isPresent());
    }

    /**
     * Verifies that the lazy {@code resourceClass} association is eagerly fetched.
     */
    @Test
    void theResourceClassComesBackAlreadyLoaded() {
        var small = fixtures.createResourceClass("small");
        var id = fixtures.createTemplate("ours", mine, small);

        var template = inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).orElseThrow();

        assertEquals("small", template.getResourceClass().getName());
    }
}
