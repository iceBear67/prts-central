# HTTP Surface Reference

Base path is `/api` (`quarkus.rest.path = /api`). All `/api/*` endpoints require authentication unless explicitly unsecured — the only exception is the liveness probe below.

## Role & Permission Mapping

| Scope | Min Role | Permissions | Endpoints & Operations |
| --- | --- | --- | --- |
| **Project Read** | `VIEWER` | `project:read`, `job:read`, `job:log:read`, `job:artifact:read`, `job:agent:read`, `task:read` | `GET /project/{projectId}`<br/>`GET .../stats`<br/>`GET .../resource-class`<br/>`GET .../job`<br/>`GET .../job/{id}`<br/>`GET .../job/{id}/log`<br/>`GET .../job/{id}/agent/session[/{sessionId}/event]`<br/>`GET .../job/artifact/{id}`<br/>`GET .../volume[/{volumeId}]`<br/>`GET .../task[/{id}]`<br/>`GET .../task/{id}/volume` |
| **Job Operations** | `MEMBER` | `job:create`, `job:cancel`, `job:agent:interact`, `project:secret:read`, `task:manage` | `POST .../job`<br/>`POST .../job/{id}/cancel`<br/>`GET .../secret` (names only)<br/>`POST/PATCH/DELETE .../task[/{id}]`<br/>`PUT/DELETE .../task/{id}/volume/{volumeId}` |
| **Project Admin** | `OWNER` | `project:update`, `project:delete`, `project:archive`, `project:transfer`, `project:member:manage`, `project:subaccount:manage`, `project:secret:manage`, `project:volume:manage`, `job:template:manage`, `job:artifact:delete` | `PATCH /project/{projectId}`<br/>`DELETE /project/{projectId}`<br/>`POST .../archive\|unarchive`<br/>`POST .../transfer`<br/>`PUT/DELETE .../member/{userId}`<br/>`POST/PUT/DELETE .../subaccount/...`<br/>`GET .../permission`<br/>`POST/PATCH/DELETE .../secret/{name}`<br/>`POST .../job/template`, `PATCH/DELETE .../job/template/{id}`<br/>`DELETE .../job/artifact/{id}`<br/>`POST .../volume`, `DELETE .../volume/{volumeId}` |
| **Project Creation** | None (global) | `project:create` | `POST /project` — the caller becomes its `OWNER`. Sub-accounts cannot (409): they hold no project role. |
| **Global Admin** | None (`admin:all`) | `Perm.ADMIN_OF_ALL` | `GET /admin/stats\|project\|user\|template\|resource-class\|permission\|task\|volume\|artifact`<br/>`/worker` and everything under it<br/>Bypasses all project permission checks |
| **Authenticated** | None | — | `GET /user[/token]`, `PUT /user/token`<br/>`GET /project` (the caller's memberships)<br/>`GET /job` (the caller's jobs across projects)<br/>`GET /resource-class` (read-only catalogue) |

### Special Permission Rules
- **Template Spec Hiding**: `GET .../job/template` requires `project:read`. However, reading template `spec` and `resourceClass` requires explicit `job:template:read` (`defaultRole = NONE`). Without it, those fields are returned as `null`. When creating a template, the response returns the full template definition directly.
- **Self-Removal / Leaving**: `DELETE .../member/{userId}` allows users to remove themselves without `project:member:manage`. The last remaining `OWNER` cannot leave or be demoted.
- **Project Enumeration Prevention**: Non-members querying nonexistent projects receive 403 Forbidden from the interceptor, not 404. Only `admin:all` callers reach the resource to receive 404.
- **Global Templates Are Read-Only Here**: `PATCH|DELETE .../job/template/{id}` returns 409 Conflict for global templates (where `projectId` is null). Global templates must be managed via `/api/admin/template`.
- **Resource Class Availability**: `POST|PATCH .../job/template[/{id}]` and `POST .../job` return **403 Forbidden** (`resource class X is not available to this project`) when the class named is neither shared nor granted to the project. `GET .../resource-class` is the listing that says which ones are. See [job-spec.md](job-spec.md).
- **Task Scope Gating**: `POST|PATCH .../task[/{taskId}]` cost `task:manage` (`MEMBER`) on their own, but a `scope` carrying `environment`, `labels` or a `resourceClass` is charged `job:spec:environment`, `job:spec:labels` and `job:resource-class` on top (`TaskScope.authorize`). Those values reach every job under the task without passing the override gate, so `task:manage` would otherwise be a way around all three. See [task-scope.md](task-scope.md).

