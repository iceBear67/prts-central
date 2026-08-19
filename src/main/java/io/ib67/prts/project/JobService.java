package io.ib67.prts.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JobService {

    @Inject
    ProjectService projectService;

    public Optional<Job> findById(UUID id) {
        return Job.findByIdOptional(id);
    }

    public Job require(UUID id) {
        return Job.<Job>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such job: " + id));
    }

    public List<Job> listByProject(UUID projectId) {
        projectService.require(projectId);
        return Job.listByProject(projectId);
    }

    @Transactional
    public Job create(UUID projectId, UUID runner) {
        var job = Job.builder()
                .project(projectService.require(projectId))
                .runner(runner)
                .build();
        job.persist();
        return job;
    }

    @Transactional
    public Job assignRunner(UUID jobId, UUID runner) {
        var job = requireOpen(jobId);
        job.setRunner(runner);
        return job;
    }

    @Transactional
    public Job complete(UUID jobId, boolean success) {
        var job = requireOpen(jobId);
        job.complete(success);
        return job;
    }

    @Transactional
    public boolean delete(UUID jobId) {
        return Job.deleteById(jobId);
    }

    public List<JobLog> listLogs(UUID jobId) {
        require(jobId);
        return JobLog.listByJob(jobId);
    }

    @Transactional
    public JobLog appendLog(UUID jobId, String topic, String message, Boolean error) {
        var log = JobLog.builder()
                .job(requireOpen(jobId))
                .topic(topic)
                .message(message)
                .error(error)
                .build();
        log.persist();
        return log;
    }

    public List<Artifact> listArtifacts(UUID jobId) {
        require(jobId);
        return Artifact.listByJob(jobId);
    }

    public Optional<Artifact> findArtifact(UUID artifactId) {
        return Artifact.findByIdOptional(artifactId);
    }

    @Transactional
    public Artifact addArtifact(UUID jobId, String objectKey, long sizeBytes) {
        var artifact = Artifact.builder()
                .job(require(jobId))
                .objectKey(objectKey)
                .sizeBytes(sizeBytes)
                .build();
        artifact.persist();
        return artifact;
    }

    @Transactional
    public boolean deleteArtifact(UUID artifactId) {
        return Artifact.deleteById(artifactId);
    }

    private Job requireOpen(UUID jobId) {
        var job = require(jobId);
        if (job.isCompleted()) {
            throw new IllegalStateException("job already completed: " + jobId);
        }
        return job;
    }
}
