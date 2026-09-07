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
 * What {@link JobSpecTemplate}'s visibility clause lets through, and what it keeps out.
 *
 * <p>A template belongs to one project or to none, and a project sees its own plus every global one.
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
        mine = fixtures.project("mine");
        theirs = fixtures.project("theirs");
    }

    @Test
    void aProjectSeesItsOwnAndTheGlobalOnes() {
        var small = fixtures.resourceClass("small", null);
        fixtures.template("ours", mine, small);
        fixtures.template("shared", null, small);
        fixtures.template("theirs", theirs, small);

        var names = inTx(() -> JobSpecTemplate.listVisibleFetched(mine).stream()
                .map(JobSpecTemplate::getName)
                .sorted()
                .toList());

        // No order by on the finder, so only membership is asserted.
        assertEquals(List.of("ours", "shared"), names);
    }

    @Test
    void aProjectWithNoTemplatesOfItsOwnStillSeesTheGlobalOnes() {
        var small = fixtures.resourceClass("small", null);
        fixtures.template("shared", null, small);

        var names = inTx(() -> JobSpecTemplate.listVisibleFetched(theirs).stream()
                .map(JobSpecTemplate::getName)
                .toList());

        assertEquals(List.of("shared"), names);
    }

    @Test
    void oneOfAnothersIsNotVisible() {
        var small = fixtures.resourceClass("small", null);
        var id = fixtures.template("theirs", theirs, small);

        assertTrue(inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).isEmpty());
    }

    @Test
    void aGlobalOneIsVisibleById() {
        var small = fixtures.resourceClass("small", null);
        var id = fixtures.template("shared", null, small);

        assertTrue(inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).isPresent());
    }

    @Test
    void aProjectFindsItsOwnById() {
        var small = fixtures.resourceClass("small", null);
        var id = fixtures.template("ours", mine, small);

        assertTrue(inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).isPresent());
    }

    /**
     * The {@code Fetched} in the name is the point: {@code resourceClass} is lazy, so reading it after
     * the transaction has closed only works because the finder joined it in.
     */
    @Test
    void theResourceClassComesBackAlreadyLoaded() {
        var small = fixtures.resourceClass("small", null);
        var id = fixtures.template("ours", mine, small);

        var template = inTx(() -> JobSpecTemplate.findVisibleFetched(mine, id)).orElseThrow();

        assertEquals("small", template.getResourceClass().getName());
    }
}
