# HTTP Surface Reference

Base path is `/api` (`quarkus.rest.path = /api`). All `/api/*` endpoints require authentication unless explicitly unsecured.

## Role & Permission Mapping

| Scope | Min Role | Permissions | Endpoints & Operations |
| --- | --- | --- | --- |
| **Project Read** | `VIEWER` | `project:read`, `job:read`, `job:log:read`, `job:artifact:read`, `task:read` | `GET /project/{projectId}`<br/>`GET .../job`<br/>`GET .../job/{id}`<br/>`GET .../job/{id}/log`<br/>`GET .../job/artifact/{id}`<br/>`GET .../volume`<br/>`GET .../task[/{id}]`<br/>`GET .../task/{id}/volume` |
| **Job Operations** | `MEMBER` | `job:create`, `job:cancel`, `project:secret:read`, `task:manage` | `POST .../job`<br/>`POST .../job/{id}/cancel`<br/>`GET .../secret` (names only)<br/>`POST/PATCH/DELETE .../task[/{id}]`<br/>`PUT/DELETE .../task/{id}/volume/{volumeId}` |
| **Project Admin** | `OWNER` | `project:update`, `project:delete`, `project:archive`, `project:transfer`, `project:member:manage`, `project:subaccount:manage`, `project:secret:manage`, `project:volume:manage`, `job:template:manage`, `job:artifact:delete` | `PATCH /project/{projectId}`<br/>`DELETE /project/{projectId}`<br/>`POST .../archive\|unarchive`<br/>`POST .../transfer`<br/>`PUT/DELETE .../member/{userId}`<br/>`POST/PUT/DELETE .../subaccount/...`<br/>`POST/PATCH/DELETE .../secret/{name}`<br/>`POST/DELETE .../job/template[/{id}]`<br/>`DELETE .../job/artifact/{id}`<br/>`POST .../volume`, `DELETE .../volume/{volumeId}` |
| **Project Creation** | None (global) | `project:create` | `POST /project` — the caller becomes its `OWNER`. Sub-accounts cannot (409): they hold no project role. |
| **Global Admin** | None (`admin:all`) | `Perm.ADMIN_OF_ALL` | `GET /admin/stats\|project\|user\|template\|permission`<br/>`/worker` and everything under it<br/>Bypasses all project permission checks |

### Special Permission Rules
- **Template Spec Hiding**: `GET .../job/template` requires `project:read`. However, reading template `spec` and `resourceClass` requires explicit `job:template:read` (`defaultRole = NONE`). Without it, those fields are returned as `null`. When creating a template, the response returns the full template definition directly.
- **Self-Removal / Leaving**: `DELETE .../member/{userId}` allows users to remove themselves without `project:member:manage`. The last remaining `OWNER` cannot leave or be demoted.
- **Project Enumeration Prevention**: Non-members querying nonexistent projects receive 403 Forbidden from the interceptor, not 404. Only `admin:all` callers reach the resource to receive 404.
- **Global Templates Are Read-Only Here**: `DELETE .../job/template/{id}` returns 409 Conflict for global templates (where `projectId` is null). Global templates must be managed via `/api/admin/template`.

## Archived Projects

`POST /project/{projectId}/archive` sets a project to read-only status. During archiving, `ProjectService.archive` invokes `stopWork` to cancel pending queue items and interrupt active running jobs.

Mutating endpoints within a project call `ProjectService.requireWritable(projectId)` and return **409 Conflict** when the project is archived (such as renaming or describing, ownership transfer, member/subaccount management, secrets, job creation/cancellation, template operations, artifact deletion, task and volume operations). Read requests remain allowed, as do `POST .../unarchive` and `DELETE /project/{projectId}`.

`TaskService.requireOpen` is the same idea one level down: a task that is `CLOSING` or `CLOSED` conflicts on any write to it, and on any job naming it.

Because writability is checked explicitly rather than through an interceptor, any new mutating project endpoint must call `requireWritable(projectId)`. Worker reporting endpoints (`JobService.applyState`, `appendLog`, `ArtifactService.record`) intentionally omit this check so in-flight status reports are not rejected.

## Jobs API Behavior

