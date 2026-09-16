package io.ib67.prts.worker.mock.protocol;

/**
 * The resource snapshot a worker reports, in the shape the control plane stores and places against.
 *
 * @param current  what is left for new jobs; absent means the worker reports nothing
 * @param capacity what the worker has in total; absent disables capacity filtering entirely
 * @param pending  how many jobs the worker is already holding, which placement balances across
 */
public record ResourceInfo(Resources current, Resources capacity, int pending) {

    /**
     * One dimension of the snapshot. The names are the wire's, not this module's.
     */
    public record Resources(int numCpus, int numMemories, int numDisks) {
    }

    /** A worker with everything free and nothing pending. */
    public static ResourceInfo of(int numCpus, int numMemories, int numDisks) {
        var resources = new Resources(numCpus, numMemories, numDisks);
        return new ResourceInfo(resources, resources, 0);
    }

    /** The same snapshot with a different queue depth. */
    public ResourceInfo withPending(int pending) {
        return new ResourceInfo(current, capacity, pending);
    }
}
