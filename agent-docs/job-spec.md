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
- **Resolution**: `JobSpecTemplate.findVisibleFetched(projectId, id)` resolves templates visible to the project; `listGlobalFetched` / `findGlobalFetched` see only the global ones.
- **Managed from two places**: project templates through `POST|DELETE /project/{projectId}/job/template[/{id}]` (`job:template:manage`), global ones through `/api/admin/template`. Each refuses the other's scope.
- **A global template names a global resource class** and mounts no volumes. Both belong to one project, so neither is visible to the other projects the template is offered to.
- **Inbound specs arrive as `JobSpecRequest`**, not `JobSpec`: the spec's compact constructor rejects a missing image, and a throw during deserialization is a bodiless 400. `CreateTemplateRequest.check` validates the payload where the resource can answer with a message.
- **Deleting a template leaves queued entries alone**: `job.template_id` carries no foreign key and a queue entry holds its request as jsonb. An entry that outlives its template throws `NotFoundException` on its next attempt and `PendingJobDispatcher` marks it `FAILED` — it does not loop.

### Resource Classes (`ResourceClass`)
- **Keying**: Composite primary key `(name, projectId)` via `@IdClass(ResourceClassId.class)`.
- **Global Sentinel**: Global classes use `ResourceClass.GLOBAL` (`Reserved.ID`).
- **Shadowing**: `ResourceClass.findVisible(projectId, name)` queries the project-specific row first, falling back to global if absent. Project-specific classes shadow global classes sharing the same name.
- **Foreign Keys**: References use two-column FKs `(resource_class, resource_class_project)` on `job` and `job_spec_template`.
- **Override Rule**: Overriding a resource class requires `job:resource-class`. Retaining the template's default class does not require this permission.

## Volume Isolation

A job may only mount worker volumes owned by its project (`JobSpec.requireVolumesIn(project)`). Mounting volumes from another project is rejected.

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

