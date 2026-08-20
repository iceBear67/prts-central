package io.ib67.prts.project;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.storage.StorageConfig;
import io.ib67.prts.storage.StorageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ArtifactUploadService {
    static final Duration PENDING_TTL_GRACE = Duration.ofMinutes(5);
    private static final Logger LOG = Logger.getLogger(ArtifactUploadService.class);

    private Cache<UUID, PendingUpload> pending;
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "prts-artifact-upload-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    JobService jobService;
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
            UUID workerId, UUID jobId, String suggestedFileName, long sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("artifact size must be >= 0");
        }
        var maxFile = storageConfig.maxFileSize().asLongValue();
        if (sizeBytes > maxFile) {
            throw new IllegalStateException("artifact exceeds max file size: " + sizeBytes + " > " + maxFile);
        }
        reserveSlot();
        var uploadId = UUID.randomUUID();
        var fileName = sanitizeFileName(suggestedFileName);
        var objectKey = destKey(jobId, uploadId, fileName);
        var expiresAt = Instant.now().plus(storageConfig.presignDuration());
        var session = new PendingUpload(uploadId, jobId, workerId, objectKey, sizeBytes, expiresAt);
        var stored = false;
        try {
            jobService.assertCanStoreArtifact(
                    jobId,
                    workerId,
                    sizeBytes,
                    () -> reservedBytes(jobId),
                    storageConfig.maxJobSize().asLongValue(),
                    () -> pending.put(uploadId, session));
            stored = true;
            var put = storageService.presignPut(objectKey, sizeBytes);
            return new ClientboundMessage.PresignedUpload(
                    uploadId,
                    jobId,
                    objectKey,
                    put.url(),
                    put.method(),
                    put.expiresAt(),
                    sizeBytes);
        } catch (RuntimeException e) {
            if (stored) {
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

    private long reservedBytes(UUID jobId) {
        long reserved = 0;
        for (var session : pending.asMap().values()) {
            if (jobId.equals(session.jobId())) {
                reserved += session.sizeBytes();
            }
        }
        return reserved;
    }

    private void sweep() {
        try {
            for (var session : List.copyOf(pending.asMap().values())) {
                tryPromote(session);
            }
        } catch (RuntimeException e) {
            LOG.error("artifact upload sweeper failed", e);
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
            jobService.addArtifact(session.jobId(), session.workerId(), session.objectKey(), session.sizeBytes());
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
                jobService.addArtifact(session.jobId(), session.workerId(), session.objectKey(), session.sizeBytes());
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
            tryPromoteOrDelete(session);
        }
    }

    static String destKey(UUID jobId, UUID uploadId, String fileName) {
        return "jobs/" + jobId + "/" + uploadId + "/" + fileName;
    }

    static String sanitizeFileName(String suggested) {
        if (suggested == null || suggested.isBlank()) {
            return "artifact.bin";
        }
        var name = suggested.replace('\\', '/');
        var slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "artifact.bin";
        }
        if (name.length() > 200) {
            name = name.substring(name.length() - 200);
        }
        return name;
    }

    private record PendingUpload(
            UUID uploadId,
            UUID jobId,
            UUID workerId,
            String objectKey,
            long sizeBytes,
            Instant expiresAt
    ) {
    }
}
