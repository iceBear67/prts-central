package io.ib67.prts.admin;

import io.ib67.prts.dto.admin.AdminStatsView;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.task.entity.TaskState;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.Config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The parts of {@code GET /admin/stats} the resource cannot reach on its own: this process's identity,
 * and the two aggregates that belong to no entity because only the dashboard asks for them.
 */
@ApplicationScoped
public class AdminStatsService {

    /** Length of the completion series, in hour-aligned buckets ending in the current hour. */
    static final int WINDOW_HOURS = 24;

    private static final String UNKNOWN = "unknown";

    @Inject
    EntityManager entityManager;
    /**
     * {@code quarkus.*} is Quarkus's own namespace, not ours, so the two properties below are read
     * through {@link Config} rather than claimed by a {@code @ConfigMapping} of our own.
     */
    @Inject
    Config config;

    private Instant startedAt;

    void start(@Observes StartupEvent event) {
        startedAt = Instant.now();
    }

    public AdminStatsView.SystemInfo system() {
        return new AdminStatsView.SystemInfo(startedAt, version(), environment());
    }

    /**
     * Terminal-state counts for each of the last {@value #WINDOW_HOURS} hours, oldest first.
     *
     * <p>Bucketing happens here rather than in the query: HQL has no portable hour truncation, and
     * Hibernate owns the {@code Instant} to column conversion, so reading the raw completions back is
     * the only way to bucket that does not depend on the dialect or on how the column stores its zone.
     * The window bounds the scan at a day of completions.
     */
    public List<AdminStatsView.Hourly> completionsLast24h() {
        var from = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(WINDOW_HOURS - 1, ChronoUnit.HOURS);
        return bucket(completionsSince(from), from);
    }

    public Map<TaskState, Long> tasksByState() {
        return entityManager
                .createQuery("select t.state, count(t.id) from Task t group by t.state", Object[].class)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (TaskState) row[0], row -> (Long) row[1]));
    }

    private List<Completion> completionsSince(Instant from) {
        return entityManager
                .createQuery("select j.completedAt, j.state from Job j where j.completedAt >= ?1",
                        Object[].class)
                .setParameter(1, from)
                .getResultList().stream()
                .map(row -> new Completion((Instant) row[0], (JobState) row[1]))
                .toList();
    }

    /** Zero-filled buckets covering the {@value #WINDOW_HOURS} hours from {@code from}, oldest first. */
    static List<AdminStatsView.Hourly> bucket(List<Completion> completions, Instant from) {
        // Keyed by ordinal because the second index is a state and the counters are per-bucket dense.
        var counts = new long[WINDOW_HOURS][JobState.values().length];
        for (var completion : completions) {
            // between() truncates towards zero, so a completion in the hour before the window would
            // otherwise land in the first bucket rather than outside it.
            if (completion.at().isBefore(from)) {
                continue;
            }
            var index = ChronoUnit.HOURS.between(from, completion.at());
            if (index < WINDOW_HOURS) {
                counts[(int) index][completion.state().ordinal()]++;
            }
        }
        return IntStream.range(0, WINDOW_HOURS)
                .mapToObj(hour -> new AdminStatsView.Hourly(
                        from.plus(hour, ChronoUnit.HOURS),
                        counts[hour][JobState.SUCCESS.ordinal()],
                        counts[hour][JobState.FAILED.ordinal()],
                        counts[hour][JobState.CANCELLED.ordinal()]))
                .toList();
    }

    private String version() {
        return config.getOptionalValue("quarkus.application.version", String.class).orElse(UNKNOWN);
    }

    // Profiles come back with the last one set first, which is the one that wins.
    private static String environment() {
        var profiles = ConfigUtils.getProfiles();
        return profiles.isEmpty() ? UNKNOWN : profiles.getFirst();
    }

    /** One job's terminal state and the moment it reached it. */
    record Completion(Instant at, JobState state) {
    }
}
