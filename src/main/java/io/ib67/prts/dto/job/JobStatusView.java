package io.ib67.prts.dto.job;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Common interface for jobs and queued pending jobs, discriminated by the {@code type} property.
 */
// EXISTING_PROPERTY, not PROPERTY: Jackson writes a type id from the declared type, and a listing
// declares Page<T>, whose argument is erased by the time the items are serialized — so every row of
// a mixed listing went out undiscriminated. A branch that answers the property itself carries it
// whatever the writer was handed.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
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

    /** Which branch this is: the discriminator, written by the view rather than by the writer. */
    @JsonProperty("type")
    String type();
}
