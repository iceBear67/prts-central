package io.ib67.prts.agent.runner;

import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;

@Getter
public class Runner {
    protected final String name;
    protected final RunnerRpc rpc;
    /**
     * {@code null} means this runner has unbounded resources and no pending work we know of.
     */
    @Setter
    @Nullable
    protected RunnerInfo info;

    public Runner(String name, RunnerRpc rpc, @Nullable RunnerInfo info) {
        this.name = name;
        this.rpc = rpc;
        this.info = info;
    }

    public int pendingJobCount() {
        return info == null ? 0 : info.getPending();
    }
}
