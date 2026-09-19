package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live agent channels, one per job, and their lifecycle.
 *
 * <p>A channel leaves the map before it is discarded, and both happen under the same lock a viewer is
 * admitted under, so no viewer can join a channel that has already closed its sockets.
 */
@ApplicationScoped
class AgentChannels {

    private static final Logger LOG = Logger.getLogger(AgentChannels.class);

    @Inject
    AgentTranscript transcript;
    @Inject
    AcpConfig acpConfig;

    private final Map<UUID, AgentChannel> channels = new ConcurrentHashMap<>();

    /**
     * Opens a job's channel, discarding whatever it replaces, and resumes the sessions already stored
     * for that job.
     */
    void open(UUID workerId, UUID jobId, JsonNode initialize, String acpSessionId) {
        var attachment = transcript.openRoot(workerId, jobId, acpSessionId);
        var channel = new AgentChannel(
                jobId, attachment.projectId(), workerId, initialize, acpSessionId,
                attachment.rootSession());
        channel.putSessions(transcript.sessionsOf(jobId));

        AgentChannel displaced;
        synchronized (this) {
            displaced = channels.put(jobId, channel);
        }
        if (displaced != null) {
            displaced.discard("the agent re-attached");
        }
        LOG.infof("job %s: its agent attached on worker %s, session %s", jobId, workerId, acpSessionId);
    }

    @Nullable
    AgentChannel get(UUID jobId) {
        return channels.get(jobId);
    }

    /**
     * @throws IllegalStateException if the job has no channel, or holds one on another worker
     */
    AgentChannel require(UUID workerId, UUID jobId) {
        var channel = channels.get(jobId);
        if (channel == null) {
            throw new IllegalStateException("no agent is attached for job " + jobId);
        }
        if (!channel.workerId().equals(workerId)) {
            throw new IllegalStateException("job " + jobId + " is not on worker " + workerId);
        }
        return channel;
    }

    /**
     * Admits a viewer to a job's channel.
     *
     * @throws NoSuchElementException if no active channel exists for the job/project
     * @throws IllegalStateException  if the channel reached its viewer limit
     */
    void attach(UUID projectId, UUID jobId, AgentViewer viewer) {
        synchronized (this) {
            var channel = channels.get(jobId);
            if (channel == null || channel.isClosed()) {
                throw new NoSuchElementException("job " + jobId + " has no live agent session");
            }
            if (!channel.projectId().equals(projectId)) {
                throw new NoSuchElementException("no such job in project " + projectId + ": " + jobId);
            }
            if (channel.viewerCount() >= acpConfig.maxViewersPerJob()) {
                throw new IllegalStateException(
                        "job " + jobId + " is already watched by " + channel.viewerCount() + " viewers");
            }
            channel.addViewer(viewer);
        }
    }

    /**
     * Closes a job's channel and the sessions it left behind.
     */
    void close(UUID jobId, String reason) {
        AgentChannel channel;
        synchronized (this) {
            channel = channels.remove(jobId);
        }
        if (channel == null) {
            return;
        }
        channel.discard(reason);
        try {
            transcript.closeAll(jobId);
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot close the agent sessions of job %s", jobId);
        }
        LOG.infof("job %s: its agent channel closed, %s", jobId, reason);
    }

    /**
     * Closes every channel a worker was hosting, because the worker itself is gone.
     */
    void closeByWorker(UUID workerId) {
        channels.values().stream()
                .filter(channel -> channel.workerId().equals(workerId))
                .map(AgentChannel::jobId)
                .toList()
                .forEach(jobId -> close(jobId, "the worker disconnected"));
    }
}
