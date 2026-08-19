package io.ib67.prts.agent.runner;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.*;

import java.util.*;

@ApplicationScoped
public class RunnerService {
    @Getter
    @AllArgsConstructor
    public static class Runner {
        protected final String name;
        @JsonIgnore
        protected final RunnerRpc rpc;
        @Setter
        protected ResourceInfo resource;
    }

    @Data
    @Builder
    public static class ResourceInfo {
        protected int numCpus;
        protected int numMemories;
        protected int numDisks;
    }

    protected Map<UUID, Runner> activeRunners;

    public Map<UUID, Runner> getActiveRunners() {
        return Collections.unmodifiableMap(activeRunners);
    }

    public Optional<Runner> getRunner(UUID id) {
        return Optional.ofNullable(activeRunners.get(id));
    }

    void unregisterRunner(UUID uuid) {
        activeRunners.remove(uuid);
    }

    boolean registerRunner(UUID id, Runner info) {
        if(activeRunners.containsKey(id)) return false;
        activeRunners.put(id, info);
        return true;
    }
}
