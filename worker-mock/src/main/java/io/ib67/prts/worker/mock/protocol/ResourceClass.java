package io.ib67.prts.worker.mock.protocol;

import java.util.Objects;

/**
 * The resource profile a job asks for, sent along with it so a worker can sanity-check what it was
 * handed. Placement itself already happened on the control plane.
 */
public record ResourceClass(String name, int numCpus, int memCount, int diskSize, boolean shared) {
    public ResourceClass {
        Objects.requireNonNull(name, "name");
    }
}
