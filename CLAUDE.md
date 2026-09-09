# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`prts-central` is the **control plane** of a distributed job runner. It is a Quarkus (JVM 21) service that:

- authenticates humans over OIDC and workers over a shared-secret header,
- stores projects, jobs, job specs, logs and artifact metadata in PostgreSQL,
- keeps a live WebSocket session with each worker, schedules containerized jobs onto them, and
- brokers artifact uploads straight from worker to S3 via presigned URLs.

Workers themselves live in another repository; this one only speaks the protocol in
`io.ib67.prts.agent.worker.message`.

## Documentation index

The detail lives in `agent-docs/`. **Read the file for the area you are touching before changing it** —
each one documents constraints that are not visible in the code it describes.

| Doc | Read it before |
| --- | --- |
| [agent-docs/build-and-run.md](agent-docs/build-and-run.md) | building, running, or touching the schema / local Postgres / S3 |
| [agent-docs/job-lifecycle.md](agent-docs/job-lifecycle.md) | anything about creating, scheduling, cancelling or ending a job, or the pending queue |
| [agent-docs/job-spec.md](agent-docs/job-spec.md) | changing `JobSpec`, secrets, templates, resource classes, or the override gating |
| [agent-docs/task-scope.md](agent-docs/task-scope.md) | tasks, what they inject into a job, worker volumes, or task teardown |
| [agent-docs/worker-protocol.md](agent-docs/worker-protocol.md) | adding a WebSocket message or touching worker sessions / placement |
| [agent-docs/authorization.md](agent-docs/authorization.md) | auth mechanisms, `Perm`, `@RequirePermission`, roles, sub-accounts |
| [agent-docs/http-surface.md](agent-docs/http-surface.md) | adding or changing an endpoint, a DTO, a mapper, or the OpenAPI filter |
| [agent-docs/artifacts.md](agent-docs/artifacts.md) | the upload / quota / S3 path |
| [agent-docs/secrets.md](agent-docs/secrets.md) | project secrets, the seal format, key rotation |
| [agent-docs/project-deletion.md](agent-docs/project-deletion.md) | `ProjectService.delete` or anything it tears down |
| [agent-docs/transactions.md](agent-docs/transactions.md) | adding a transaction boundary around an RPC |
| [agent-docs/testing.md](agent-docs/testing.md) | writing a test, or changing anything that decides which tier one can live in |
| [TODO.md](TODO.md) | known gaps left open on purpose, and what closing each would take |

### Package map

Entities live in an `.entity` sub-package and JAX-RS resources in a `.resource` one; the package root
holds the services and value objects.

| Package | Role |
| --- | --- |
| `agent.worker` | Live worker sessions, the WebSocket protocol (`.message`), scheduling, `VolumeService`; `.entity` = `Worker`, `ResourceClass`, `WorkerVolume`, `VolumeState` |
| `agent.job` | `JobSpec` value object, override/permission gating; `.entity` = `JobSpecTemplate`, `JobLock` |
| `job` | `JobLauncher` (authorize, launch), `JobService` (state, discard, reads, `stopOpen`), `JobResource`, `JobAccess`, `JobConfig`; `.entity` = `Project` / `Job` / `JobLog` / `Artifact` / `JobState` / `ProjectRole` plus the `JobRequest` value |
| `job.task` | `Task` scopes: `TaskScope` value object, `TaskService`, `TaskTeardownDispatcher`; `.entity` = `Task`, `TaskState`, `TaskVolume` |
| `project` | `ProjectService` and `ProjectResource` |
| `pending` | The job queue: `PendingJob` entity, `PendingJobService`, `PendingJobDispatcher` |
| `user` | `User`, project membership, permission grants + cached lookup, sub-accounts |
| `auth` | OIDC identity augmentation, worker token mechanism, `@RequirePermission` interceptor |
| `secret` | Project secrets sealed by `SecretCipher`; `secret.user`, personal access tokens |
| `admin` | The `/api/admin` surface: cross-project listings, permission administration, global templates, dashboard counters |
| `dto` | Outward-facing view records, grouped `dto.admin` / `dto.job` / `dto.project` / `dto.task` / `dto.request`; the ones belonging to no group (`SecretView`, `WorkerView`, `AccessTokenView`, ...) stay at the root |
| `storage` | S3 presigning (`StorageService`) and `ArtifactService`, the upload quota and hand-off |
| `openapi` | Build-time `OASFilter` republishing permissions, the real status codes and the shared error contract into the OpenAPI document |

