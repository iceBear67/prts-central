package io.ib67.prts.project.resource;

import io.ib67.prts.agent.job.JobSpecTemplate;
import io.ib67.prts.dto.JobLogPage;
import io.ib67.prts.dto.JobSpecTemplateView;
import io.ib67.prts.dto.JobView;
import io.ib67.prts.dto.PresignedUrlView;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobService;
import io.ib67.prts.storage.StorageService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/job")
@Produces(MediaType.APPLICATION_JSON)
public class JobResource {
    @Inject
    JobService jobService;
    @Inject
    StorageService storageService;
    @Inject
    JobConfig jobConfig;

    @GET
    @Path("/template")
    @Transactional
    public List<JobSpecTemplateView> listTemplates() {
        return JobSpecTemplate.listAllFetched().stream()
                .map(JobSpecTemplateView::of)
                .toList();
    }

    @GET
    @Path("/template/{id}")
    @Transactional
    public JobSpecTemplateView getTemplate(@PathParam("id") UUID id) {
        return JobSpecTemplate.findByIdFetched(id)
                .map(JobSpecTemplateView::of)
                .orElseThrow(NotFoundException::new);
    }

    @GET
    @Path("/{id}")
    @Transactional
    public JobView getJob(@PathParam("id") UUID id) {
        var job = jobService.findById(id).orElseThrow(NotFoundException::new);
        return JobView.of(job, jobService.listArtifacts(id));
    }

    @GET
    @Path("/artifact/{id}")
    @Transactional
    public PresignedUrlView getArtifactUrl(@PathParam("id") UUID id) {
        var artifact = jobService.findArtifact(id).orElseThrow(NotFoundException::new);
        return PresignedUrlView.of(storageService.presignGet(artifact.getObjectKey()));
    }

    @GET
    @Path("/{id}/log")
    @Transactional
    public JobLogPage getJobLogs(
            @PathParam("id") UUID id,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") Integer size) {
        jobService.findById(id).orElseThrow(NotFoundException::new);
        var pageSize = clampPageSize(size);
        var pageIndex = Math.max(page, 0);
        return JobLogPage.of(
                jobService.listLogs(id, pageIndex, pageSize),
                pageIndex,
                pageSize,
                jobService.countLogs(id));
    }

    private int clampPageSize(Integer size) {
        var max = jobConfig.log().maxPageSize();
        if (size == null || size <= 0) {
            return max;
        }
        return Math.min(size, max);
    }
}
