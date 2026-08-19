package io.ib67.prts.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.*;

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
    public Job create(UUID projectId, UUID worker) {
        var job = Job.builder()
                .project(projectService.require(projectId))
                .worker(worker)
                .build();
        job.persist();
        return job;
    }

    @Transactional
    public Job assignWorker(UUID jobId, UUID worker) {
        var job = requireOpen(jobId);
        job.setWorker(worker);
        return job;
    }

    @Transactional
    public Optional<Job> applyState(UUID jobId, JobState state) {
        Objects.requireNonNull(state, "jobState");
        var found = Job.<Job>findByIdOptional(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var job = found.get();
        if (job.isCompleted() || job.getState() == state) {
            return found;
        }
        var previous = job.getState();
        job.transitionTo(state);
        persistLog(job, "state", previous + " -> " + state, state == JobState.FAILED);
        return found;
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
        return persistLog(requireOpen(jobId), topic, message, error);
    }

    private JobLog persistLog(Job job, String topic, String message, Boolean error) {
        var log = JobLog.builder()
                .job(job)
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
