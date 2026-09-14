package io.ib67.prts.agent.acp.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.agent.acp.AcpConfig;
import io.ib67.prts.agent.acp.AgentTranscript;
import io.ib67.prts.agent.acp.entity.AgentSession;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.agent.AgentEventPage;
import io.ib67.prts.dto.agent.AgentSessionView;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.entity.ProjectRole;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * REST resource for querying recorded ACP sessions and frame transcripts.
 */
@Path("/project/{projectId}/job/{jobId}/agent")
@Produces(MediaType.APPLICATION_JSON)
public class AgentResource {

    @Inject
    AgentTranscript transcript;
    @Inject
    JobService jobService;
    @Inject
    AcpConfig acpConfig;

    /** Lists all ACP sessions for a job, root session first. */
    @GET
    @Path("/session")
    @Transactional
    @RequirePermission(value = Perm.JOB_AGENT_READ, defaultRole = ProjectRole.VIEWER)
    public List<AgentSessionView> listSessions(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("jobId") UUID jobId) {
        jobService.requireInProject(projectId, jobId);
        return AgentSession.listByJob(jobId).stream().map(AgentSessionView::of).toList();
    }

    /** Returns a paginated slice of recorded frames for a session in sequence order. */
    @GET
    @Path("/session/{sessionId}/event")
    @Transactional
    @RequirePermission(value = Perm.JOB_AGENT_READ, defaultRole = ProjectRole.VIEWER)
    public AgentEventPage listEvents(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("jobId") UUID jobId,
            @PathParam("sessionId") UUID sessionId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, acpConfig.maxPageSize());
        return transcript.viewOf(projectId, jobId, sessionId, Pages.clampOffset(offset, window), window);
    }
}
