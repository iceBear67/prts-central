package io.ib67.prts.project;

import io.ib67.prts.project.entity.Project;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {

    public Optional<Project> findById(UUID id) {
        return Project.findByIdOptional(id);
    }

    public List<Project> listAll() {
        return Project.listAll();
    }

    public Project require(UUID id) {
        return Project.<Project>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such project: " + id));
    }

    @Transactional
    public Project create(String name) {
        var project = Project.builder().name(name).build();
        project.persist();
        return project;
    }

    @Transactional
    public Project rename(UUID id, String name) {
        var project = require(id);
        project.setName(name);
        return project;
    }

    /** No caller yet, on purpose: a real delete cascades over the project's jobs and roster in code. */
    @Transactional
    public boolean delete(UUID id) {
        return Project.deleteById(id);
    }
}
