package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.entity.ResourceClass;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Objects;

/**
 * View of a resource class specification.
 *
 * @param shared Whether every project may name this class. A class that is not shared is open only
 *               to the projects an admin listed under it.
 */
@Schema(description = ResourceClassView.FIGURES)
public record ResourceClassView(
        String name,
        int numCpus,
        int memCount,
        int diskSize,
        boolean shared
) {
    /**
     * Published on this view and on the requests that write one, because nothing else says it: a
     * client that guessed MiB or GiB would be off by three orders of magnitude either way.
     */
    public static final String FIGURES = """
            A placement profile: each figure is a minimum the host must meet, compared as it stands \
            against the capacity a worker reports for itself. Zero requires nothing.

            The figures carry no unit and the service never converts them, so `memCount` and \
            `diskSize` count in whatever the workers of this deployment count in — render them \
            unlabelled rather than assuming MiB or GiB.""";

    public ResourceClassView {
        Objects.requireNonNull(name, "name");
    }

    public static ResourceClassView of(ResourceClass klass) {
        return new ResourceClassView(klass.getName(), klass.getNumCpus(), klass.getMemCount(),
                klass.getDiskSize(), klass.isShared());
    }
}
