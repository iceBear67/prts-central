# HTTP Surface Reference

Base path is `/api` (`quarkus.rest.path = /api`). All `/api/*` endpoints require authentication unless explicitly unsecured.

## Role & Permission Mapping

| Scope | Min Role | Permissions | Endpoints & Operations |
| --- | --- | --- | --- |
| **Project Read** | `VIEWER` | `project:read`, `job:read`, `job:log:read`, `job:artifact:read` | `GET /project/{projectId}`<br/>`GET .../job`<br/>`GET .../job/{id}`<br/>`GET .../job/{id}/log`<br/>`GET .../job/artifact/{id}` |
| **Job Operations** | `MEMBER` | `job:create`, `job:cancel`, `project:secret:read` | `POST .../job`<br/>`POST .../job/{id}/cancel`<br/>`GET .../secret` (names only) |
| **Project Admin** | `OWNER` | `project:update`, `project:delete`, `project:member:manage`, `project:subaccount:manage`, `project:secret:manage` | `PATCH /project/{projectId}`<br/>`DELETE /project/{projectId}`<br/>`PUT/DELETE .../member/{userId}`<br/>`POST/PUT/DELETE .../subaccount/...`<br/>`POST/PATCH/DELETE .../secret/{name}` |
| **Global Admin** | None (`admin:all`) | `Perm.ADMIN_OF_ALL` | `GET /worker`<br/>`POST /worker/{id}/enable\|disable`<br/>Bypasses all project permission checks |

### Special Permission Rules
- **Template Spec Hiding**: `GET .../job/template` requires `project:read`. However, reading template `spec` and `resourceClass` requires explicit `job:template:read` (`defaultRole = NONE`). Without it, those fields are returned as `null`.
- **Self-Removal / Leaving**: `DELETE .../member/{userId}` allows users to remove themselves without `project:member:manage`. The last remaining `OWNER` cannot leave or be demoted.
- **Project Enumeration Prevention**: Non-members querying nonexistent projects receive 403 Forbidden from the interceptor, not 404. Only `admin:all` callers reach the resource to receive 404.

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

