package io.ib67.prts.agent.runner;

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

/**
 * A named resource requirement. Runners are matched against these numbers when a job is scheduled.
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
}
