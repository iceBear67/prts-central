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
- **Resolution**: `JobSpecTemplate.findVisibleFetched(projectId, id)` resolves templates visible to a project; `searchGlobal` and `findGlobalFetched` query only global templates.
- **Management Endpoints**: Project-scoped templates are managed via `POST|PATCH|DELETE /project/{projectId}/job/template[/{id}]` (`job:template:manage`). Global templates are managed via `/api/admin/template`. Each endpoint only operates within its designated scope — a global template conflicts (409) on both the project-scoped `PATCH` and `DELETE`.
- **Editing in Place**: `PATCH` applies whichever of `name`, `resourceClass` and `spec` the body carries, and a supplied `spec` **replaces** the stored one rather than merging (merging cannot remove an environment entry). It updates rather than recreates because the ID is what a task, a queued job and a re-run payload hold. The answer carries the full spec regardless of `job:template:read` — the caller just wrote it.
- **Global Template Constraints**: Global templates cannot mount volumes, since volumes belong to specific projects. Resource classes carry no such restriction — they are service-wide.
- **Request Payloads**: Inbound templates accept `JobSpecRequest` rather than `JobSpec`. Bean Validation validates required fields such as `image`, returning informative validation errors.
- **Template Deletion and Pending Jobs**: Deleting a template does not alter existing queue entries (`job.template_id` does not enforce a foreign key, and pending entries store the request payload as `jsonb`). If a pending job's referenced template no longer exists during dispatch, `PendingJobDispatcher` catches the `NotFoundException` and transitions the job to `FAILED`.

### Resource Classes (`ResourceClass`)
- **Keying**: Primary key is `name`. The catalogue is service-wide.
- **Foreign Keys**: `job.resource_class` and `job_spec_template.resource_class` reference `resource_class(name)`.
- **Lookup**: `ResourceClass.findByName(name)` wraps `findByIdOptional` to facilitate test mocking via `Mockito.mockStatic`.
- **Management**: Managed via `/api/admin/resource-class` (`admin:all`). Workers report physical metrics but do not define classes. Deletion returns 409 Conflict if referenced by existing jobs or templates.
- **Override Rule**: Overriding a resource class requires `job:resource-class`. Retaining the default class (from the template or `TaskScope.resourceClass`) requires no additional permission.

### Per-Project Availability

A class is either **shared** — every project's to name — or open only to the projects listed under it
in `project_resource_class` (`ProjectResourceClass`, a composite-key join keyed by project and class
name).

- **`ResourceClass.shared` defaults to true**, in the column and in the builder, so a deployment that
  grants nothing keeps the catalogue it had before this existed. A class is restricted only by an
  admin saying so (`shared: false` on create, or `PATCH`).
- **Both foreign keys cascade at the database level**, which is why neither `ProjectService.delete`
  nor the resource class delete mentions this table. Grants are not a reference that blocks deleting
  a class, and they survive the class being shared and un-shared — un-sharing restores the list an
  admin built rather than emptying it.
- **The gate is `ResourceClass.requireAvailableTo(projectId)`**, throwing
  `io.quarkus.security.ForbiddenException` (403, `ForbiddenMapper`), applied at:
  - `JobLauncher.resolveResourceClass`, in **every** branch — including the one that keeps the
    template's or the task's own default, which is how a global template or a task scope would
    otherwise hand a restricted class to every project.
  - `JobResource.createTemplate` / `updateTemplate`, so the refusal lands where the class was chosen
    rather than on every later submission.
  - `resolve` runs in `prepare` too, so a queue entry whose grant was withdrawn while it waited is
    refused on dispatch and `PendingJobDispatcher` marks it `FAILED`.
- **A `TaskScope.resourceClass` is not validated when the task is written**, as before: it is resolved
  when a job under the task is launched, and refused there.
- **Reads**: `GET /project/{projectId}/resource-class` is the project's own half of the catalogue
  (`ResourceClass.listAvailableTo`); `GET /resource-class` still publishes the whole of it to any
  authenticated caller, because a class is service-wide configuration rather than tenant data. The
  mapping is written from `/api/admin/resource-class/{name}/project`.

## Volume Isolation

`JobSpec.requireVolumesIn(project)` validates that all requested volumes exist, belong to the project,
are in `READY` state, share the same workerEntity host, and specify valid mount paths. Cross-workerEntity volume
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

