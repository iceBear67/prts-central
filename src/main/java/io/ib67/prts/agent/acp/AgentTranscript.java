package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import io.ib67.prts.agent.acp.entity.AgentDirection;
import io.ib67.prts.agent.acp.entity.AgentEvent;
import io.ib67.prts.agent.acp.entity.AgentSession;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.agent.AgentEventView;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence service for ACP sessions and frames.
 *
 * <p>Decoupled from live routing in {@link AgentService}. Each operation runs in an independent
 * transaction to keep DB transactions separate from WebSocket RPC boundaries.
 */
@ApplicationScoped
public class AgentTranscript {

    /**
     * Attachment result containing the job's project ID and root session row ID.
     */
    public record Attachment(UUID projectId, UUID rootSession) {
        public Attachment {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(rootSession, "rootSession");
        }
    }

    /**
     * Validates worker assignment and creates or resumes the job's root session.
     *
     * @throws NoSuchElementException if the job does not exist
     * @throws IllegalStateException  if the job is finished or not assigned to the worker
     */
    public Attachment openRoot(UUID workerId, UUID jobId, String acpSessionId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var job = Job.<Job>findByIdOptional(jobId)
                    .orElseThrow(() -> new NoSuchElementException("no such job: " + jobId));
            if (job.isCompleted()) {
                throw new IllegalStateException("job already " + job.getState() + ": " + jobId);
            }
            // Prevent workers from attaching to jobs not assigned to them.
            if (!workerId.equals(job.getWorker())) {
                throw new IllegalStateException("job " + jobId + " is not on worker " + workerId);
            }
            return new Attachment(
                    job.getProject().getId(), open(job, acpSessionId, null).getId());
        });
    }

    /**
     * Creates a child session parented to the root session.
     */
    public UUID openChild(UUID rootId, String acpSessionId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var root = AgentSession.<AgentSession>findByIdOptional(rootId).orElseThrow(
                    () -> new NoSuchElementException("no such agent session: " + rootId));
            return open(root.getJob(), acpSessionId, root).getId();
        });
    }

    private static AgentSession open(Job job, String acpSessionId, @Nullable AgentSession parent) {
        var existing = AgentSession.findInJob(job.getId(), acpSessionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        var session = AgentSession.builder()
                .job(job)
                .acpSessionId(acpSessionId)
                .parent(parent)
                .build();
        session.persist();
        return session;
    }

    /**
     * Persists an ACP frame. Uses an entity reference to avoid loading the session on high-frequency chunk writes.
     */
    public void record(
            UUID sessionId,
            AgentDirection direction,
            @Nullable String method,
            @Nullable UUID actor,
            JsonNode frame) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frame, "frame");
        QuarkusTransaction.requiringNew().run(() -> AgentEvent.builder()
                .agentSession(AgentEvent.getEntityManager().getReference(AgentSession.class, sessionId))
                .direction(direction)
                .method(method)
                .actor(actor)
                .frame(frame)
                .build()
                .persist());
    }

    /** Closes all open sessions for a job. */
    public void closeAll(UUID jobId) {
        QuarkusTransaction.requiringNew().run(() -> AgentSession.closeOpenByJob(jobId, Instant.now()));
    }

    /** Returns all sessions for a job mapped by their ACP session ID. */
    public Map<String, UUID> sessionsOf(UUID jobId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var sessions = new LinkedHashMap<String, UUID>();
            AgentSession.listByJob(jobId)
                    .forEach(session -> sessions.put(session.getAcpSessionId(), session.getId()));
            return sessions;
        });
    }

    /** Returns a paginated view of session events, batch-resolving actor user info. */
    public Page<AgentEventView> viewOf(
            UUID projectId, UUID jobId, UUID sessionId, int offset, int length) {
        AgentSession.findInProject(projectId, jobId, sessionId).orElseThrow(() -> new NotFoundException(
                "no such agent session in job " + jobId + ": " + sessionId));
        var events = AgentEvent.listBySession(sessionId, offset, length);
        var users = User.mapByIds(events.stream()
                .map(AgentEvent::getActor)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return new Page<>(
                events.stream()
                        .map(event -> new AgentEventView(
                                event.getId(),
                                event.getCreatedAt(),
                                event.getDirection(),
                                event.getMethod(),
                                event.getActor() == null
                                        ? null
                                        : UserInfo.of(event.getActor(), users.get(event.getActor())),
                                event.getFrame()))
                        .toList(),
                offset,
                length,
                AgentEvent.countBySession(sessionId));
    }
}
