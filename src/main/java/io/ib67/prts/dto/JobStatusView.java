package io.ib67.prts.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.eclipse.microprofile.openapi.annotations.media.DiscriminatorMapping;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * What a job id resolves to: the job, or the queue entry it has not become yet. A client holds the
 * one id it was given at create time and reads {@code type} to know which it is looking at, rather
 * than swapping ids and endpoints once the entry is dispatched.
 *
 * <p>Endpoints returning either of these must declare <em>this</em> type: Jackson writes the
 * discriminator off the static type, so a method declared as the concrete record loses it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JobView.class, name = JobView.TYPE),
        @JsonSubTypes.Type(value = PendingJobView.class, name = PendingJobView.TYPE)
})
// Spelled a second time for the document: the scanner does not read Jackson's subtypes, and without
// this the interface publishes as a bare object and neither record is emitted at all.
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
