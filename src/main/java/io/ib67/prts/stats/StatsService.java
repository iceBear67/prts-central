package io.ib67.prts.stats;

import io.ib67.prts.dto.DailyCount;
import io.ib67.prts.dto.HourlyCount;
import io.ib67.prts.dto.admin.AdminStatsView;
import io.ib67.prts.job.entity.JobState;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.Config;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The parts of the statistics endpoints their resources cannot reach on their own: this process's
 * identity, and the completion series that belong to no entity because only a dashboard asks for them.
 *
 * <p>Serves both {@code GET /admin/stats} and {@code GET /project/{projectId}/stats} — the second is
 * the same aggregation with a project predicate, so every series here takes a nullable project.
 */
@ApplicationScoped
public class StatsService {

    /** Length of the short hourly series, in hour-aligned buckets ending in the current hour. */
    public static final int WINDOW_HOURS = 24;
    /**
     * Length of the long hourly series: 30 days of hours.
     *
     * <p>A day is too coarse a cell for a heatmap drawn across the width of a dashboard, and 24 of
     * them is one day rather than a window to scan — so the same series is published at both widths
     * and the client picks by how much room it has.
     */
    public static final int WINDOW_HOURS_LONG = 24 * 30;
    /** Length of the daily series, in UTC days ending today. */
    public static final int WINDOW_DAYS = 90;

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
     * Every terminal completion inside the longest window the series below cover.
     *
     * <p>Read once and bucketed as many ways as a dashboard asks for: the daily series spans the
     * others, so a second query for the hours inside it would scan rows this one already holds — and
     * two reads at different instants can disagree about the hour they share.
     *
     * <p>Bucketing happens in Java rather than in the query: HQL has no portable time truncation, and
     * Hibernate owns the {@code Instant} to column conversion, so reading the raw completions back is
     * the only way to bucket that does not depend on the dialect or on how the column stores its zone.
     * The window bounds the scan.
     */
    public List<Completion> completions(@Nullable UUID projectId) {
        return completionsSince(startOf(DailyCount.UNIT, WINDOW_DAYS), projectId);
    }

    /** The last {@code hours} hour-aligned buckets, oldest first, zero-filled. */
    public static List<HourlyCount> hourlyLast(List<Completion> completions, int hours) {
        return hourly(completions, startOf(HourlyCount.UNIT, hours), hours);
    }

    /** The same series a day at a time, for the calendar heatmap. Same bucketing, coarser unit. */
    public static List<DailyCount> dailyLast(List<Completion> completions) {
        return daily(completions, startOf(DailyCount.UNIT, WINDOW_DAYS));
    }

    public static List<HourlyCount> hourly(
            List<Completion> completions, Instant from, int hours) {
        var counts = bucket(completions, from, HourlyCount.UNIT, hours);
        return IntStream.range(0, hours)
                .mapToObj(hour -> new HourlyCount(
                        from.plus(hour, HourlyCount.UNIT),
                        counts[hour][JobState.SUCCESS.ordinal()],
                        counts[hour][JobState.FAILED.ordinal()],
                        counts[hour][JobState.CANCELLED.ordinal()]))
                .toList();
    }

    public static List<DailyCount> daily(List<Completion> completions, Instant from) {
        var counts = bucket(completions, from, DailyCount.UNIT, WINDOW_DAYS);
        return IntStream.range(0, WINDOW_DAYS)
                .mapToObj(day -> new DailyCount(
                        from.plus(day, DailyCount.UNIT).atZone(ZoneOffset.UTC).toLocalDate(),
                        counts[day][JobState.SUCCESS.ordinal()],
                        counts[day][JobState.FAILED.ordinal()],
                        counts[day][JobState.CANCELLED.ordinal()]))
                .toList();
    }

    /** The first bucket's start: {@code count} units back from the one the current instant is in. */
    private static Instant startOf(ChronoUnit unit, int count) {
        return Instant.now().truncatedTo(unit).minus(count - 1L, unit);
    }

    /** Zero-filled counters, {@code count} buckets of {@code unit} from {@code from}, oldest first. */
    private static long[][] bucket(
            List<Completion> completions, Instant from, ChronoUnit unit, int count) {
        // Keyed by ordinal because the second index is a state and the counters are per-bucket dense.
        var counts = new long[count][JobState.values().length];
        for (var completion : completions) {
            // between() truncates towards zero, so a completion in the unit before the window would
            // otherwise land in the first bucket rather than outside it.
            if (completion.at().isBefore(from)) {
                continue;
            }
            var index = unit.between(from, completion.at());
            if (index < count) {
                counts[(int) index][completion.state().ordinal()]++;
            }
        }
        return counts;
    }

    private List<Completion> completionsSince(Instant from, @Nullable UUID projectId) {
        var query = entityManager
                .createQuery("select j.completedAt, j.state from Job j where j.completedAt >= :from"
                        + (projectId == null ? "" : " and j.project.id = :project"), Object[].class)
                .setParameter("from", from);
        if (projectId != null) {
            query.setParameter("project", projectId);
        }
        return query.getResultList().stream()
                .map(row -> new Completion((Instant) row[0], (JobState) row[1]))
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
    public record Completion(Instant at, JobState state) {
    }
}
