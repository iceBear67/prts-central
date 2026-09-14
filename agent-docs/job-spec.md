# JobSpec, Templates & Resource Classes

## Secrets Isolation

Plaintext secrets are never stored in the database or serialized inside `job.spec` jsonb:
- **Annotation**: `JobSpec#secret` is annotated with `@JsonIgnore` and excluded from all standard spec serializations.
- **In-Memory Injection**: `JobLauncher.prepare` resolves project secrets and attaches them to a transient copy of `JobSpec` via `withSecret(...)`.
- **Worker Delivery**: `WorkerClient.createJob` transfers secrets explicitly in `CreateJob.secrets`.
- **Log & View Safety**: `JobSpec#toString` prints secret counts rather than values; `JobView.SpecView` omits secrets.

## Templates & Resource Classes

### Templates (`JobSpecTemplate`)
- **Scoping**: `project_id = null` indicates a global template accessible across all projects; otherwise scoped to a single project.
- **Resolution**: `JobSpecTemplate.findVisibleFetched(projectId, id)` resolves templates visible to a project; `listGlobalFetched` and `findGlobalFetched` query only global templates.
- **Management Endpoints**: Project-scoped templates are managed via `POST|DELETE /project/{projectId}/job/template[/{id}]` (`job:template:manage`). Global templates are managed via `/api/admin/template`. Each endpoint only operates within its designated scope.
- **Global Template Constraints**: Global templates cannot mount volumes, since volumes belong to specific projects. Resource classes carry no such restriction — they are service-wide.
- **Request Payloads**: Inbound templates accept `JobSpecRequest` rather than `JobSpec`. Bean Validation validates required fields such as `image`, returning informative validation errors.
- **Template Deletion and Pending Jobs**: Deleting a template does not alter existing queue entries (`job.template_id` does not enforce a foreign key, and pending entries store the request payload as `jsonb`). If a pending job's referenced template no longer exists during dispatch, `PendingJobDispatcher` catches the `NotFoundException` and transitions the job to `FAILED`.

### Resource Classes (`ResourceClass`)
- **Keying**: Primary key is `name`. The catalogue is service-wide and shared across all projects.
- **Foreign Keys**: `job.resource_class` and `job_spec_template.resource_class` reference `resource_class(name)`.
- **Lookup**: `ResourceClass.findByName(name)` wraps `findByIdOptional` to facilitate test mocking via `Mockito.mockStatic`.
- **Management**: Managed via `/api/admin/resource-class` (`admin:all`). Workers report physical metrics but do not define classes. Deletion returns 409 Conflict if referenced by existing jobs or templates.
- **Override Rule**: Overriding a resource class requires `job:resource-class`. Retaining the default class (from the template or `TaskScope.resourceClass`) requires no additional permission.

## Volume Isolation

`JobSpec.requireVolumesIn(project)` validates that all requested volumes exist, belong to the project,
are in `READY` state, share the same worker host, and specify valid mount paths. Cross-worker volume
mismatches are rejected at validation rather than during scheduling to prevent unschedulable jobs from queueing.

`JobSpec.VolumeSpec.MOUNT_POINT` validates mount paths: absolute path of non-empty segments, no `.` or
`..`, no whitespace or control characters, no trailing slash, and at most 512 characters. Validation
occurs on the merged specification after `TaskScope.bindTo`, covering both task mounts and job overrides.
`AttachVolumeRequest` applies the same pattern at the API edge.

## Task Layer

Attributes contributed by a `Task` are merged in `JobLauncher.resolve`:
`TaskScope.defaultsTo` applies beneath caller overrides, while `TaskScope.bindTo` (volumes and task identity)
applies on top and cannot be overridden. Task attributes do not use `JobSpecOverrideAuthorizer`, as task scope
permissions are validated upon task creation/update via `TaskScope.authorize` (see [task-scope.md](task-scope.md)).

## Per-Field Override Gating (`JobSpecOverride`)

Overrides are validated field-by-field against caller permissions:
- **`JobSpecOverridePermissions`**: Implements `JobSpecOverrideAuthorizer`. Each method is annotated with a field-specific `@RequirePermission` check.
- **Application**: `JobSpecOverride.applyTo(spec, authorizer)` validates and applies fields. Scalar fields replace template values; maps and lists merge.
- **Pass-through**: `JobLauncher.PRE_AUTHORIZED` bypasses permission checks when dispatching pre-validated queued jobs.

### Adding an Overridable Field
1. Add field to `JobSpec` (normalize nulls in compact constructor) and `JobSpecOverride`.
2. Define permission constant in `Perm`.
3. Add method to `JobSpecOverrideAuthorizer`.
4. Implement method in `JobSpecOverridePermissions` with `@RequirePermission`.
5. Implement pass-through in `JobLauncher.PRE_AUTHORIZED`.
6. Wire field application in `JobSpecOverride.applyTo`.