## Health Check

`GET /api/health` returns **204 No Content** with no checks performed — it proves the HTTP layer answers, which is what a load balancer or orchestrator needs without holding a token. It is unsecured by a `permit` rule on its own exact path in `quarkus.http.auth.permission`; Quarkus matches the longest prefix, so that rule outranks the `/api/*` authenticated policy. The path is listed in `EndpointOASFilter.PUBLIC_PATHS` so the OpenAPI document does not promise a 401 it never answers.

## Archived Projects

`POST /project/{projectId}/archive` sets a project to read-only status. During archiving, `ProjectService.archive` invokes `stopWork` to cancel pending queue items and interrupt active running jobs.

Mutating endpoints within a project call `ProjectService.requireWritable(projectId)` and return **409 Conflict** when the project is archived (such as renaming or describing, ownership transfer, member/subaccount management, secrets, job creation/cancellation, template operations, artifact deletion, task and volume operations). Read requests remain allowed, as do `POST .../unarchive` and `DELETE /project/{projectId}`.

`TaskService.requireOpen` is the same idea one level down: a task that is `CLOSING` or `CLOSED` conflicts on any write to it, and on any job naming it.

Because writability is checked explicitly rather than through an interceptor, any new mutating project endpoint must call `requireWritable(projectId)`. Worker reporting endpoints (`JobService.applyState`, `appendLog`, `ArtifactService.record`) intentionally omit this check so in-flight status reports are not rejected.

## Jobs API Behavior

### 1. Asynchronous Queueing (`POST /project/{projectId}/job`)
- Does not create an active `Job` immediately. Enqueues an authorized `JobRequest` and returns `201 Created` with `PendingJobView`.
- Polled or resolved once `PendingJobDispatcher` assigns a worker.
- Endpoints return the sealed interface `JobStatusView` (`type: "job"` or `"pending"`).
- **Each branch writes `type` itself** (`JobView.type()`, `PendingJobView.type()`), which is why the
  `@JsonTypeInfo` on `JobStatusView` is `EXISTING_PROPERTY` rather than `PROPERTY`. Jackson takes a type
  id from the *declared* type, and a listing declares `Page<T>` — erased by the time its items are
  written, so under `PROPERTY` every row of `GET .../job` would go out without `type` while
  `GET .../job/{id}` carries it. A branch that answers the property itself carries it whatever the
  writer was handed, `Page<JobView>` included.

### 2. Dual-ID Routing
`GET /project/{projectId}/job/{id}` and `POST .../job/{id}/cancel` accept either:
- The persistent `Job` UUID.
- The `PendingJob` queue entry UUID (automatically resolves to the resulting `Job` once placed).

### 3. Replay & Re-runs
No server-side automatic re-run endpoint exists. Clients retrieve the original `createRequest` from `JobView` and submit it as a new `POST .../job`. The request pins the resolved `resourceClass` from the original execution, and its `taskId`, so a replay lands back in the same task.

### 4. Filtering the merged listing
`GET /project/{projectId}/job?task=&state=&offset=&length=` is the only listing that merges two tables,
so `state` names a member of neither enum on its own: `JobStatus` (`io.ib67.prts.job`) is their union,
and a member selects whichever halves recognise it — `CANCELLED` and `FAILED` both, `RUNNING` only a
job, `QUEUED` only a queue entry. A member the other table does not know selects **nothing** there,
which is not the same as leaving it unfiltered; the count follows the same predicate, so `total` and
`items` always agree. `JobStatusTest` asserts the union still covers both enums — a state added to
either without being named here is one the listing cannot be filtered by.

`DISPATCHED` is in the union for completeness and matches no row: an entry that became a job is listed
as that job, and the queue half only lists unplaced entries.

