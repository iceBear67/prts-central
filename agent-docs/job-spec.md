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
- **Keying**: `name` is the whole primary key. The catalogue is service-wide — a class belongs to no project, and every project draws from the same one.
- **Foreign Keys**: `job.resource_class` and `job_spec_template.resource_class` reference `resource_class (name)`.
- **Lookup**: `ResourceClass.findByName(name)`, hand-written rather than a bare `findByIdOptional` so `JobLauncherTest` can mock it with `mockStatic`.
- **Management**: `/api/admin/resource-class` (`admin:all`) is the only write path — a worker reports the capacity it has but never declares a class, so a fresh install can create neither a template nor a job until an admin defines one. Deletion is refused (409) while a job or template still names it, since both hold the FK above and a job is kept as the record of what ran.
- **Override Rule**: Overriding a resource class requires `job:resource-class`. Retaining the default does not — and a `TaskScope.resourceClass` stands in for the template's as that default, since both were set by someone already permitted to: a template costs `job:template:manage`, and a task scope is charged `job:resource-class` by `TaskScope.authorize` when it is written.

## Volume Isolation

`JobSpec.requireVolumesIn(project)` rejects a spec whose volumes are unknown, belong to another project,
are not `READY`, **span more than one worker**, or mount at an unusable path. The cross-worker check
belongs here rather than at placement: `WorkerScheduler` collapses every reason it cannot place a job
into `"no available worker can run this job"`, so a cross-worker spec would silently back off until it
expired.

`JobSpec.VolumeSpec.MOUNT_POINT` is the path rule — an absolute path of non-empty segments, no `.` or
`..`, no whitespace or control characters, no trailing slash, at most 512 characters. The control plane
never resolves these paths; it forwards them to a worker that turns them into bind mounts, which is
precisely why it may not pass a traversing one along. `requireVolumesIn` runs on the merged spec after
`TaskScope.bindTo`, so it covers task mounts and caller overrides alike; `AttachVolumeRequest` declares
the same constant so `PUT .../task/{taskId}/volume/{volumeId}` refuses at the edge instead of at
dispatch.

## Task Layer

Values a `Task` contributes are merged in `JobLauncher.resolve` around the override, never through it:
`TaskScope.defaultsTo` goes underneath (a job may specialize), `TaskScope.bindTo` on top (volumes and
identity, which it may not). Neither takes a `JobSpecOverrideAuthorizer` — see
[task-scope.md](task-scope.md) for why routing them through `applyTo` would charge the requester for the
task's own permissions. What the scope carries is gated once instead, by `TaskScope.authorize` at the
task write endpoints.

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

