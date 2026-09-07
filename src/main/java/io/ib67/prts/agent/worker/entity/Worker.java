package io.ib67.prts.agent.worker.entity;

import io.ib67.prts.agent.worker.RegisteredWorker;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Persistent worker identity. The live session is {@link RegisteredWorker}; {@link #disabled} lives
 * here so it survives a reconnect, and is mirrored onto the session on register.
 */
@Entity
@Table(name = "worker")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Worker extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    /** A disabled worker keeps its session and its running jobs, but is offered nothing new. */
    @Column(name = "disabled", nullable = false)
    private boolean disabled;

    public static Worker upsert(UUID id, String name) {
        Worker existing = findById(id);
        if (existing == null) {
            var created = builder().id(id).name(name).build();
            created.persistAndFlush();
            return created;
        }
        if (name != null && !name.equals(existing.getName())) {
            existing.setName(name);
        }
        return existing;
    }
}
