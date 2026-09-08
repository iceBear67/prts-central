package io.ib67.prts.testing;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import org.mockito.MockedStatic;

import java.util.concurrent.Callable;

import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Mocks {@link QuarkusTransaction#requiringNew()} to execute runnables and callables
 * synchronously on the calling thread in unit tests without requiring a transaction manager.
 */
public final class InlineTransactions implements AutoCloseable {

    private final MockedStatic<QuarkusTransaction> quarkusTransaction;

    @SuppressWarnings("unchecked")
    public InlineTransactions() {
        // RETURNS_SELF supports method chaining on TransactionRunnerOptions.
        var runner = mock(TransactionRunnerOptions.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(runner.call(any())).thenAnswer(invocation ->
                invocation.getArgument(0, Callable.class).call());
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(runner).run(any());

        quarkusTransaction = mockStatic(QuarkusTransaction.class);
        quarkusTransaction.when(QuarkusTransaction::requiringNew).thenReturn(runner);
    }

    @Override
    public void close() {
        quarkusTransaction.close();
    }
}
