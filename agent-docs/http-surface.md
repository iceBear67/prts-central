# HTTP Surface Reference

Base path is `/api` (`quarkus.rest.path = /api`). All `/api/*` endpoints require authentication unless explicitly unsecured.

## Role & Permission Mapping

| Scope | Min Role | Permissions | Endpoints & Operations |
| --- | --- | --- | --- |
| **Project Read** | `VIEWER` | `project:read`, `job:read`, `job:log:read`, `job:artifact:read` | `GET /project/{projectId}`<br/>`GET .../job`<br/>`GET .../job/{id}`<br/>`GET .../job/{id}/log`<br/>`GET .../job/artifact/{id}` |
| **Job Operations** | `MEMBER` | `job:create`, `job:cancel`, `project:secret:read` | `POST .../job`<br/>`POST .../job/{id}/cancel`<br/>`GET .../secret` (names only) |
| **Project Admin** | `OWNER` | `project:update`, `project:delete`, `project:archive`, `project:transfer`, `project:member:manage`, `project:subaccount:manage`, `project:secret:manage`, `job:template:manage`, `job:artifact:delete` | `PATCH /project/{projectId}`<br/>`DELETE /project/{projectId}`<br/>`POST .../archive\|unarchive`<br/>`POST .../transfer`<br/>`PUT/DELETE .../member/{userId}`<br/>`POST/PUT/DELETE .../subaccount/...`<br/>`POST/PATCH/DELETE .../secret/{name}`<br/>`POST/DELETE .../job/template[/{id}]`<br/>`DELETE .../job/artifact/{id}` |
| **Project Creation** | None (global) | `project:create` | `POST /project` — the caller becomes its `OWNER`. Sub-accounts cannot (409): they hold no project role. |
| **Global Admin** | None (`admin:all`) | `Perm.ADMIN_OF_ALL` | `GET /admin/stats\|project\|user\|template\|permission`<br/>`/worker` and everything under it<br/>Bypasses all project permission checks |

### Special Permission Rules
- **Template Spec Hiding**: `GET .../job/template` requires `project:read`. However, reading template `spec` and `resourceClass` requires explicit `job:template:read` (`defaultRole = NONE`). Without it, those fields are returned as `null`. The template the caller just created is returned in full — they wrote it.
- **Self-Removal / Leaving**: `DELETE .../member/{userId}` allows users to remove themselves without `project:member:manage`. The last remaining `OWNER` cannot leave or be demoted.
- **Project Enumeration Prevention**: Non-members querying nonexistent projects receive 403 Forbidden from the interceptor, not 404. Only `admin:all` callers reach the resource to receive 404.
- **Global Templates Are Read-Only Here**: `DELETE .../job/template/{id}` refuses a template with no project (409). They belong to `/admin/template`.

## Archived Projects

`POST /project/{projectId}/archive` turns a project read-only. `ProjectService.archive` runs the same
`stopWork` as a delete first — the queue is cancelled and running jobs interrupted — so nothing is left
running that the now-refused cancel endpoint could no longer stop.

Every mutating endpoint of a project calls `ProjectService.requireWritable(projectId)` first and answers
**409** while it is archived: rename, transfer, member and sub-account management, secrets, job create
and cancel, template create and delete, artifact delete. Reads are untouched, and so are
`POST .../unarchive` and `DELETE /project/{projectId}` — an archived project is never stuck.

The guard is an explicit call, not an interceptor: **a new mutating endpoint has to add it.** Worker
report paths (`JobService.applyState`, `appendLog`, `ArtifactService.record`) deliberately have no
guard, or an archive would break jobs still reporting in.

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
No server-side automatic re-run endpoint exists. Clients retrieve the original `createRequest` from `JobView` and submit it as a new `POST .../job`. The request pins the resolved `resourceClass` from the original execution.

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

The mutating `/admin/user` endpoints hold **no transaction of their own**: the grant cache is invalidated
when the service's transaction completes, so reading the user back inside it would answer from the
pre-change snapshot.

Listing windows are capped by `admin.list.max-page-size` (`AdminConfig`), the same shape as `job.list`.

## Token & Secret Endpoints

- `GET /api/user/token` & `PUT /api/user/token`: Personal access token management for the current user. Returns the plaintext token only upon initial `PUT`. Sub-accounts cannot access this endpoint.
- `PUT /api/project/{projectId}/subaccount/{userId}/token`: Project owner endpoint to mint/rotate a sub-account token.
- `POST /api/project/{projectId}/secret`: Creates a secret (conflicts on duplicate name). Names must match `[A-Za-z_][A-Za-z0-9_]{0,63}`.
- `PATCH .../secret/{name}`: Updates `description` (cleared if blank) or `value` (re-sealed under project context).

## DTO & Exception Architecture

- **DTO Structure**: Located under `io.ib67.prts.dto` (`dto.job`, `dto.project`, `dto.request`). Resources map entities to DTOs; service methods return entities.
- **Exception Mapping**:
  - `NoSuchElementException` -> mapped to 404 by `NotFoundMapper`.
  - `ClientErrorMapper` -> wraps 4xx exceptions with structured `{ "message": ... }` responses.
- **OpenAPI**: `PermissionOASFilter` runs at build time to document `@RequirePermission` rules, required roles, and 401/403 responses across the generated OpenAPI schema.

