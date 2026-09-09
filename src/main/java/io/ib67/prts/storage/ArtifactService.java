package io.ib67.prts.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.dto.ArtifactUsage;
import io.ib67.prts.project.entity.Artifact;
import io.ib67.prts.project.entity.Job;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.NotFoundException;
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
 * Coordinates worker artifact uploads directly to object storage.
 *
 * <p>Tracks in-flight uploads against job storage quotas, issues presigned upload URLs,
 * and records completed artifacts once uploaded.
 */
@ApplicationScoped
public class ArtifactService {
    static final Duration PENDING_TTL_GRACE = Duration.ofMinutes(5);
    private static final Logger LOG = Logger.getLogger(ArtifactService.class);

    private Cache<UUID, PendingUpload> pending;
    private final AtomicInteger pendingCount = new AtomicInteger();
    /** Upload IDs currently being processed to prevent concurrent promotion or cleanup. */
    private final Set<UUID> promoting = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "prts-artifact-upload-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    EntityManager entityManager;
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

    /**
     * Deletes an artifact database record and its underlying storage object.
     *
     * <p>The database row is deleted in a new transaction before deleting the storage object, ensuring
     * no presigned download URLs can be issued for a deleted artifact.
     */
    public void delete(UUID projectId, UUID artifactId) {
        var objectKey = QuarkusTransaction.requiringNew().call(() -> {
            var artifact = Artifact.findInProject(projectId, artifactId)
                    .orElseThrow(() -> new NotFoundException(
                            "no such artifact in project " + projectId + ": " + artifactId));
            var key = artifact.getObjectKey();
            artifact.delete();
            return key;
        });
        storageService.deleteQuietly(objectKey);
    }

    /** Counts every artifact stored across all projects, and the bytes they occupy. */
    public ArtifactUsage stored() {
        // sum() returns null when no rows exist, whereas count() returns 0.
        var row = (Object[]) entityManager
                .createQuery("select count(a), sum(a.sizeBytes) from Artifact a")
                .getSingleResult();
        var bytes = (Long) row[1];
        return new ArtifactUsage((long) row[0], bytes == null ? 0 : bytes);
    }

    /** Cancels in-flight uploads and deletes partial objects for a job. */
    public void discardPendingOf(UUID jobId) {
        for (var session : List.copyOf(pending.asMap().values())) {
            if (jobId.equals(session.jobId())) {
                claimed(session, this::discard);
            }
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

    /** Validates the upload against the job's remaining quota under a pessimistic lock. */
    private void reserve(PendingUpload session) {
        lockAssignedOpen(session.jobId(), session.workerId());
        var artifacts = Artifact.listByJob(session.jobId());
        var reserved = reservedFor(session.jobId());

        var maxCount = storageConfig.maxJobArtifacts();
        var count = artifacts.size() + reserved.count();
        if (count >= maxCount) {
            throw new IllegalStateException(
                    "job artifact count exceeded: " + (count + 1) + " > " + maxCount);
        }

        var max = storageConfig.maxJobSize().asLongValue();
        var used = artifacts.stream().mapToLong(Artifact::getSizeBytes).sum();
        if (used > max || reserved.bytes() > max - used || session.sizeBytes() > max - used - reserved.bytes()) {
            throw new IllegalStateException(
                    "job artifact quota exceeded: " + (used + reserved.bytes() + session.sizeBytes()) + " > " + max);
        }
        pending.put(session.uploadId(), session);
    }

    /** Active quota reservations for in-flight uploads. */
    private ArtifactUsage reservedFor(UUID jobId) {
        var count = 0L;
        long bytes = 0;
        for (var session : pending.asMap().values()) {
            if (jobId.equals(session.jobId())) {
                count++;
                bytes += session.sizeBytes();
            }
        }
        return new ArtifactUsage(count, bytes);
    }

    /** Records an uploaded artifact in the database. */
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

    /** Acquires a pessimistic lock on an open job assigned to the specified worker. */
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

    /** Executes work on an upload session ensuring exclusive access. */
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

    /** Extracts a sanitized file name from the worker's suggested file path. */
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

    /** Sanitizes and truncates a file name for use in storage object keys. */
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
