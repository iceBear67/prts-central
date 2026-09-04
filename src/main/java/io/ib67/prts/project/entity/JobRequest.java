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
 * A create request as the domain keeps it: a template and what the caller asked to change about it.
 * It is what {@link Job} and {@link io.ib67.prts.pending.PendingJob} store and what
 * {@link JobLauncher} takes; the wire shape is {@code CreateJobRequest}.
 *
 * @param override      the fields to change on the template's spec, each gated on its own permission
 * @param resourceClass by name, resolved against the project on every use so the shadowing rule
 *                      applies then; {@code null} defers to the template's. Gated only when it
 *                      deviates from the template's — see {@link JobLauncher#authorize}, which pins
 *                      it to the resolved one.
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
