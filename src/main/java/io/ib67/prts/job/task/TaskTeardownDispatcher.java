package io.ib67.prts.job.task;

import io.ib67.prts.job.JobConfig;
import io.ib67.prts.job.task.entity.Task;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background sweep finishing the teardown of tasks left closing.
 *
 * <p>{@link TaskService#close} tears a task down on the spot, so this catches the two cases where that
 * pass could not finish: the process died partway through, and a job was dispatched while work was
 * being stopped. Unlike project deletion there is no caller waiting on an HTTP response, so a task
 * simply stays {@code CLOSING} until a sweep gets it clean.
 */
@ApplicationScoped
public class TaskTeardownDispatcher {
    private static final Logger LOG = Logger.getLogger(TaskTeardownDispatcher.class);

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "prts-task-teardown");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    TaskService taskService;
    @Inject
    JobConfig jobConfig;

    void start(@Observes StartupEvent event) {
        var interval = jobConfig.task().teardownInterval().toMillis();
        ticker.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    // Visible for testing to trigger a single sweep manually.
    void tick() {
        try {
            for (var taskId : closing()) {
                try {
                    taskService.teardown(taskId);
                } catch (RuntimeException e) {
                    LOG.errorf(e, "cannot finish tearing down task %s", taskId);
                }
            }
        } catch (Throwable t) {
            LOG.error("task teardown sweep failed", t);
        }
    }

    private List<UUID> closing() {
        return QuarkusTransaction.requiringNew()
                .call(() -> Task.listClosing(jobConfig.task().teardownBatch()).stream()
                        .map(Task::getId)
                        .toList());
    }
}
