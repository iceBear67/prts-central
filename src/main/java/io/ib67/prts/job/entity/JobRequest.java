package io.ib67.prts.project.entity;

import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.project.JobLauncher;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal representation of a job creation request.
 *
 * @param templateId    ID of the job template to instantiate
 * @param override      optional field overrides for the template spec
 * @param resourceClass optional name of the resource class to run on; if null, defaults to the template's class
 */
@Embeddable
public record JobRequest(
        @Column(name = "template_id", nullable = false, updatable = false)
        UUID templateId,

        @JdbcTypeCode(SqlTypes.JSON)
        @Column(name = "create_override", updatable = false, columnDefinition = "jsonb")
        @Nullable JobSpecOverride override,

        @Column(name = "resource_class", updatable = false, columnDefinition = "varchar")
        @Nullable String resourceClass
) {
    public JobRequest {
        Objects.requireNonNull(templateId, "templateId");
    }

    public JobRequest withResourceClass(String resourceClass) {
        return new JobRequest(templateId, override, resourceClass);
    }
}
