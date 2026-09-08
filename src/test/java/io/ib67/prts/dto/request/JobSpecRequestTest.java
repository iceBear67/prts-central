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

    /** Validates the request and returns concatenated violation messages. */
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

    /** Templates do not include secrets; secrets are resolved at job dispatch time. */
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

    /** Verifies constraint violation message when image is missing. */
    @Test
    void aMissingImageIsRejectedWithAMessage() {
        assertEquals("spec.image is required", rejection(of(null, 0L)));
    }

    /** Blank images are normalized to null and rejected by constraint validation. */
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