### 5. Tasks
A job joins a task through `CreateJobRequest.taskId`, not a nested path — `GET .../job?task={taskId}`
narrows the listing. This keeps job creation under `/project/{projectId}/...`, which
`JobSpecOverridePermissions` depends on: its gating methods take no `@ProjectId` parameter and resolve
scope from the literal `{projectId}` path parameter, so moving the endpoint would silently disable
per-field override checks.

`JobResource.createJob` does **not** repeat a task check. `JobLauncher.resolve` needs the task anyway to
merge its scope, and conflicts there (409) if it is closing or closed. See [task-scope.md](task-scope.md).

### 6. Agent Sessions

- `GET .../job/{jobId}/agent/session`: Lists ACP sessions for the job. Requires `job:agent:read`.
- `GET .../job/{jobId}/agent/session/{sessionId}/event?offset=&length=`: Paginated JSON-RPC frames for a session, capped by `acp.max-page-size`. Requires `job:agent:read`.

Live ACP interaction uses WebSocket `/ws/project/{projectId}/job/{jobId}/agent` instead of REST. See [agent-acp.md](agent-acp.md).

## Volumes

`POST /project/{projectId}/volume` blocks on the worker's acknowledgment and returns its refusal reason
verbatim when it has no room; `DELETE .../volume/{volumeId}` returns 409 while any task still mounts it.
`GET .../volume/{volumeId}` answers with the volume and its `mounts` — `task_volume` is many-to-many, and
which tasks hold it is exactly what that 409 would otherwise be the only way to learn.
Mounting and unmounting are task operations (`PUT|DELETE .../task/{taskId}/volume/{volumeId}`) and cost
only `task:manage` — allocating disk is the privileged part, not choosing a path.

The scheduler picks the host; a caller never names a worker. `/worker/{id}/volume` remains the admin's
cross-project view.

## Cross-Project Reads

Two endpoints answer across every project the caller reaches, so a client does not have to fan out one
request per project and merge:

- `GET /api/job?project=&state=&worker=&since=&offset=&length=` — the caller's jobs, newest first.
  Scope comes from `JobAccess.readableProjects()`: the projects they are a member of and those they hold
  a grant in, each put through the same `job:read`/`VIEWER` gate the per-project listing uses, so the
  endpoint carries no `@RequirePermission` of its own and a ban still applies. `admin:all` drops the
  predicate entirely. `JobView.createRequest` is omitted — it is gated per project.
  It merges no queue entries, unlike `GET /project/{projectId}/job`: a job that is still queued is in
  the project's listing and not in this one.
- `GET /api/resource-class?query=&offset=&length=` — the resource class catalogue, readable by any
  authenticated caller. Classes are service-wide configuration, not per-tenant data, and a member who can
  submit into one can already observe its limits by running something. Writes stay under `/admin`.
  `GET /project/{projectId}/resource-class` narrows the same catalogue to what one project may
  actually name, which is what a job form or a task scope editor offers.

## Statistics

`GET /project/{projectId}/stats` is the admin dashboard's aggregation with a `project_id` predicate,
behind `project:read`/`VIEWER`. `StatsService` (`io.ib67.prts.stats`) owns both: every series there takes
a nullable project, which is why it is not in the `admin` package.

`jobs.dailyLast90d` mirrors `hourlyLast24h` one UTC day at a time, zero-filled and oldest first — days
cannot be derived from 24 hours of buckets, and the calendar heatmap needs a quarter of them. Bucketing
stays in Java for the reason the hourly series already documents: HQL has no portable time truncation,
and Hibernate owns the `Instant`-to-column conversion.

`jobs.hourlyLast30d` is the hourly series widened to 720 buckets, published by **both** stats
endpoints: a day is too coarse a cell for a heatmap drawn across the width of a dashboard, and 24
hours is one day rather than a window to scan. Its last 24 buckets are `hourlyLast24h`, which is
published on its own because `completedLast24h` is summed from it.

`StatsService.completions(projectId)` reads the **longest** window once — the daily series spans the
others — and `hourlyLast(...)` / `dailyLast(...)` bucket that one list three ways. A second query for
the hours inside the days would scan rows the first already holds, and two reads taken at different
instants can disagree about the bucket they share.

## Admin Surface (`/api/admin`, all `admin:all`)

