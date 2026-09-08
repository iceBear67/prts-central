package io.ib67.prts.openapi;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.project.entity.ProjectRole;
import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Enriches the OpenAPI specification at build time with metadata not automatically extracted by SmallRye:
 * permission requirements ({@link RequirePermission}), accurate HTTP success statuses, and standard error responses.
 *
 * <p>Standardizes error responses using {@code ClientErrorMapper.ErrorView} via a {@code default} response
 * and adds commonly expected HTTP status codes (e.g. 400, 401, 403, 404, 409) based on method signatures
 * and annotations.
 */
@OpenApiFilter(stages = OpenApiFilter.RunStage.BUILD)
public class EndpointOASFilter implements OASFilter {

    private static final DotName REQUIRE_PERMISSION = DotName.createSimple(RequirePermission.class.getName());
    private static final DotName RESPONSE_STATUS = DotName.createSimple("org.jboss.resteasy.reactive.ResponseStatus");
    private static final DotName JAXRS_PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final Map<DotName, PathItem.HttpMethod> VERBS = Map.of(
            DotName.createSimple("jakarta.ws.rs.GET"), PathItem.HttpMethod.GET,
            DotName.createSimple("jakarta.ws.rs.POST"), PathItem.HttpMethod.POST,
            DotName.createSimple("jakarta.ws.rs.PUT"), PathItem.HttpMethod.PUT,
            DotName.createSimple("jakarta.ws.rs.PATCH"), PathItem.HttpMethod.PATCH,
            DotName.createSimple("jakarta.ws.rs.DELETE"), PathItem.HttpMethod.DELETE,
            DotName.createSimple("jakarta.ws.rs.HEAD"), PathItem.HttpMethod.HEAD,
            DotName.createSimple("jakarta.ws.rs.OPTIONS"), PathItem.HttpMethod.OPTIONS);
    private static final String GATE_SUFFIX = "Permissions";
    private static final String EXTENSION = "x-required-permission";

    private static final String ERROR_SCHEMA = "ErrorView";
    private static final String ERROR_REF = "#/components/schemas/" + ERROR_SCHEMA;
    private static final String JSON = "application/json";
    /** HTTP 401 status code, which returns an empty body for authentication challenges. */
    private static final String CHALLENGE = "401";

    /** Base path prefix for project operations subject to {@code ProjectService.requireWritable}. */
    private static final String PROJECT_SCOPE = "/project/{projectId}";
    /** Mutating project endpoints that bypass {@code requireWritable} to allow unarchiving. */
    private static final Set<String> ALWAYS_WRITABLE = Set.of(
            "POST " + PROJECT_SCOPE + "/archive", "POST " + PROJECT_SCOPE + "/unarchive");

    private final IndexView index;
    private final Map<String, MethodInfo> endpoints = new HashMap<>();
    private final Map<String, Map<String, Rule>> gatedFields = new HashMap<>();