These groupings move; **do not hand-build a path from this table**, look the class up by name (below).

## Tooling

**The shell `PATH` carries no JDK**, so a bare `./gradlew ...` fails from Bash; set `JAVA_HOME` or
build and lint through the IntelliJ MCP tools. Details in
[agent-docs/build-and-run.md](agent-docs/build-and-run.md).

**Tier C (`@Tag("e2e")`) is not run locally — it is CI's job.** It needs containers the test JVM cannot
reach here; do not look for a way around that. `./gradlew test` (tiers A and B, no containers) is the
local signal; write tier C tests, then let CI execute them. See
[agent-docs/testing.md](agent-docs/testing.md).

The agent also runs **sandboxed and isolated from the host environment**: a path the IDE can see is not
necessarily readable from Bash, and Gradle caches, JDKs and dependency jars generally are not. **Prefer
the IDEA MCP tools over shell search** — they answer from the IDE's index, which reaches places the
sandbox does not:

- `mcp__idea__search_symbol` to find a class/method by name, `mcp__idea__search_text` /
  `mcp__idea__search_regex` for content, `mcp__idea__search_file` for paths.
- `mcp__idea__analyze_calls` for callers/callees — real call-graph data, not a grep for the name.
- `mcp__idea__get_symbol_info` for a symbol's declaration and doc at a position.
- `mcp__idea__read_file` reads **library and JDK sources**, including entries inside jars
  (`/path/lib.jar!/pkg/Foo.class`) and decompiled `.class` files. This is the way to check what a
  Quarkus/Hibernate/Panache API actually does instead of guessing.

**Reach a class by symbol name, not by a path you assembled.** Packages here get reshuffled (the DTOs
and the entities both have been), so a path is the part that goes stale while the name does not —
`mcp__idea__search_symbol` follows the move.

## Conventions

- **Panache active record.** Entities extend `PanacheEntityBase` and carry their own static finders
  (`listByProject`, `findByIdFetched`, `deleteByJob`, `tryAcquire`, ...). Query logic belongs on the
  entity; orchestration belongs in the `*Service`.
- **UUIDv7 primary keys** via `@UuidGenerator(style = VERSION_7)`, generated by the application. The
  one exception is `JobLog`, an identity `bigint` because of its volume.
- **Lombok** through the `io.freefair.lombok` plugin — `@Getter/@Setter/@Builder/@NoArgsConstructor`
  on entities, `@ToString.Exclude` on lazy associations to avoid triggering loads.
- **Records for everything immutable**: DTOs, WebSocket messages, `JobSpec`. `JobSpec` and
  `JobSpecOverride` are stored as `jsonb` (`@JdbcTypeCode(SqlTypes.JSON)`), so they must stay
  Jackson-round-trippable and backward-compatible with rows already in the database.
- **A record's compact constructor `requireNonNull`s every reference component that is not
  `@Nullable`** — Jackson and Panache build records via deserialization, where explicit checks prevent
  unexpected nulls.
