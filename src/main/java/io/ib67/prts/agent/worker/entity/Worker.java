package io.ib67.prts.agent.worker.entity;

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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.ToString;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistent worker entity storing registration details and disabled status across reconnects.
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

    /** Whether this worker is disabled from receiving new jobs. */
    @Column(name = "disabled", nullable = false)
    private boolean disabled;

    /** Lists a page of registered workers ordered by name and ID. */
    public static List<Worker> listPage(int offset, int length) {
        return Worker.<Worker>find("order by name, id")
                .range(offset, offset + length - 1)
                .list();
    }

    /** Finds workers by IDs, returning an ID-to-Worker map of existing registrations. */
    public static Map<UUID, Worker> mapByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return Worker.<Worker>find("id in ?1", ids).stream()
                .collect(Collectors.toMap(Worker::getId, worker -> worker));
    }

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