    public EndpointOASFilter(IndexView index) {
        this.index = index;
        for (var instance : index.getAnnotations(JAXRS_PATH)) {
            if (instance.target().kind() == AnnotationTarget.Kind.CLASS) {
                collectResource(instance.target().asClass());
            }
        }
        for (var instance : index.getAnnotations(REQUIRE_PERMISSION)) {
            if (instance.target().kind() == AnnotationTarget.Kind.METHOD) {
                collectGate(instance.target().asMethod(), rule(instance));
            }
        }
    }

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        var paths = openAPI.getPaths();
        if (paths != null && paths.getPathItems() != null) {
            paths.getPathItems().forEach(this::describeOperations);
        }
        var components = openAPI.getComponents();
        if (components == null) {
            components = OASFactory.createComponents();
            openAPI.setComponents(components);
        }
        if (components.getSchemas() != null) {
            components.getSchemas().forEach(this::describeProperties);
        }
        components.addSchema(ERROR_SCHEMA, errorSchema());
    }

    private void collectResource(ClassInfo type) {
        for (var method : type.methods()) {
            var verb = verbOf(method);
            if (verb != null) {
                endpoints.put(key(verb, pathOf(method)), method);
            }
        }
    }

    // Record property gates match the naming convention <RecordName>Permissions.
    private void collectGate(MethodInfo method, Rule rule) {
        var declaring = method.declaringClass();
        if (declaring.declaredAnnotation(JAXRS_PATH) != null || !declaring.simpleName().endsWith(GATE_SUFFIX)) {
            return;
        }
        var gated = declaring.simpleName().substring(0, declaring.simpleName().length() - GATE_SUFFIX.length());
        gatedFields.computeIfAbsent(gated, name -> new HashMap<>()).put(method.name(), rule);
    }

    // Fall back to class-level annotations if not defined directly on the method.
    private Rule ruleOf(MethodInfo method) {
        var declared = method.declaredAnnotation(REQUIRE_PERMISSION);
        var instance = declared != null ? declared : method.declaringClass().declaredAnnotation(REQUIRE_PERMISSION);
        return instance == null ? null : rule(instance);
    }

    /**
     * Determines the HTTP success status for a method, or null if SmallRye's default applies.
     *
     * <p>Resolves RESTEasy's {@code @ResponseStatus} and defaults {@code void} methods to HTTP 204.
     */
    private static String statusOf(MethodInfo method) {
        var declared = method.declaredAnnotation(RESPONSE_STATUS);
        if (declared != null) {
            return String.valueOf(declared.value().asInt());
        }
        return method.returnType().kind() == Type.Kind.VOID ? "204" : null;
    }

    private Rule rule(AnnotationInstance instance) {
        return new Rule(
                Perm.valueOf(instance.value().asEnum()),
                instance.valueWithDefault(index, "defaultValue").asBoolean(),
                ProjectRole.valueOf(instance.valueWithDefault(index, "defaultRole").asEnum()),
                instance.valueWithDefault(index, "allowAdmin").asBoolean());
    }

    private static PathItem.HttpMethod verbOf(MethodInfo method) {
        for (var verb : VERBS.entrySet()) {
            if (method.declaredAnnotation(verb.getKey()) != null) {
                return verb.getValue();
            }
        }
        return null;
    }

    private static String pathOf(MethodInfo method) {
        var onClass = method.declaringClass().declaredAnnotation(JAXRS_PATH);
        return normalize(pathValue(onClass) + "/" + pathValue(method.declaredAnnotation(JAXRS_PATH)));
    }

    private static String pathValue(AnnotationInstance path) {
        return path == null ? "" : path.value().asString();
    }

    private static String normalize(String path) {
        var normalized = path.replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String key(PathItem.HttpMethod verb, String path) {
        return verb + " " + path;
    }

    private void describeOperations(String path, PathItem item) {
        if (item.getOperations() == null) {
            return;
        }
        item.getOperations().forEach((verb, operation) -> describe(path, verb, operation));
    }

    // Strips path prefixes until matching a known endpoint path.
    private MethodInfo lookup(PathItem.HttpMethod verb, String path) {
        for (var candidate = path; candidate != null; ) {
            var method = endpoints.get(key(verb, candidate));
            if (method != null) {
                return method;
            }
            var next = candidate.indexOf('/', 1);
            candidate = next < 0 ? null : candidate.substring(next);
        }
        return null;
    }

    private void describe(String path, PathItem.HttpMethod verb, Operation operation) {
        var responses = operation.getResponses() != null ? operation.getResponses() : OASFactory.createAPIResponses();
        var method = lookup(verb, path);
        if (method != null) {
            var status = statusOf(method);
            if (status != null) {
                applyStatus(responses, status);
            }
            var rule = ruleOf(method);
            if (rule != null) {
                operation.setDescription(append(operation.getDescription(), summary(rule)));
                operation.addExtension(EXTENSION, extension(rule));
                if (!rule.defaultValue()) {
                    addResponse(responses, "403", "Missing `" + rule.perm().permission() + "`"
                            + (rule.defaultRole() == ProjectRole.NONE ? "." : " or the corresponding project role."));
                }
            }
            if (writesToProject(verb, method)) {
                addResponse(responses, "409", "The project is archived or cannot accept modifications in its current state.");
            }
        }
        // Operations under the /api tree require authentication.
        addResponse(responses, "401", "Authentication required or user identity not recognized. The response body is empty.");
        if (operation.getRequestBody() != null) {
            addResponse(responses, "400", "The request body is missing, malformed, or violates schema constraints.");
        }
        if (path.indexOf('{') >= 0) {
            addResponse(responses, "404", "The requested resource was not found or is inaccessible.");
        }
        // Common HTTP error codes are explicitly documented; other errors use the default error schema.
        addResponse(responses, APIResponses.DEFAULT, "Default error response.");
        responses.getAPIResponses().forEach((code, response) -> {
            if (carriesError(code)) {
                addErrorBody(response);
            }
        });
        operation.setResponses(responses);
    }

    /**
     * Updates the generated success response code to match the actual status returned by the method.
     *
     * <p>Skipped if the method already declares its own {@code @APIResponse} annotations.
     */
    private static void applyStatus(APIResponses responses, String status) {
        if (responses.hasAPIResponse(status)) {
            return;
        }
        var generated = responses.getAPIResponses().keySet().stream()
                .filter(EndpointOASFilter::isSuccess)
                .toList();
        if (generated.size() != 1) {
            return;
        }
        var moved = responses.getAPIResponse(generated.getFirst());
        responses.removeAPIResponse(generated.getFirst());
        responses.addAPIResponse(status, moved);
    }

    private static boolean writesToProject(PathItem.HttpMethod verb, MethodInfo method) {
        if (verb == PathItem.HttpMethod.GET) {
            return false;
        }
        var path = pathOf(method);
        return path.startsWith(PROJECT_SCOPE) && !ALWAYS_WRITABLE.contains(key(verb, path));
    }

    private static void addResponse(APIResponses responses, String code, String description) {
        if (!responses.hasAPIResponse(code)) {
            responses.addAPIResponse(code, OASFactory.createAPIResponse().description(description));
        }
    }

    private static void addErrorBody(APIResponse response) {
        if (response.getContent() == null) {
            response.setContent(errorContent());
        }
    }

    private static Content errorContent() {
        return OASFactory.createContent().addMediaType(JSON,
                OASFactory.createMediaType().schema(OASFactory.createSchema().ref(ERROR_REF)));
    }

    private static Schema errorSchema() {
        return OASFactory.createSchema()
                .addType(Schema.SchemaType.OBJECT)
                .description("Standard JSON error response body for 4xx and 5xx errors.")
                .addProperty("message", OASFactory.createSchema()
                        .addType(Schema.SchemaType.STRING)
                        .description("Error message describing the failure."))
                .required(List.of("message"));
    }

    private static boolean isSuccess(String code) {
        return code.length() == 3 && code.charAt(0) == '2';
    }

    /** Checks if the status code expects an ErrorView response body (all 4xx/5xx except 401). */
    private static boolean carriesError(String code) {
        if (APIResponses.DEFAULT.equals(code)) {
            return true;
        }
        return code.length() == 3
                && (code.charAt(0) == '4' || code.charAt(0) == '5')
                && !CHALLENGE.equals(code);
    }

    private void describeProperties(String schemaName, Schema schema) {
        var gated = gatedFields.get(schemaName);
        if (gated == null || schema.getProperties() == null) {
            return;
        }
        schema.getProperties().forEach((property, propertySchema) -> {
            var rule = gated.get(property);
            if (rule != null) {
                propertySchema.setDescription(append(propertySchema.getDescription(),
                        "Overriding the template value requires `" + rule.perm().permission() + "` in this project."));
            }
        });
    }

    private static String summary(Rule rule) {
        var text = new StringBuilder("**Authorization**: requires `")
                .append(rule.perm().permission())
                .append(rule.perm().global() ? "` globally" : "` in this project");
        if (rule.defaultRole() != ProjectRole.NONE) {
            text.append(", or project role `").append(rule.defaultRole()).append("` or higher");
        }
        if (rule.allowAdmin() && rule.perm() != Perm.ADMIN_OF_ALL) {
            text.append("; `").append(Perm.ADMIN_OF_ALL.permission()).append("` passes as well");
        }
        text.append('.');
        if (rule.defaultValue()) {
            text.append(" Callers holding neither are allowed anyway.");
        }
        return text.toString();
    }

    private static Map<String, Object> extension(Rule rule) {
        var extension = new LinkedHashMap<String, Object>();
        extension.put("permission", rule.perm().permission());
        extension.put("scope", rule.perm().global() ? "global" : "project");
        if (rule.defaultRole() != ProjectRole.NONE) {
            extension.put("defaultRole", rule.defaultRole().name());
        }
        extension.put("allowAdmin", rule.allowAdmin());
        extension.put("defaultValue", rule.defaultValue());
        return extension;
    }

    private static String append(String existing, String addition) {
        return existing == null || existing.isBlank() ? addition : existing + "\n\n" + addition;
    }

    private record Rule(Perm perm, boolean defaultValue, ProjectRole defaultRole, boolean allowAdmin) {
        private Rule {
            Objects.requireNonNull(perm, "perm");
            Objects.requireNonNull(defaultRole, "defaultRole");
        }
    }
}