| Endpoint | Notes |
| --- | --- |
| `GET /admin/stats` | Service-wide counters: users, projects, workers (registered/disabled/connected/schedulable), jobs by state, tasks by state, queue by state, artifact count and bytes, volume count and allocated bytes, and `system` (`startedAt`, `version`, active profile). `jobs.hourlyLast24h` is 24 hour-aligned buckets of terminal-state completions, oldest first, zero-filled; `completedLast24h` is their sum, so the scalar and the series always agree. `jobs.hourlyLast30d` is the same series over 720 buckets and `jobs.dailyLast90d` the same again at day resolution — one read of the widest window, bucketed three ways. Every `byState` grouping publishes all of its enum's constants, zero-filled (`dto.Counts.filled`). `StatsService` owns the series and the start time — they belong to no entity because only a dashboard wants them. |
| `GET /admin/project?query=&offset=&length=` | Every project with member, visible-job and queued counts. A listing only — an admin already reaches each project's own endpoints. |
| `GET /admin/user?query=&offset=&length=` | Search by name or email. `subAccountOf` is the owning project, or null for a person. |
| `GET /admin/user/{userId}` | Memberships plus every grant, split into global and per-project. |
| `PUT /admin/user/{userId}/permission/global` | Replaces the system-wide grants. Rejects project-scoped permissions (400) and sub-accounts (409). |
| `PUT /admin/user/{userId}/permission/project/{projectId}` | Replaces that project's grants (`UserService.setPermissions`). |
| `DELETE /admin/user/{userId}/permission` | Revokes every grant, in any scope. |
| `GET /admin/permission` | The whole `Perm` catalogue with each one's ban state, `category` and `description`. Read-only — bans come from `permission.banned`, see [authorization.md](authorization.md). The prose lives on the `Perm` constants so a permission added there cannot appear in a picker as a bare identifier with nothing failing to make that visible. |

