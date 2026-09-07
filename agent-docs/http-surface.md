# HTTP surface

## Paths and gating

`quarkus.rest.path` is `/api` and the policies in `application.yml` match it (`/api/*` → authenticated
OIDC; `/ws/worker` → `worker-token`). Measured: `/api/project` → 302, `/project` → 404. Note
`@ApplicationPath` only counts on a `jakarta.ws.rs.core.Application` subclass — adding one with `"/"`
would move every endpoint off `/api` and silently un-gate it.

Job endpoints hang off their project — `JobResource` is `/project/{projectId}/job`, and
`ProjectResource` spells its variable `{projectId}` too for the same reason — so every check has a
project to scope to. A job is still looked up by its own id and then matched against that project
(`JobService.findInProject` / `requireInProject`, `Artifact.findInProject`); reaching a job through
the wrong project is a 404, not a different job. Log paging is `?offset=&length=` with no total, to keep
it to one query.

Reads take `ProjectRole.VIEWER` (`project:read`, `job:read`, `job:log:read`, `job:artifact:read`),
acting on a job takes `MEMBER` (`job:create`, `job:cancel`, and `project:secret:read` — whoever writes
a job has to know which secret names exist), and changing the project or its roster takes `OWNER`
(`project:update`, `project:member:manage`, `project:delete`, `project:subaccount:manage`,
`project:secret:manage`). The line between `job:read` and `job:artifact:read` is metadata vs bytes:
`JobView` lists artifact names, while handing out a presigned URL is the download itself. Deleting is
its own `Perm` rather than `project:update`: it is strictly more than a rename and there is no undoing it.
`job:template:read` is the odd one: the template endpoints are gated `project:read`, and the perm gates
the **content** — `JobSpecTemplateView.of(template, withSpec)` leaves `spec` and `resourceClass` null
without an explicit grant (`JobAccess.mayReadTemplate`, `defaultRole = NONE`: no role stands in, only
`admin:all`), so every viewer sees which templates exist but not what they run.

## Workers

`WorkerResource` is a human's, gated `admin:all` at class level (workers belong to no project, so no
role can stand in). It lists the persistent `Worker` rows, connected or not, merged with the live
session's `Info`; `POST /{id}/disable|enable` flips `Worker.disabled`, which `WorkerService.setDisabled`
mirrors onto the live `RegisteredWorker` under the same monitor `registerWorker` reads it under, so a
flag written mid-register cannot land on the displaced session. `WorkerScheduler.select` skips a
disabled worker and `PendingJobDispatcher.tick` idles when none is schedulable; what a disabled worker
is already running is left alone.

## Projects, members, tokens, secrets

`GET /project/{projectId}` is a `ProjectDetailView`: the roster, job counts, the caller's `role`
(`NONE` off the roster) and `access` — `MEMBER`, `ADMIN` for an `admin:all` holder past it, or
`PERMISSION` for a `project:read` grant. There is no separate member list, and no leave endpoint:
`DELETE .../member/{userId}` is `defaultValue = true` and the body draws the line — removing yourself
takes nothing, removing anyone else takes `project:member:manage` via `PermissionService.allows`.

`GET /project` carries no `@RequirePermission`, deliberately: it lists only the caller's own
memberships. `UserService.grant`/`revoke` refuse to demote or remove a project's **last `OWNER`** —
nobody would be left who could grant the role back, so a last owner cannot leave either.

`/user/token` carries no `@RequirePermission` — it only ever touches the caller's own row, and reads
`UserContext.require()` to refuse an identity with no local user. It also refuses a **sub-account**
(403), even one calling with its own token: that token is the only credential it has and the owner
minted it, so a reroll by the holder would take it away from the owner. `SubAccountResource` is the
owner's copy of it (`PUT .../subaccount/{userId}/token`) and the only place a sub-account's token is
managed; every `/{userId}` route there goes through `SubAccountService.require`, the containment check
that stops an owner of project A from managing — or minting a token for — anything that is not project
A's. A token, its own or a sub-account's, is returned **once**, by the `PUT` that mints it.

`PATCH .../secret/{name}` takes `description` and/or `value`, each nullable for "unchanged" and
refused when both are; a blank description clears it, a value is re-sealed by `SecretService.update`.
The rest of that resource, and what sealing means, is in [secrets.md](secrets.md).

## Jobs