### 1. Asynchronous Queueing (`POST /project/{projectId}/job`)
- Does not create an active `Job` immediately. Enqueues an authorized `JobRequest` and returns `201 Created` with `PendingJobView`.
- Polled or resolved once `PendingJobDispatcher` assigns a worker.
- Endpoints return the sealed interface `JobStatusView` (`type: "job"` or `"pending"`) to preserve Jackson discriminator serialization.

### 2. Dual-ID Routing
`GET /project/{projectId}/job/{id}` and `POST .../job/{id}/cancel` accept either:
- The persistent `Job` UUID.
- The `PendingJob` queue entry UUID (automatically resolves to the resulting `Job` once placed).

### 3. Replay & Re-runs
No server-side automatic re-run endpoint exists. Clients retrieve the original `createRequest` from `JobView` and submit it as a new `POST .../job`. The request pins the resolved `resourceClass` from the original execution, and its `taskId`, so a replay lands back in the same task.

### 4. Tasks
A job joins a task through `CreateJobRequest.taskId`, not a nested path — `GET .../job?task={taskId}`
narrows the listing. This keeps job creation under `/project/{projectId}/...`, which
`JobSpecOverridePermissions` depends on: its gating methods take no `@ProjectId` parameter and resolve
scope from the literal `{projectId}` path parameter, so moving the endpoint would silently disable
per-field override checks.

`JobResource.createJob` does **not** repeat a task check. `JobLauncher.resolve` needs the task anyway to
merge its scope, and conflicts there (409) if it is closing or closed. See [task-scope.md](task-scope.md).

## Volumes

`POST /project/{projectId}/volume` blocks on the worker's acknowledgment and returns its refusal reason
verbatim when it has no room; `DELETE .../volume/{volumeId}` returns 409 while any task still mounts it.
Mounting and unmounting are task operations (`PUT|DELETE .../task/{taskId}/volume/{volumeId}`) and cost
only `task:manage` — allocating disk is the privileged part, not choosing a path.

The scheduler picks the host; a caller never names a worker. `/worker/{id}/volume` remains the admin's
cross-project view.

## Admin Surface (`/api/admin`, all `admin:all`)

| Endpoint | Notes |
| --- | --- |
| `GET /admin/stats` | Service-wide counters: users, projects, workers (registered/disabled/connected/schedulable), jobs by state + completed in the last 24h, queue by state, artifact count and bytes. |
| `GET /admin/project?query=&offset=&length=` | Every project with member, visible-job and queued counts. A listing only — an admin already reaches each project's own endpoints. |
| `GET /admin/user?query=&offset=&length=` | Search by name or email. `subAccountOf` is the owning project, or null for a person. |
| `GET /admin/user/{userId}` | Memberships plus every grant, split into global and per-project. |
| `PUT /admin/user/{userId}/permission/global` | Replaces the system-wide grants. Rejects project-scoped permissions (400) and sub-accounts (409). |
| `PUT /admin/user/{userId}/permission/project/{projectId}` | Replaces that project's grants (`UserService.setPermissions`). |
| `DELETE /admin/user/{userId}/permission` | Revokes every grant, in any scope. |
| `GET /admin/permission` | The whole `Perm` catalogue with each one's ban state. Read-only — bans come from `permission.banned`, see [authorization.md](authorization.md). |
| `GET\|POST /admin/template`, `DELETE /admin/template/{id}` | The templates every project may use. Project templates are invisible here (404 on delete). |
| `PATCH /worker/{id}` | Rename. Holds only until the worker registers again under a name of its own — `Worker.upsert` takes the name from the registration. |
| `DELETE /worker/{id}` | Drops the registration. 409 while connected, holding unfinished jobs, or hosting volumes (`worker_volume` carries a plain foreign key). |
| `POST /worker/{id}/disconnect` | Closes the session; its unfinished jobs fail as on any disconnect. Deliberately separate from the delete. |
| `GET /worker/{id}/job\|volume` | The worker's unfinished jobs, and the volumes it hosts across projects. |

Mutating endpoints under `/admin/user` do not wrap their operations in a resource-level transaction:
the permission grant cache is invalidated when the service transaction commits, so re-reading the user
within the same transaction would return pre-commit data.

Listing page sizes are capped by `admin.list.max-page-size` (`AdminConfig`), matching `job.list`.

