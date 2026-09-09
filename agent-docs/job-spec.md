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
- **Global Template Constraints**: Global templates may only reference global resource classes and cannot mount volumes, since volumes and project-scoped resource classes belong to specific projects.
- **Request Payloads**: Inbound templates accept `JobSpecRequest` rather than `JobSpec`. Bean Validation validates required fields such as `image`, returning informative validation errors.
- **Template Deletion and Pending Jobs**: Deleting a template does not alter existing queue entries (`job.template_id` does not enforce a foreign key, and pending entries store the request payload as `jsonb`). If a pending job's referenced template no longer exists during dispatch, `PendingJobDispatcher` catches the `NotFoundException` and transitions the job to `FAILED`.

### Resource Classes (`ResourceClass`)
- **Keying**: Composite primary key `(name, projectId)` via `@IdClass(ResourceClassId.class)`.
- **Global Sentinel**: Global classes use `ResourceClass.GLOBAL` (`Reserved.ID`).
- **Shadowing**: `ResourceClass.findVisible(projectId, name)` queries the project-specific row first, falling back to global if absent. Project-specific classes shadow global classes sharing the same name.
- **Foreign Keys**: References use two-column FKs `(resource_class, resource_class_project)` on `job` and `job_spec_template`.
- **Override Rule**: Overriding a resource class requires `job:resource-class`. Retaining the default does not — and a `TaskScope.resourceClass` stands in for the template's as that default, since both were set by someone already permitted to.

## Volume Isolation

`JobSpec.requireVolumesIn(project)` rejects a spec whose volumes are unknown, belong to another project,
are not `READY`, or **span more than one worker**. The last check belongs here rather than at placement:
`WorkerScheduler` collapses every reason it cannot place a job into `"no available worker can run this
job"`, so a cross-worker spec would silently back off until it expired.

## Task Layer

Values a `Task` contributes are merged in `JobLauncher.resolve` around the override, never through it:
`TaskScope.defaultsTo` goes underneath (a job may specialize), `TaskScope.bindTo` on top (volumes and
identity, which it may not). Neither takes a `JobSpecOverrideAuthorizer` — see
[task-scope.md](task-scope.md) for why routing them through `applyTo` would charge the requester for the
task's own permissions.

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

