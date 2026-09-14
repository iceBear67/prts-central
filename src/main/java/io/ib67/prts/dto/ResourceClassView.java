package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.entity.ResourceClass;

import java.util.Objects;

/**
 * View of a resource class specification.
 */
public record ResourceClassView(
        String name,
        int numCpus,
        int memCount,
        int diskSize
) {
    public ResourceClassView {
        Objects.requireNonNull(name, "name");
    }

    public static ResourceClassView of(ResourceClass klass) {
        return new ResourceClassView(
                klass.getName(), klass.getNumCpus(), klass.getMemCount(), klass.getDiskSize());
    }
}