**`POST .../job` queues; it does not create.** It authorizes the request and enqueues it, and answers
**201 with a `PendingJobView`** — no `Job` exists yet, and there is deliberately no endpoint that
bypasses the queue. The job appears on the entry as `jobId` once `PendingJobDispatcher` places it,
within `job.pending.interval`. The queue has no resource of its own: `GET .../job` lists jobs and
unplaced entries together as `List<JobStatusView>`, newest first, paged `?offset=&length=` (bounded by
`job.list.max-page-size`) by fetching `offset + length` from each side and merging — no total, for the
same reason logs have none. It goes through `Job.listVisibleByProject`, which hides the orphan
`PENDING`-with-no-worker rows `TODO.md` describes rather than showing a job nobody will ever touch.
An entry's `CreateJobRequest` is gated exactly like `JobView.createRequest` and null without
`job:create`: an entry is a create that has not happened yet, so seeing the request takes what making
one takes, and the override it carries was gated field by field on the way in.

Both views are `JobStatusView`, a sealed interface carrying a `type` of `"job"` or `"pending"`. Every
endpoint returning either **declares that interface**, never the concrete record: Jackson writes the
discriminator off the static type, and the scanner reads it off the `@Schema(oneOf = ...)` there —
declaring the record drops both. That is also why `createJob` sets its 201 with `@ResponseStatus`
instead of returning a `Response`, whose entity is an untyped `Object`.

There is **no server-side re-run**. The job stores only what cannot be recovered from itself
(`template_id`, `create_override`); `Job.toRequest()` rebuilds the `JobRequest` and
`JobView.createRequest` hands it back for the client to `POST .../job` again (so a re-run is queued too)
— gated exactly like the create it replays: template fields stay free, the stored override is
re-authorized against the *re-running* caller, and the template is read at its current version. The
resource class it names is the **resolved** one, not what the original requester typed, so the replay
pins the class that actually ran even if the template has moved on. There is deliberately no
`create_resource_class`: a request field worth replaying either survives in the job or is not worth
storing twice.

`GET .../job/{jobId}` takes **either id the client may be holding** — a job's, or that of the queue
entry it came from, which is followed to the job once `jobId` is set. So the id handed out at create
time keeps working for the life of the run and the client never swaps ids or endpoints; `type` says
which of the two it found. It cannot flip back: a discarded attempt never writes `jobId`.
`POST .../job/{jobId}/cancel` takes either id the same way — a job's, or an entry's, followed to its
job once placed and otherwise cancelled as a `QUEUED` entry — and answers with whichever it cancelled.
(The decision tree is in [job-lifecycle.md](job-lifecycle.md).)

Detail there **scales with permission** rather than splitting into a second endpoint: the stored
create request is filled only for a caller who could post it. `JobAccess` is the one answer to
that question — it wraps `PermissionService.allows`, the non-throwing twin of the `job:create` /
`MEMBER` check `@RequirePermission` would enforce (and, for templates, of `job:template:read`), so a
resource never keeps a copy of a rule. It is null for a viewer, and for a job with no `templateId`. Keep
it in step by hand when the interceptor's rules change; the `JobView` the cancel returns leaves it null
because no such check was made there.

## DTOs, mappers, OpenAPI

DTOs are the boundary, and the **resources own them**: services return entities (`Job`, `List<JobLog>`,
`Artifact`) and the resource maps them with `JobView.of(...)`. Because create/rerun/cancel hand back an
entity whose transaction has already closed, a mapper may only read what that entity carries — see the
note on `JobView.of`. `JobView.SpecView` deliberately projects `JobSpec` **without `secrets()`**.
Keep secrets out of any new outward-facing view.

`ClientErrorMapper` gives a 4xx the message `WebApplicationException(String, Status)` leaves out; 5xx
passes through untouched.

`NotFoundMapper` turns the `NoSuchElementException` that `*Service.require` methods throw into a 404,
which is why endpoints call `projectService.require(projectId)` straight away instead of looking the
row up once for a 404 and again for the work. Note that a non-member asking about a project that does
not exist gets **403 from the interceptor**, not 404 — the check runs first and cannot distinguish the
two; only `admin:all` callers reach the body and see the 404.

`PermissionOASFilter` republishes the authorization model into the OpenAPI document: per operation it
appends the `@RequirePermission` rule to the description, adds an `x-required-permission` extension
and 401/403 responses; per property it notes what an override costs, reading a bean named
`XPermissions` as gating the fields of `X` (so renaming `JobSpecOverridePermissions` silently drops
that). It runs at `RunStage.BUILD` because only the Jandex index knows which method serves which
path, and matches operations by verb plus **path suffix**, since document paths carry the `/api`
prefix and the annotations do not. `store-schema-directory: build/openapi` makes every build write
the document out, which is how to check it.

## Known inconsistency

Services still throw `jakarta.ws.rs` exceptions directly as well (`JobLauncher`'s `NotFoundException` /
`BadRequestException`, `JobService`'s and `UserService`'s `ClientErrorException(CONFLICT)`), so the web
layer does leak inward. Finishing the job means domain exceptions plus more mappers, and touching how
`ArtifactUploadService` and `WorkerWebSocket` branch on `NoSuchElementException | IllegalStateException`.
