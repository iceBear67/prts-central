package io.ib67.prts.dto.job;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Common interface for jobs and queued pending jobs, discriminated by the {@code type} property.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JobView.class, name = JobView.TYPE),
        @JsonSubTypes.Type(value = PendingJobView.class, name = PendingJobView.TYPE)
})
// Explicit schema definition for OpenAPI polymorphic documentation.
@Schema(
        oneOf = {JobView.class, PendingJobView.class},
        discriminatorProperty = "type",
        discriminatorMapping = {
                @DiscriminatorMapping(value = JobView.TYPE, schema = JobView.class),
                @DiscriminatorMapping(value = PendingJobView.TYPE, schema = PendingJobView.class)
        })
public sealed interface JobStatusView permits JobView, PendingJobView {

    Instant createdAt();
}
