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
import lombok.ToString;

import java.util.List;
import java.util.Optional;

/**
 * Service-wide resource profile specifying CPU, memory, and disk requirements for job placement.
 */
@Entity
@Table(name = "resource_class")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class ResourceClass extends PanacheEntityBase {

    @Id
    @Column(name = "name", nullable = false, updatable = false, columnDefinition = "varchar")
    private String name;

    @Column(name = "num_cpus", nullable = false)
    private int numCpus;

    @Column(name = "mem_count", nullable = false)
    private int memCount;

    @Column(name = "disk_size", nullable = false)
    private int diskSize;

    // Static wrapper around findByIdOptional to enable mockStatic in tests.
    public static Optional<ResourceClass> findByName(String name) {
        return findByIdOptional(name);
    }

    /** Lists one page of the catalogue, by name. */
    public static List<ResourceClass> listPage(int offset, int length) {
        return ResourceClass.<ResourceClass>find("order by name")
                .range(offset, offset + length - 1)
                .list();
    }
}
