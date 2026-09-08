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
 * Republishes into the OpenAPI document what a JAX-RS method declares but SmallRye does not read: its
 * {@link RequirePermission} gate, the status it actually answers with, and the errors it can raise.
 *
 * <p>The error contract is documented here rather than on each method because it is uniform: every
 * refusal answers with {@code ClientErrorMapper.ErrorView}, so a {@code default} response says the
 * whole of it in one line per operation. The named codes it adds alongside are the ones worth calling
 * out, derived from the shape of the endpoint — deliberately not an exhaustive list, because being
 * exhaustive would mean an annotation per endpoint that drifts the moment one is added.
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
    /** The one 4xx with no body of ours: it is the authentication challenge. */
    private static final String CHALLENGE = "401";

    /** Where {@code ProjectService.requireWritable} guards every write, and so where 409 lives. */
    private static final String PROJECT_SCOPE = "/project/{projectId}";
    /**
     * The project writes that deliberately skip {@code requireWritable} — without them an archived
     * project would have no way back.
     */
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

    // A JobSpecOverridePermissions-style gate names the record whose property it guards.
    private void collectGate(MethodInfo method, Rule rule) {
        var declaring = method.declaringClass();
        if (declaring.declaredAnnotation(JAXRS_PATH) != null || !declaring.simpleName().endsWith(GATE_SUFFIX)) {
            return;
        }
        var gated = declaring.simpleName().substring(0, declaring.simpleName().length() - GATE_SUFFIX.length());
        gatedFields.computeIfAbsent(gated, name -> new HashMap<>()).put(method.name(), rule);
    }

    // Class-level annotations apply to methods that do not define their own.
    private Rule ruleOf(MethodInfo method) {
        var declared = method.declaredAnnotation(REQUIRE_PERMISSION);
        var instance = declared != null ? declared : method.declaringClass().declaredAnnotation(REQUIRE_PERMISSION);
        return instance == null ? null : rule(instance);
    }

    /**
     * The status the method answers with, or null when SmallRye already derived it.
     *
     * <p>It reads neither RESTEasy's {@code @ResponseStatus} nor the 204 a {@code void} method answers
     * with, so every created resource was documented 200 and every {@code void} POST 201.
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

    // Strips context prefixes until matching a known endpoint path.
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
                            + (rule.defaultRole() == ProjectRole.NONE ? "." : " and the role standing in for it."));
                }
            }
            if (writesToProject(verb, method)) {
                addResponse(responses, "409", "Refused by the project's current state; an archived "
                        + "project answers this to every write. The message says which.");
            }
        }
        // Everything the document describes sits under the `authenticated` policy of application.yml.
        addResponse(responses, "401", "Not signed in, or no local user for the authenticated identity. "
                + "Carries no body: this is the authentication challenge, which under the `web-app` OIDC "
                + "flow is a redirect to the provider rather than an answer of ours.");
        if (operation.getRequestBody() != null) {
            addResponse(responses, "400", "The body is absent, malformed, or breaks a constraint of its schema.");
        }
        if (path.indexOf('{') >= 0) {
            addResponse(responses, "404", "No such resource under this path, or none this caller may see.");
        }
        // The named codes above are the ones a caller branches on, not an exhaustive list — 415, say, is
        // left to `default`, being a client bug rather than anything a UI reacts to. This is what makes
        // that acceptable: whatever else an endpoint refuses with, the body is the same shape.
        addResponse(responses, APIResponses.DEFAULT, "Any other refusal.");
        responses.getAPIResponses().forEach((code, response) -> {
            if (carriesError(code)) {
                addErrorBody(response);
            }
        });
        operation.setResponses(responses);
    }

    /**
     * Re-keys the generated success response onto the status the method actually answers with.
     *
     * <p>Left alone when the method declares its own {@code @APIResponse} set: it has already said what
     * it answers, and there is no single generated response to move.
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
                .description("The body every 4xx and 5xx of this service answers with.")
                .addProperty("message", OASFactory.createSchema()
                        .addType(Schema.SchemaType.STRING)
                        .description("What was refused, in the words of whatever refused it."))
                .required(List.of("message"));
    }

    private static boolean isSuccess(String code) {
        return code.length() == 3 && code.charAt(0) == '2';
    }

    /** Everything answered with {@code ClientErrorMapper.ErrorView}, which is everything but the challenge. */
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
