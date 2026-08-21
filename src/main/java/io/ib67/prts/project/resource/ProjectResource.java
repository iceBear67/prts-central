package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.CreateJobRequest;
import io.ib67.prts.dto.JobView;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.ProjectRole;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

@Path("/project")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectResource {
    @Inject
    JobService jobService;

}