- **An inbound request DTO states its validation rules as Bean Validation constraints**, each with an explicit
  `message` (`@NotBlank(message = "name is required")`). The resource accepts it as
  `@NotNull(message = "a request body is required") @Valid XxxRequest`, and `ConstraintViolationMapper`
  formats failures into `{"message": ...}`. Constraints are automatically published to the OpenAPI schema as
  `required`, `pattern`, `minLength`, `minimum`, etc.
  - **Escape braces in message templates.** Bean validation treats `{` and `}` as interpolation syntax;
    literal braces must be escaped (`[A-Za-z0-9_]\\{0,63\\}`).
  - Annotate nested components with `@Valid` (e.g. `CreateTemplateRequest.spec`) to validate their constraints.
  - Compact constructors normalize values (such as `strip()`) in a null-safe manner and avoid throwing validation
    exceptions, as Bean Validation runs after instantiation.
  - Validation rules that cannot be expressed via standard constraints are handled in code:
    - Cross-field rules or single-enum exclusions (e.g. `UpdateSecretRequest` requiring description or
      value, `SetMemberRoleRequest` rejecting `NONE`) throw from the constructor, and `ClientErrorMapper`
      extracts the message from the deserialization cause chain.
    - Configuration-dependent limits (e.g. `SecretConfig.maxValueLength`) that cannot be compile-time `@Size` constants.
    - Semantic lookups and validations (e.g. `SetPermissionsRequest.resolved()`).
  - DTOs reused in responses (such as `CreateJobRequest` within `JobView`) do not trigger validation on serialization,
    as Bean Validation only executes when a validator explicitly checks an incoming request payload.
- **Config via `@ConfigMapping` interfaces** (`StorageConfig`, `JobConfig`, `WorkerConfig`,
  `SecretConfig`, `AdminConfig`, `PermissionConfig`), not `@ConfigProperty`. These are **immutable
  snapshots** built and cached by SmallRye at startup; they cannot be changed at runtime. Values that must
  be dynamic at runtime require persistent storage rather than configuration mappings.
- **No `@Nullable` where an empty value says the same thing**, containers above all: a `Map`/`List`
  field is non-null and empty, and `JobSpec.lock` is `""` rather than null. Normalize at the
  constructor so no reader has to tell absent from empty. `@Nullable` is for a genuine third state —
  `JobSpecOverride`'s fields, where absent must be told from supplied-but-empty because only a
  supplied field is gated.
- **Authorization lives at the endpoint**, not in the services. Services take plain arguments or domain
  values (`JobRequest`), never wire DTOs; request-shape validation stays in the resource.
- **A resource shapes; it never queries.** `EntityManager` does not appear in a resource — anything that
  needs one goes on the entity as a Panache finder or into the service owning the concept, which is what
  those layers are for. Put it where that concept already lives: `ArtifactService.stored()` beside its
  `reservedFor()`, `UserToProject.countByProjects` beside the `Job.countVisibleByProjects` its caller
  already uses. What stays at the endpoint is grouping, merging and mapping into view records.
- **Don't invent a type to carry a shape.** A service method, or a record, that exists only to reshape data
  its caller can already reach is the resource's work —
  `ScopedGrants.of(permissionService.grantsOf(id))`, not `PermissionService.grantsByScope`. Two callers
  wanting the same shape is no reason to push it down; share it on the view record, and reuse one that
  already fits before writing a new one (`ArtifactUsage` was `ArtifactService.Reservations`, one rename
  away).
- **Every mutating project endpoint must call `ProjectService.requireWritable(projectId)` first.**
  Archived projects return 409 Conflict for all modifications other than unarchive and delete. This check
  is explicit rather than interceptor-based, so **new mutating endpoints must include it**. Worker reporting
  paths (`JobService.applyState`, `appendLog`, `ArtifactService.record`) intentionally bypass this check.
  See [agent-docs/http-surface.md](agent-docs/http-surface.md) for existing call sites.
- **Transactions do not span an RPC.** Commit in `QuarkusTransaction.requiringNew()`, make the call
  outside any transaction, compensate in another one.
- `-parameters` is on for `compileJava`; REST/JSON parameter names depend on it.
- Comments in this codebase explain *why* a non-obvious choice was made (ordinal enums, attempt-keyed
  ack maps, `columnDefinition`). Match that: skip narration, document the constraint. The same goes for
  these docs — when a change invalidates a rule written down in `agent-docs/`, update that file in the
  same commit.
