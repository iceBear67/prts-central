package io.ib67.prts.project;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.storage.StorageConfig;
import io.ib67.prts.storage.StorageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Brokers a worker's artifact straight to S3: reserves room against the job's quota, hands out a
 * presigned PUT, and records an {@link Artifact} once the object has landed. The quota spans what has
 * landed (rows) and what is still in flight ({@link #pending}), which is why both halves live here.
 */
@ApplicationScoped
public class ArtifactUploadService {
    static final Duration PENDING_TTL_GRACE = Duration.ofMinutes(5);
    private static final Logger LOG = Logger.getLogger(ArtifactUploadService.class);

    private Cache<UUID, PendingUpload> pending;
    private final AtomicInteger pendingCount = new AtomicInteger();
    /** Uploads currently being promoted, so the sweeper and the removal listener never overlap. */
    private final Set<UUID> promoting = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "prts-artifact-upload-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    StorageService storageService;
    @Inject
    StorageConfig storageConfig;

    @PostConstruct
    void initPending() {
        pending = Caffeine.newBuilder()
                .expireAfterWrite(storageConfig.presignDuration().plus(PENDING_TTL_GRACE))
                .scheduler(Scheduler.systemScheduler())
                .removalListener(this::onPendingRemoved)
                .build();
        sweeper.scheduleWithFixedDelay(this::sweep, 2, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopSweeper() {
        sweeper.shutdownNow();
    }

    public ClientboundMessage.PresignedUpload begin(
            UUID workerId, UUID jobId, String name, long sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("artifact size must be >= 0");
        }
        var maxFile = storageConfig.maxFileSize().asLongValue();
        if (sizeBytes > maxFile) {
            throw new IllegalStateException("artifact exceeds max file size: " + sizeBytes + " > " + maxFile);
        }
        reserveSlot();
        var uploadId = UUID.randomUUID();
        var artifactName = artifactName(name);
        var objectKey = destKey(jobId, uploadId, keyFileName(artifactName));
        var expiresAt = Instant.now().plus(storageConfig.presignDuration());
        var session = new PendingUpload(uploadId, jobId, workerId, artifactName, objectKey, sizeBytes, expiresAt);
        // Flipped inside the transaction, not after it: a commit failure afterwards would otherwise
        // have both this method and the removal listener decrement the slot count.
        var stored = new AtomicBoolean();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                reserve(session);
                stored.set(true);
            });
            var put = storageService.presignPut(objectKey, sizeBytes);
            return new ClientboundMessage.PresignedUpload(
                    uploadId,
                    jobId,
                    artifactName,
                    objectKey,
                    put.url(),
                    put.method(),
                    put.expiresAt(),
                    sizeBytes);
        } catch (RuntimeException e) {
            if (stored.get()) {
                pending.invalidate(uploadId);
            } else {
                pendingCount.decrementAndGet();
            }
            throw e;
        }
    }

    private void reserveSlot() {
        var max = storageConfig.maxPendingUploads();
        while (true) {
            var current = pendingCount.get();
            if (current >= max) {
                throw new IllegalStateException("too many pending uploads: " + current + " >= " + max);
            }
            if (pendingCount.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    /**
     * Checks the upload against the job's quota and puts it in {@link #pending} while the job row is
     * still locked, so two uploads cannot both fit into the same remaining room.
     */
    private void reserve(PendingUpload session) {
        lockAssignedOpen(session.jobId(), session.workerId());
        var max = storageConfig.maxJobSize().asLongValue();
        var used = Artifact.listByJob(session.jobId()).stream().mapToLong(Artifact::getSizeBytes).sum();
        var reserved = reservedBytes(session.jobId());
        if (used > max || reserved > max - used || session.sizeBytes() > max - used - reserved) {
            throw new IllegalStateException(
                    "job artifact quota exceeded: " + (used + reserved + session.sizeBytes()) + " > " + max);
        }
        pending.put(session.uploadId(), session);
    }

    private long reservedBytes(UUID jobId) {
        long reserved = 0;
        for (var session : pending.asMap().values()) {
            if (jobId.equals(session.jobId())) {
                reserved += session.sizeBytes();
            }
        }
        return reserved;
    }

    /** Idempotent on the object key: the sweeper and the removal listener may both see an upload land. */
    private void record(PendingUpload session) {
        QuarkusTransaction.requiringNew().run(() -> {
            var job = lockAssignedOpen(session.jobId(), session.workerId());
            if (Artifact.count("objectKey", session.objectKey()) > 0) {
                return;
            }
            Artifact.builder()
                    .job(job)
                    .name(session.name())
                    .objectKey(session.objectKey())
                    .sizeBytes(session.sizeBytes())
                    .build()
                    .persist();
        });
    }

    /** Only the worker the job was given may attach to it, and only while it is still running. */
    private static Job lockAssignedOpen(UUID jobId, UUID workerId) {
        var job = Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE);
        if (job == null) {
            throw new NoSuchElementException("no such job: " + jobId);
        }
        if (job.isCompleted()) {
            throw new IllegalStateException("job already completed: " + jobId);
        }
        if (!workerId.equals(job.getWorker())) {
            throw new IllegalStateException("job not assigned to this worker: " + jobId);
        }
        return job;
    }

    private void sweep() {
        try {
            for (var session : List.copyOf(pending.asMap().values())) {
                claimed(session, this::tryPromote);
            }
        } catch (RuntimeException e) {
            LOG.error("artifact upload sweeper failed", e);
        }
    }

    /**
     * Runs {@code work} only if nothing else is on this upload. The sweeper and the removal listener
     * (on the common pool) both HEAD-then-insert, and could otherwise promote it twice — or delete
     * the object after the other thread persisted it.
     */
    private void claimed(PendingUpload session, Consumer<PendingUpload> work) {
        if (!promoting.add(session.uploadId())) {
            return;
        }
        try {
            work.accept(session);
        } finally {
            promoting.remove(session.uploadId());
        }
    }

    private void tryPromote(PendingUpload session) {
        var size = storageService.findObjectSize(session.objectKey());
        if (size.isEmpty()) {
            return;
        }
        if (size.getAsLong() != session.sizeBytes()) {
            LOG.warnf("pending artifact upload %s has unexpected size %s, expected %s",
                    session.uploadId(), size.getAsLong(), session.sizeBytes());
            discard(session);
            return;
        }
        try {
            record(session);
            pending.asMap().remove(session.uploadId());
        } catch (NoSuchElementException | IllegalStateException e) {
            LOG.infof("dropping pending artifact upload %s: %s", session.uploadId(), e.getMessage());
            discard(session);
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot promote artifact upload %s", session.uploadId());
        }
    }

    private void tryPromoteOrDelete(PendingUpload session) {
        try {
            var size = storageService.findObjectSize(session.objectKey());
            if (size.isPresent() && size.getAsLong() == session.sizeBytes()) {
                record(session);
                return;
            }
        } catch (NoSuchElementException | IllegalStateException e) {
            LOG.infof("dropping expired artifact upload %s: %s", session.uploadId(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot promote expired artifact upload %s", session.uploadId());
        }
        storageService.deleteQuietly(session.objectKey());
    }

    private void discard(PendingUpload session) {
        pending.asMap().remove(session.uploadId());
        storageService.deleteQuietly(session.objectKey());
    }

    private void onPendingRemoved(UUID uploadId, PendingUpload session, RemovalCause cause) {
        pendingCount.decrementAndGet();
        if (session != null && cause.wasEvicted()) {
            LOG.infof("pending artifact upload %s expired (%s)", uploadId, cause);
            claimed(session, this::tryPromoteOrDelete);
        }
    }

    static String destKey(UUID jobId, UUID uploadId, String fileName) {
        return "jobs/" + jobId + "/" + uploadId + "/" + fileName;
    }

    /**
     * The last segment of whatever the worker called the file. Not {@code Path.getFileName()}: the
     * string is a path on the worker's filesystem, not ours, so either separator may be in play.
     */
    static String artifactName(String suggested) {
        if (suggested == null) {
            return "artifact.bin";
        }
        var name = suggested.substring(Math.max(suggested.lastIndexOf('/'), suggested.lastIndexOf('\\')) + 1);
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "artifact.bin";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    /**
     * What of an {@link #artifactName} may sit in an object key. The name is already a basename, so only
     * the character set and the length are left to settle; cut from the front so the extension survives.
     */
    static String keyFileName(String artifactName) {
        var name = artifactName.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private record PendingUpload(
            UUID uploadId,
            UUID jobId,
            UUID workerId,
            String name,
            String objectKey,
            long sizeBytes,
            Instant expiresAt
    ) {
        private PendingUpload {
            Objects.requireNonNull(uploadId, "uploadId");
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
