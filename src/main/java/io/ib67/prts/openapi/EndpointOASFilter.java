package io.ib67.prts.openapi;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.job.entity.ProjectRole;
import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
    private static final String SCHEMA_REF = "#/components/schemas/";
    private static final String ERROR_REF = SCHEMA_REF + ERROR_SCHEMA;
    private static final String JSON = "application/json";

    private static final String PACKAGE = "io.ib67.prts.";
    private static final DotName NULLABLE = DotName.createSimple("jakarta.annotation.Nullable");
    private static final DotName MAP = DotName.createSimple("java.util.Map");
    /** HTTP 401 status code, which returns an empty body for authentication challenges. */
    private static final String CHALLENGE = "401";

    /** Public endpoints exempt from the authenticated policy that do not return 401. */
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/health");

    /** Base path prefix for project operations subject to {@code ProjectService.requireWritable}. */
    private static final String PROJECT_SCOPE = "/project/{projectId}";
    /**
     * Mutating project endpoints that do not invoke {@code requireWritable} and therefore do not return 409
     * (e.g. unarchive and delete actions on archived projects).
     */
    private static final Set<String> BYPASSES_WRITABLE = Set.of(
            "POST " + PROJECT_SCOPE + "/archive",
            "POST " + PROJECT_SCOPE + "/unarchive",
            "DELETE " + PROJECT_SCOPE);

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
            describeShapes(openAPI, components.getSchemas());
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
        // Non-public endpoints require authentication.
        if (!PUBLIC_PATHS.contains(path)) {
            addResponse(responses, "401", "Authentication required or user identity not recognized. The response body is empty.");
        }
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
        return path.startsWith(PROJECT_SCOPE) && !BYPASSES_WRITABLE.contains(key(verb, path));
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

    /**
     * States what a response body guarantees, read off the Java type behind each schema.
     *
     * <p>SmallRye derives {@code required} from Bean Validation, which only inbound DTOs carry — so a
     * response schema publishes none, and a client cannot tell a field that may be absent from one the
     * compact constructor {@code requireNonNull}s. The declaration answers it: no {@code @Nullable}
     * means required, {@code @Nullable} means the value may be null.
     *
     * <p>Schemas a request body reaches are left alone. Several are shared between a request and a
     * response ({@code TaskScope}, {@code VolumeSpec}, {@code CreateJobRequest}), and there the
     * constraints already say what a caller must send.
     */
    private void describeShapes(OpenAPI openAPI, Map<String, Schema> declared) {
        var shapes = new ResponseShapes(declared);
        var paths = openAPI.getPaths();
        if (paths != null && paths.getPathItems() != null) {
            paths.getPathItems().forEach((path, item) -> {
                if (item.getOperations() != null) {
                    item.getOperations().forEach((verb, operation) -> shapes.collect(
                            lookup(verb, path), operation));
                }
            });
        }
        var inbound = requestSchemas(openAPI);
        shapes.resolved().forEach((name, type) -> {
            if (!inbound.contains(name)) {
                describeShape(declared.get(name), type);
            }
        });
    }

    private void describeShape(Schema schema, ClassInfo type) {
        if (schema == null || schema.getProperties() == null) {
            return;
        }
        var required = new ArrayList<String>();
        schema.getProperties().forEach((property, propertySchema) -> {
            var field = type.field(property);
            if (field == null) {
                return;
            }
            if (isNullable(type, field)) {
                allowNull(propertySchema);
            } else {
                required.add(property);
            }
            // A grouping keyed by an enum is published with its keys constrained, so a client mapping
            // the breakdown fails loudly on a key it does not know rather than dropping the count.
            keysOf(field).ifPresent(constants -> propertySchema.setPropertyNames(
                    OASFactory.createSchema().addType(Schema.SchemaType.STRING).enumeration(constants)));
        });
        if (!required.isEmpty() && schema.getRequired() == null) {
            schema.setRequired(required);
        }
    }

    /**
     * Resolves each response schema to the class it was generated from by walking the document and the
     * endpoint's return type together.
     *
     * <p>Matching on the schema name would not do: SmallRye derives one from the simple class name and
     * disambiguates collisions with a counter, so {@code AdminStatsView.Jobs} and
     * {@code ProjectDetailView.Jobs} become {@code Jobs} and {@code Jobs1} with nothing saying which is
     * which. A union ({@code allOf} / {@code oneOf}) is not descended into — its branches are other
     * types, and pairing them with the declared one would resolve the wrong class.
     */
    private final class ResponseShapes {
        private final Map<String, Schema> declared;
        private final Map<String, ClassInfo> resolved = new HashMap<>();
        private final Set<String> visited = new HashSet<>();

        private ResponseShapes(Map<String, Schema> declared) {
            this.declared = declared;
        }

        private void collect(MethodInfo endpoint, Operation operation) {
            if (endpoint == null || operation.getResponses() == null) {
                return;
            }
            operation.getResponses().getAPIResponses().forEach((code, response) -> {
                if (isSuccess(code) && response.getContent() != null
                        && response.getContent().getMediaTypes() != null) {
                    response.getContent().getMediaTypes().values().stream()
                            .map(MediaType::getSchema)
                            .forEach(schema -> pair(schema, endpoint.returnType()));
                }
            });
        }

        private void pair(Schema schema, Type type) {
            if (schema == null || type == null) {
                return;
            }
            var reference = refName(schema);
            if (reference != null) {
                var owner = classOf(type);
                if (owner != null && owner.name().toString().startsWith(PACKAGE)) {
                    resolved.putIfAbsent(reference, owner);
                }
                if (visited.add(reference + " " + type.name())) {
                    pair(declared.get(reference), type);
                }
                return;
            }
            pair(schema.getItems(), argument(type, 0));
            pair(schema.getAdditionalPropertiesSchema(), argument(type, 1));
            var owner = classOf(type);
            if (schema.getProperties() == null || owner == null) {
                return;
            }
            schema.getProperties().forEach((property, propertySchema) -> {
                var field = owner.field(property);
                if (field != null) {
                    pair(propertySchema, field.type());
                }
            });
        }

        private Map<String, ClassInfo> resolved() {
            return resolved;
        }
    }

    private ClassInfo classOf(Type type) {
        return type.kind() == Type.Kind.CLASS || type.kind() == Type.Kind.PARAMETERIZED_TYPE
                ? index.getClassByName(type.name())
                : null;
    }

    private static Type argument(Type type, int position) {
        if (type == null || type.kind() != Type.Kind.PARAMETERIZED_TYPE) {
            return null;
        }
        var arguments = type.asParameterizedType().arguments();
        return position < arguments.size() ? arguments.get(position) : null;
    }

    // Without @Target, jakarta.annotation.Nullable lands on a record's field and accessor alike; a
    // Lombok POJO only ever carries it on the field.
    private static boolean isNullable(ClassInfo type, FieldInfo field) {
        if (field.hasAnnotation(NULLABLE)) {
            return true;
        }
        var accessor = type.method(field.name());
        return accessor != null && accessor.hasAnnotation(NULLABLE);
    }

    /** The constants of {@code E} when the field is a {@code Map<E, ?>}, otherwise empty. */
    private Optional<List<Object>> keysOf(FieldInfo field) {
        if (field.type().kind() != Type.Kind.PARAMETERIZED_TYPE
                || !MAP.equals(field.type().name())) {
            return Optional.empty();
        }
        var arguments = field.type().asParameterizedType().arguments();
        if (arguments.isEmpty()) {
            return Optional.empty();
        }
        var key = index.getClassByName(arguments.getFirst().name());
        if (key == null || !key.isEnum()) {
            return Optional.empty();
        }
        return Optional.of(key.enumConstants().stream().map(constant -> (Object) constant.name()).toList());
    }

    // OpenAPI 3.1 states nullability in the type list. A bare $ref carries no type of its own, and
    // there being absent from `required` is the whole statement.
    private static void allowNull(Schema schema) {
        var types = schema.getType();
        if (types != null && !types.isEmpty() && !types.contains(Schema.SchemaType.NULL)) {
            schema.addType(Schema.SchemaType.NULL);
        }
    }

    /** Component schemas a request body reaches, at any depth. */
    private static Set<String> requestSchemas(OpenAPI openAPI) {
        var components = openAPI.getComponents();
        var declared = components == null || components.getSchemas() == null
                ? Map.<String, Schema>of()
                : components.getSchemas();
        var pending = new ArrayDeque<Schema>();
        var paths = openAPI.getPaths();
        if (paths != null && paths.getPathItems() != null) {
            paths.getPathItems().values().stream()
                    .filter(item -> item.getOperations() != null)
                    .flatMap(item -> item.getOperations().values().stream())
                    .map(Operation::getRequestBody)
                    .filter(Objects::nonNull)
                    .map(RequestBody::getContent)
                    .filter(content -> content != null && content.getMediaTypes() != null)
                    .flatMap(content -> content.getMediaTypes().values().stream())
                    .map(MediaType::getSchema)
                    .filter(Objects::nonNull)
                    .forEach(pending::add);
        }
        var reached = new HashSet<String>();
        while (!pending.isEmpty()) {
            var schema = pending.poll();
            var referenced = refName(schema);
            if (referenced != null && reached.add(referenced) && declared.containsKey(referenced)) {
                pending.add(declared.get(referenced));
            }
            children(schema).forEach(pending::add);
        }
        return reached;
    }

    private static Stream<Schema> children(Schema schema) {
        return Stream.of(
                        schema.getProperties() == null
                                ? Stream.<Schema>empty() : schema.getProperties().values().stream(),
                        Stream.ofNullable(schema.getItems()),
                        Stream.ofNullable(schema.getAdditionalPropertiesSchema()),
                        schema.getAllOf() == null ? Stream.<Schema>empty() : schema.getAllOf().stream(),
                        schema.getAnyOf() == null ? Stream.<Schema>empty() : schema.getAnyOf().stream(),
                        schema.getOneOf() == null ? Stream.<Schema>empty() : schema.getOneOf().stream())
                .flatMap(stream -> stream);
    }

    private static String refName(Schema schema) {
        var ref = schema.getRef();
        return ref == null || !ref.startsWith(SCHEMA_REF) ? null : ref.substring(SCHEMA_REF.length());
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
