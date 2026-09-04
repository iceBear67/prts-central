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
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes what {@link RequirePermission} enforces into the OpenAPI document: the permission an
 * endpoint takes, the project role that stands in for it, and the responses a caller holding neither
 * gets. Field-gating beans — a bean of pass-through methods named after the record whose fields they
 * gate, see {@link io.ib67.prts.agent.job.JobSpecOverridePermissions} — annotate that record's
 * schema properties instead.
 *
 * <p>Runs at build time because that is where the Jandex index still knows which Java method serves
 * which path; the OpenAPI model alone no longer does.
 */
@OpenApiFilter(stages = OpenApiFilter.RunStage.BUILD)
public class PermissionOASFilter implements OASFilter {

    private static final DotName REQUIRE_PERMISSION = DotName.createSimple(RequirePermission.class.getName());
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

    private final IndexView index;
    /** {@code "GET /project/{projectId}"} — the path as JAX-RS spells it, without any prefix. */
    private final Map<String, Rule> endpoints = new HashMap<>();
    /** Schema name to property name to the rule gating that property. */
    private final Map<String, Map<String, Rule>> gatedFields = new HashMap<>();

    public PermissionOASFilter(IndexView index) {
        this.index = index;
        for (var instance : index.getAnnotations(REQUIRE_PERMISSION)) {
            switch (instance.target().kind()) {
                case METHOD -> collect(instance.target().asMethod(), rule(instance));
                case CLASS -> collect(instance.target().asClass(), rule(instance));
                default -> {
                }
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
        if (components != null && components.getSchemas() != null) {
            components.getSchemas().forEach(this::describeProperties);
        }
    }

    private void collect(MethodInfo method, Rule rule) {
        var declaring = method.declaringClass();
        if (declaring.declaredAnnotation(JAXRS_PATH) != null) {
            collectEndpoint(method, rule);
        } else if (declaring.simpleName().endsWith(GATE_SUFFIX)) {
            var gated = declaring.simpleName().substring(0, declaring.simpleName().length() - GATE_SUFFIX.length());
            gatedFields.computeIfAbsent(gated, name -> new HashMap<>()).put(method.name(), rule);
        }
    }

    /** A class-level binding covers the resource methods that do not carry one of their own. */
    private void collect(ClassInfo type, Rule rule) {
        if (type.declaredAnnotation(JAXRS_PATH) == null) {
            return;
        }
        for (var method : type.methods()) {
            if (method.declaredAnnotation(REQUIRE_PERMISSION) == null) {
                collectEndpoint(method, rule);
            }
        }
    }

    private void collectEndpoint(MethodInfo method, Rule rule) {
        var verb = verbOf(method);
        if (verb != null) {
            endpoints.put(key(verb, pathOf(method)), rule);
        }
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
        item.getOperations().forEach((verb, operation) -> {
            var rule = ruleFor(verb, path);
            if (rule != null) {
                describe(operation, rule);
            }
        });
    }

    /**
     * Document paths carry the {@code quarkus.rest.path} prefix and the HTTP root path, neither of
     * which the annotations know about, so leading segments are dropped until one matches.
     */
    private Rule ruleFor(PathItem.HttpMethod verb, String path) {
        for (var candidate = path; candidate != null; ) {
            var rule = endpoints.get(key(verb, candidate));
            if (rule != null) {
                return rule;
            }
            var next = candidate.indexOf('/', 1);
            candidate = next < 0 ? null : candidate.substring(next);
        }
        return null;
    }

    private void describe(Operation operation, Rule rule) {
        operation.setDescription(append(operation.getDescription(), summary(rule)));
        operation.addExtension(EXTENSION, extension(rule));

        var responses = operation.getResponses() != null ? operation.getResponses() : OASFactory.createAPIResponses();
        // Every check refuses an identity with no local user, whatever else it allows.
        if (!responses.hasAPIResponse("401")) {
            responses.addAPIResponse("401", OASFactory.createAPIResponse()
                    .description("Not signed in, or no local user for the authenticated identity."));
        }
        if (!rule.defaultValue() && !responses.hasAPIResponse("403")) {
            responses.addAPIResponse("403", OASFactory.createAPIResponse()
                    .description("Missing `" + rule.perm().permission() + "`"
                            + (rule.defaultRole() == ProjectRole.NONE ? "." : " and the role standing in for it.")));
        }
        operation.setResponses(responses);
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