`GET /project/{projectId}/permission` publishes the `project` half of that same catalogue and is **not** an
admin endpoint: it is gated on `project:subaccount:manage` (`defaultRole: OWNER`), the permission that
governs `PUT .../subaccount/{userId}/permission`. An owner allowed to set a sub-account's grants has to be
able to read the list to choose them from, and `banned` travels with each entry — a ban overrides grants,
roles and `admin:all` alike, so without it an owner would record a grant that silently has no effect. The
answer does not vary by project; `{projectId}` is what the endpoint authorizes against.
| `GET /admin/task?query=&state=&offset=&length=` | Every project's tasks in one listing. `query` matches the task name; `TaskView` names the owning project. Shares `Task.search` with the project-scoped listing, which passes a project id where this passes null. |
| `GET /admin/volume?worker=&project=&state=&query=&offset=&length=` | Every worker's volumes in one listing. `/worker/{id}/volume` answers the same question one host at a time. |
| `GET /admin/artifact?project=&job=&offset=&length=` | Stored artifacts across projects, newest first, as `AdminArtifactView` (which names the job and project a bare `ArtifactView` does not, and carries `projectArchivedAt` so a client can gate the per-row delete on the same state that endpoint checks). Downloads still go through `GET /project/{projectId}/job/artifact/{id}`, where the presigned URL and the project's own read permission live. |
| `GET\|POST /admin/template`, `PATCH\|DELETE /admin/template/{id}` | The templates every project may use, `?query=` matching the name. Project templates are invisible here (404 on delete and on update). `PATCH` applies whichever of `name`, `resourceClass` and `spec` the body carries; a supplied `spec` **replaces** the stored one rather than merging, since merging cannot remove an environment entry. It updates in place because the ID is what a queued job and a re-run payload hold. |
| `GET\|POST /admin/resource-class`, `PATCH\|DELETE /admin/resource-class/{name}` | Manages the service-wide resource class catalogue (keyed by name), `?query=` matching the name. Workers report physical capacity but do not define classes. `numCpus`, `memCount` and `diskSize` are **unitless** minimums compared as they stand against what a worker reports (`WorkerScheduler.capacityFits`), so only the workers of a deployment know what the last two count in; `ResourceClassView.FIGURES` publishes that on the view and on both request bodies rather than leaving a client to read `65536` as MiB on a guess. `DELETE` returns 409 Conflict if referenced by existing jobs or templates. The read half is also published unauthenticated-by-role at `GET /api/resource-class`. |
| `GET /admin/resource-class/{name}/project`, `PUT\|DELETE .../{projectId}` | Which projects may name a class that is not `shared`. `PUT` is idempotent and answers 204; `DELETE` is 404 when there was no grant, so a stale admin page does not report success. Grants are recorded and kept while a class is shared — un-sharing restores the list rather than emptying it. The other direction of the same answer is `GET /project/{projectId}/resource-class`, which an `admin:all` caller reaches for any project. See [job-spec.md](job-spec.md). |
| `PATCH /worker/{id}` | Rename. Holds only until the worker registers again under a name of its own — `WorkerEntity.upsert` takes the name from the registration. |
| `DELETE /worker/{id}?force=` | Drops the registration, answering `WorkerRemovalView` (`volumesDropped`, `jobsFailed`). 409 while connected either way — a live session means the host is not gone, and `POST .../disconnect` says so explicitly. Without `force`, also 409 while it holds unfinished jobs or hosts volumes (`worker_volume` carries a plain foreign key). `force=true` is the operator stating the host is never coming back: the volume rows go without the RPC a release normally needs (their task mounts follow through `task_volume`'s `ON DELETE CASCADE`) and the jobs it held fail, since nothing will report on them. Storage is abandoned, not reclaimed — which is what the counts say. |
| `POST /worker/{id}/disconnect` | Closes the session; its unfinished jobs fail as on any disconnect. Deliberately separate from the delete. |
| `GET /worker/{id}/job?state=&since=&offset=&length=` | The worker's job history, newest first, terminal states included — a per-host timeline needs the finished ones and the gaps between them. |
| `GET /worker/{id}/volume` | The volumes it hosts across projects. |

Mutating endpoints under `/admin/user` do not wrap their operations in a resource-level transaction:
the permission grant cache is invalidated when the service transaction commits, so re-reading the user
within the same transaction would return pre-commit data.

Listing page sizes are capped by `admin.list.max-page-size` (`AdminConfig`), matching `job.list`.

## Pagination

Unbounded listings accept `?offset=&length=` with parameters bounded via `Pages.clampLength(length, max)` and `Pages.clampOffset(offset, window)`. An omitted `length` defaults to the configured maximum page size.

Every paged listing returns `Page<T>` (`io.ib67.prts.dto.Page`) rather than a bare array:

```jsonc
{ "items": [ /* … */ ], "offset": 0, "length": 50, "total": 213 }
```

`length` echoes the **clamped** window, which is the only place the configured maximum is published; `total` is what the listing would return unwindowed. Without both, a client cannot tell a page the server truncated from a page that is simply full, and learns a listing has ended only when a page comes back empty, so it cannot draw a pager. A new paged endpoint therefore adds a count finder beside its `search`/`list` one (`Task.countSearch`, `WorkerVolume.countSearch`, `JobLog.countByJob`, …) rather than returning a list.

Where the same predicate feeds both halves, it travels as one object rather than as a repeated argument list: `Job.listVisible(filter, offset, length)` and `Job.countVisible(filter)` take a `Job.Filter` built once by the caller (`GET /project/{projectId}/job` through `JobService`, `GET /api/job`, `GET /worker/{id}/job`). A listing and its total narrowed by two separate argument lists can drift; one filter cannot.

`GET /project/{projectId}/job` merges two tables, so its `total` is
`JobService.countVisible(...) + PendingJobService.countUnplaced(...)` — each taking the same task and
state the listing half did, or the total would count rows the page cannot show.

Naturally bounded collections remain unpaged (e.g., `/admin/permission`, `/project/{projectId}/permission`, `.../task/{id}/volume`, `.../job/{jobId}/agent/session`, and `GET /project`). Unpaged entity finders are reserved for internal routines that require complete result sets (such as secret resolution during dispatch or project teardown) and must not be exposed by unbounded endpoints.

## Current User

`GET /api/user` returns the account the request authenticated as — identity, `subAccountOf` (the owning project, or null for a person), the permission identifiers granted globally (`admin:all` among them), and `projects`, the caller's standing in each project they reach, keyed by project ID.

A `projects` entry carries the `role` held and the `permissions` granted on top of it. Either half alone puts a project in the map: a member with no explicit grant reports its role against an empty list, and a grant made in a project the caller is no member of reports `NONE`. Permissions a role already implies are never listed, so a client gating on this must read the role too. Authentication is the only requirement, so sub-accounts reach it as well.

## Token & Secret Endpoints

- `GET /api/user/token` & `PUT /api/user/token`: Personal access token management for the current user. Returns the plaintext token only upon initial `PUT`. Sub-accounts cannot access this endpoint.
- `POST /api/project/{projectId}/subaccount`: `CreateSubAccountRequest` carries `permissions` and `issueToken` alongside the name, so a usable account costs one write rather than three. The resource method is `@Transactional` and the three services it calls are `REQUIRED`, so a rejected permission name or a failed mint leaves nothing half-made. The token comes back once, on `SubAccountView.token`; it is null everywhere else. Editing a live sub-account goes through `PUT .../permission` and `PUT .../token`.
- `PUT /api/project/{projectId}/subaccount/{userId}/token`: Project owner endpoint to mint/rotate a sub-account token.
- `POST /api/project/{projectId}/secret`: Creates a secret (conflicts on duplicate name). Names must match `[A-Za-z_][A-Za-z0-9_]{0,63}`.
- `PATCH .../secret/{name}`: Updates `description` (cleared if blank) or `value` (re-sealed under project context).

## DTO & Exception Architecture

- **DTO Structure**: Located under `io.ib67.prts.dto` (`dto.admin`, `dto.agent`, `dto.job`, `dto.project`, `dto.task`, `dto.request`). A resource maps entities to DTOs itself where the entity carries everything the view shows; where the view needs a lookup or a permission check, the owning service's `viewOf` builds it (`JobService.viewOf`, `PendingJobService.viewOf`, `SubAccountService.viewOf`) and those views carry no static factory. Every other service method returns entities.
- **User, worker and project references**: Views referencing external records (`JobView.requestedBy`, `TaskView.createdBy`, `SubAccountView.createdBy`, `JobView.worker`, the rows of `GET /admin/resource-class/{name}/project`) embed `UserInfo` / `WorkerInfo` / `ProjectInfo` (`{id, name}`) instead of a bare UUID — every `/worker` endpoint is `admin:all`, so a project member holding only the ID has nothing to resolve it against. Because a referenced user or worker may be deleted, `UserInfo.name` and `WorkerInfo.name` are null when the record no longer exists; `ProjectInfo` is reached through a foreign key, so its `name` is never null. Paginated listings must resolve them in bulk via collection methods (such as `jobService.viewOf(projectId, jobs)`, `User.mapByIds` or `WorkerEntity.mapByIds`) rather than querying each individually.
- **Request Validation**: Inbound DTO records define Bean Validation constraints with explicit error messages. Resource methods accept them via `@NotNull(message = "a request body is required") @Valid`. The compact constructor normalizes input (e.g. `strip()`) and throws only for rules no annotation expresses: cross-field dependencies (`UpdateSecretRequest`, `UpdateProjectRequest`) and excluded enum values (`SetMemberRoleRequest`). Dynamic limits from `SecretConfig` and permission lookups in `SetPermissionsRequest.resolved()` are checked in code outside the constructor. Constraints are reflected in the OpenAPI schema (`required`, `pattern`, `minLength`, `minimum`).
- **Exception Mapping**: All 4xx and 5xx responses return `{ "message": ... }`, with the exception of 401.
  - `NoSuchElementException`: Mapped to 404 by `NotFoundMapper` with the exception message.
  - `ClientErrorMapper`: Formats 4xx exceptions into `{ "message": ... }`.
  - **403**: `RequirePermissionInterceptor` throws Quarkus's `io.quarkus.security.ForbiddenException` (a `SecurityException`). `ForbiddenMapper` maps this to the standard error JSON body.
  - **401**: Returned with an empty body as the authentication challenge. In the `web-app` OIDC flow, adding a body would override the browser redirect to the OIDC provider.
  - **Constraint Violations**: `ConstraintViolationMapper` formats violations into `{ "message": ... }`, sorting by property path for deterministic output. This mapper takes precedence over Quarkus's default `ResteasyReactiveViolationExceptionMapper`. Violations on method return values are rethrown as 500 internal server errors.
  - **Deserialization Failures**: `ServerJacksonMessageBodyReader` wraps Jackson's `DatabindException` into a generic `WebApplicationException` with status 400. `ClientErrorMapper` unwraps the cause chain to preserve the original exception message and status thrown from constructors. Malformed JSON without an underlying application exception retains the default 400 response.
- **OpenAPI**: `EndpointOASFilter` runs at build time to augment the OpenAPI document with inferred metadata:
  - A **`default` response** with `ErrorView` is added to every operation to document the standard error format without enumerating all possible codes.
  - Common status codes are inferred from method signatures: **401** on all operations, **400** on methods taking request bodies, **404** on methods with path parameters, and **409** on mutating project endpoints under `/project/{projectId}` (due to `requireWritable`, excluding operations defined in `BYPASSES_WRITABLE` such as archive, unarchive, and project deletion).
  - The **success status** is inferred from `@ResponseStatus`, defaulting to 204 for `void` methods (correcting SmallRye's default assumption of 200/201).
  - Error responses include the `ErrorView` schema, except 401 which has an empty body.
  - **Response shape** (`describeShapes`): SmallRye derives `required` from Bean Validation, which only inbound DTOs carry, so a response schema would publish none and a client could not tell an optional field from one the compact constructor `requireNonNull`s. The filter reads it off the declaration instead — no `@Nullable` means `required`, `@Nullable` means the value may be null. A `Map<SomeEnum, ?>` property additionally gets `propertyNames: { enum: [...] }`, so a client mapping `jobs.byState` fails loudly on a key it does not know rather than dropping the count.
  - **Nullability is not optionality.** Jackson writes a null field rather than omitting it, so leaving a property out of `required` alone would be the wrong claim. `allowNull` adds `"null"` to the type list, and for a bare `$ref` — which carries no type of its own, and beside which sibling keywords read as an intersection — rewrites the property as `anyOf: [ {$ref}, {type: "null"} ]`.
  - Schemas a **request body** reaches at any depth get the nullability half but **not** `required`: `CreateJobRequest`, `JobSpecOverride`, `TaskScope` and `JobSpec.VolumeSpec` are shared between a request and a response, and what a caller must *send* is the constraints' to say — `TaskScope.environment` may be omitted on the way in and is always there on the way out. A client wanting the received shape restates those four; splitting them is in [TODO.md](../TODO.md).
  - Schema names are **not** matched to classes by name — SmallRye derives one from the simple class name and disambiguates collisions with a counter (`AdminStatsView.Jobs` and `ProjectDetailView.Jobs` become `Jobs` and `Jobs1`). `ResponseShapes` walks the document and the endpoint's return type together instead, with two allowances: a `oneOf` union's branches are paired with the interface's `getKnownDirectImplementations`, matched by simple name and skipped when ambiguous (without it `PendingJobView`, reachable only through the `JobStatusView` discriminator, resolves to nothing); and a generic envelope's type arguments are carried down and substituted for its type variables (without it every item type reachable only through `Page<T>` goes unresolved).
  - **Discriminator branches** (`describeBranches`): a `oneOf` names a property its branches never
    declared, and a generator believes it — without this pass `JobView` would be generated with a
    required `type` literal its own schema does not carry. Each branch of a discriminated union
    publishes the property with the constant it sends (`enum: ["job"]`) and states it `required`. Runs
    after `describeShapes`, because the property has no field behind it and is appended to the
    `required` list that pass produced.
  - `JsonNode` is republished as an open object (`openJson`). SmallRye reflects the Java type, so the generated schema is Jackson's accessor surface — twenty-odd booleans — rather than the agent protocol frame the field carries, of which an open object is the whole of what can be promised.
  - Custom `@APIResponse` annotations are reserved for special responses (such as `JobResource.createJob`'s `JobStatusView` schema), as SmallRye replaces generated success responses when manual annotations are present.