## Current User

`GET /api/user` returns the account the request authenticated as — identity, `subAccountOf` (the owning project, or null for a person), the permission identifiers granted globally (`admin:all` among them), and `projects`, the caller's standing in each project they reach, keyed by project ID.

A `projects` entry carries the `role` held and the `permissions` granted on top of it. Either half alone puts a project in the map: a member with no explicit grant reports its role against an empty list, and a grant made in a project the caller is no member of reports `NONE`. Permissions a role already implies are never listed, so a client gating on this must read the role too. Authentication is the only requirement, so sub-accounts reach it as well.

## Token & Secret Endpoints

- `GET /api/user/token` & `PUT /api/user/token`: Personal access token management for the current user. Returns the plaintext token only upon initial `PUT`. Sub-accounts cannot access this endpoint.
- `PUT /api/project/{projectId}/subaccount/{userId}/token`: Project owner endpoint to mint/rotate a sub-account token.
- `POST /api/project/{projectId}/secret`: Creates a secret (conflicts on duplicate name). Names must match `[A-Za-z_][A-Za-z0-9_]{0,63}`.
- `PATCH .../secret/{name}`: Updates `description` (cleared if blank) or `value` (re-sealed under project context).

## DTO & Exception Architecture

- **DTO Structure**: Located under `io.ib67.prts.dto` (`dto.admin`, `dto.job`, `dto.project`, `dto.request`). Resources map entities to DTOs; service methods return entities.
- **Request Validation**: Inbound DTO records define Bean Validation constraints with explicit error messages. Resource methods accept them via `@NotNull(message = "a request body is required") @Valid`. The compact constructor only normalizes input (e.g. `strip()`). Rules not expressible as standard annotations (such as cross-field dependencies in `UpdateSecretRequest` and `UpdateProjectRequest`, excluding enum values in `SetMemberRoleRequest`, dynamic limits from `SecretConfig`, or permission lookups in `SetPermissionsRequest.resolved()`) are checked in code. Constraints are reflected in the OpenAPI schema (`required`, `pattern`, `minLength`, `minimum`).
- **Exception Mapping**: All 4xx and 5xx responses return `{ "message": ... }`, with the exception of 401.
  - `NoSuchElementException`: Mapped to 404 by `NotFoundMapper` with the exception message.
  - `ClientErrorMapper`: Formats 4xx exceptions into `{ "message": ... }`.
  - **403**: `RequirePermissionInterceptor` throws Quarkus's `io.quarkus.security.ForbiddenException` (a `SecurityException`). `ForbiddenMapper` maps this to the standard error JSON body.
  - **401**: Returned with an empty body as the authentication challenge. In the `web-app` OIDC flow, adding a body would override the browser redirect to the OIDC provider.
  - **Constraint Violations**: `ConstraintViolationMapper` formats violations into `{ "message": ... }`, sorting by property path for deterministic output. This mapper takes precedence over Quarkus's default `ResteasyReactiveViolationExceptionMapper`. Violations on method return values are rethrown as 500 internal server errors.
  - **Deserialization Failures**: `ServerJacksonMessageBodyReader` wraps Jackson's `DatabindException` into a generic `WebApplicationException` with status 400. `ClientErrorMapper` unwraps the cause chain to preserve the original exception message and status thrown from constructors. Malformed JSON without an underlying application exception retains the default 400 response.
- **OpenAPI**: `EndpointOASFilter` runs at build time to augment the OpenAPI document with inferred metadata:
  - A **`default` response** with `ErrorView` is added to every operation to document the standard error format without enumerating all possible codes.
  - Common status codes are inferred from method signatures: **401** on all operations, **400** on methods taking request bodies, **404** on methods with path parameters, and **409** on mutating project endpoints under `/project/{projectId}` (due to `requireWritable`, except archive/unarchive).
  - The **success status** is inferred from `@ResponseStatus`, defaulting to 204 for `void` methods (correcting SmallRye's default assumption of 200/201).
  - Error responses include the `ErrorView` schema, except 401 which has an empty body.
  - Custom `@APIResponse` annotations are reserved for special responses (such as `JobResource.createJob`'s `JobStatusView` schema), as SmallRye replaces generated success responses when manual annotations are present.

