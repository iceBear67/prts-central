package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpec;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobSpecRequestTest {

    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /** The single message the record's constraints produce for this payload. */
    private static String rejection(JobSpecRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private static JobSpecRequest of(String image, long timeout) {
        return new JobSpecRequest(image, null, null, null, null, timeout, null);
    }

    @Test
    void everyFieldCarriesOver() {
        var spec = new JobSpecRequest(
                "img:1",
                Map.of("A", "1"),
                Map.of("team", "core"),
                List.of("run"),
                Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)),
                60L,
                "build").toSpec();

        assertEquals("img:1", spec.image());
        assertEquals(Map.of("A", "1"), spec.environment());
        assertEquals(Map.of("team", "core"), spec.labels());
        assertEquals(List.of("run"), spec.command());
        assertEquals(Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)), spec.volumes());
        assertEquals(60L, spec.timeout());
        assertEquals("build", spec.lock());
    }

    /** A template never carries secrets: they are resolved per dispatch. */
    @Test
    void theSpecCarriesNoSecrets() {
        assertEquals(Map.of(), of("img:1", 0L).toSpec().secret());
    }

    @Test
    void nullContainersNormalizeToEmpty() {
        var spec = of("img:1", 0L).toSpec();

        assertEquals(Map.of(), spec.environment());
        assertEquals(Map.of(), spec.labels());
        assertEquals(List.of(), spec.command());
        assertEquals(Map.of(), spec.volumes());
        assertEquals("", spec.lock());
    }

    @Test
    void theImageIsStripped() {
        assertEquals("img:1", of("  img:1  ", 0L).image());
    }

    /**
     * Rejection is a constraint now, not a throw: the record still constructs, and the resource
     * parameter's validation is what refuses it. {@code ConstraintViolationMapper} turns this message
     * into the response body.
     */
    @Test
    void aMissingImageIsRejectedWithAMessage() {
        assertEquals("spec.image is required", rejection(of(null, 0L)));
    }

    /** Normalization runs first, so a whitespace-only image reaches the constraint already empty. */
    @Test
    void aBlankImageIsRejected() {
        assertEquals("spec.image is required", rejection(of("   ", 0L)));
    }

    @Test
    void aNegativeTimeoutIsRejected() {
        assertEquals("spec.timeout must be >= 0", rejection(of("img:1", -1L)));
    }

    @Test
    void aWellFormedSpecHasNothingToSay() {
        assertEquals("", rejection(of("img:1", 60L)));
    }
}
