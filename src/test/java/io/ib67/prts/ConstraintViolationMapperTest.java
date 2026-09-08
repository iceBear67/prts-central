package io.ib67.prts;

import io.ib67.prts.dto.request.CreateProjectRequest;
import io.ib67.prts.dto.request.CreateSecretRequest;
import io.ib67.prts.dto.request.CreateTemplateRequest;
import io.ib67.prts.dto.request.JobSpecRequest;
import io.ib67.prts.dto.request.SetPermissionsRequest;
import io.ib67.prts.dto.request.TransferProjectRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that the constraint messages reach the caller as the same {@code {"message": ...}} the
 * hand-written checks produced, escaping and all.
 */
class ConstraintViolationMapperTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private final ConstraintViolationMapper mapper = new ConstraintViolationMapper();

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /** Runs the record through validation the way the resource parameter does, and maps the failure. */
    private String messageFor(Object request) {
        var violations = validator.validate(request);
        var response = mapper.toResponse(new ConstraintViolationException(violations));
        assertEquals(400, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        return assertInstanceOf(ClientErrorMapper.ErrorView.class, response.getEntity()).message();
    }

    @Test
    void aBlankNameReadsTheSameAsBefore() {
        assertEquals("name is required", messageFor(new CreateProjectRequest("   ")));
    }

    @Test
    void aNullNameReadsTheSameAsBefore() {
        assertEquals("name is required", messageFor(new CreateProjectRequest(null)));
    }

    @Test
    void aMissingUserIdReadsTheSameAsBefore() {
        assertEquals("userId is required", messageFor(new TransferProjectRequest(null)));
    }

    @Test
    void aMissingPermissionListReadsTheSameAsBefore() {
        assertEquals("permissions is required, empty to hold none",
                messageFor(new SetPermissionsRequest(null)));
    }

    /**
     * The braces in the pattern are message-template syntax and are escaped in the constraint; this is
     * what proves the escape unwinds to the literal the E2E asserts.
     */
    @Test
    void theSecretNamePatternSurvivesInterpolation() {
        assertEquals("name must match [A-Za-z_][A-Za-z0-9_]{0,63}",
                messageFor(new CreateSecretRequest("9lives", null, "s3cret")));
    }

    @Test
    void aNullSecretNameReadsAsThePatternToo() {
        assertEquals("name must match [A-Za-z_][A-Za-z0-9_]{0,63}",
                messageFor(new CreateSecretRequest(null, null, "s3cret")));
    }

    @Test
    void anEmptySecretValueReadsTheSameAsBefore() {
        assertEquals("value is required", messageFor(new CreateSecretRequest("TOKEN", null, "")));
    }

    /** A nested spec is only reached because the component carries @Valid. */
    @Test
    void aNestedSpecViolationIsReached() {
        var request = new CreateTemplateRequest(
                "deploy", new JobSpecRequest(null, null, null, null, null, 0L, null), "small");

        assertEquals("spec.image is required", messageFor(request));
    }

    @Test
    void aNegativeTimeoutReadsTheSameAsBefore() {
        var negative = -1L;
        var request = new CreateTemplateRequest(
                "deploy", new JobSpecRequest("alpine", null, null, null, null, negative, null), "small");

        assertEquals("spec.timeout must be >= 0", messageFor(request));
    }

    /** Several failures are joined in a stable order rather than picking one at random. */
    @Test
    void severalViolationsAreJoinedDeterministically() {
        var request = new CreateTemplateRequest(null, null, null);

        assertEquals("name is required; resourceClass is required; spec is required",
                messageFor(request));
    }

    /**
     * A violated return value is this service breaking its own contract, not a bad request, so it is
     * rethrown to stay a 500 — the same carve-out the built-in mapper makes.
     */
    @Test
    void aReturnValueViolationIsRethrown() throws Exception {
        var target = new Contracted();
        var method = Contracted.class.getMethod("mustNotReturnNull");
        var violations = validator.forExecutables()
                .validateReturnValue(target, method, target.mustNotReturnNull());
        var exception = new ConstraintViolationException(violations);

        assertEquals(1, violations.size());
        assertThrows(ConstraintViolationException.class, () -> mapper.toResponse(exception));
    }

    /** Stand-in for a resource whose own return value is constrained. */
    public static class Contracted {
        @NotNull
        public String mustNotReturnNull() {
            return null;
        }
    }
}
