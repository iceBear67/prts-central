package io.ib67.prts.testing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InlineTransactions}.
 */
class InlineTransactionsTest {

    @Test
    void callRunsTheBodyAndReturnsItsValue() {
        try (var ignored = new InlineTransactions()) {
            assertEquals("done", QuarkusTransaction.requiringNew().call(() -> "done"));
        }
    }

    @Test
    void runRunsTheBody() {
        var ran = new AtomicBoolean();
        try (var ignored = new InlineTransactions()) {
            QuarkusTransaction.requiringNew().run(() -> ran.set(true));
        }
        assertTrue(ran.get());
    }

    @Test
    void aFailingBodyPropagates() {
        try (var ignored = new InlineTransactions()) {
            var error = assertThrows(IllegalStateException.class,
                    () -> QuarkusTransaction.requiringNew().call(() -> {
                        throw new IllegalStateException("boom");
                    }));
            assertEquals("boom", error.getMessage());
        }
    }
}
