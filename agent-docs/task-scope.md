# Task Scope

A `Task` groups a project's jobs under a common context and provides a shared execution configuration.
It does not orchestrate workflow steps, resolve dependencies, or spawn jobs automatically.

## Ownership and Lifecycles

| Component | Owner | Lifecycle |
| --- | --- | --- |
| `TaskScope` (environment, labels, default resource class) | Task | Scoped to task; inactive once closed |
| Volume mounts (`task_volume`, with `mountPoint`) | Task | Unmounted on task closure |
| Worker volumes (`worker_volume`) | Project | Persistent; independent of individual tasks |
| Jobs (`job.task_id`) | Project | Persistent; retained after task closure |

`task_volume` is a many-to-many relationship: a single volume can be mounted by multiple tasks at
independent mount paths. Therefore, `mountPoint` is stored on the mount mapping rather than on the volume.

Metadata fields (`name`, `description`, and `trackedAt` representing an issue or PR link) are purely
informational. Only `TaskScope` attributes are injected into executed jobs.

Tasks do not declare separate authorization scopes, secrets, templates, or resource classes. All permissions
remain bound to `(userId, permission, projectId)`.

## Spec Merging Hierarchy

`JobLauncher.resolve` merges configurations across four layers in order of precedence:

1. `template.getSpec()`: Base template specification.
2. `TaskScope.defaultsTo`: Task-level environment variables and labels (can be overridden by job).
3. `JobSpecOverride.applyTo`: User-supplied overrides, gated by individual permission checks.
4. `TaskScope.bindTo`: Enforced task volumes and system identity (`PRTS_TASK_ID`, `prts.task` label),
   which cannot be overridden.

Layer 4 enforces task identity and volume mounts, preventing job overrides from spoofing task membership.

Task attributes are applied directly rather than routed through `JobSpecOverride`, avoiding redundant
permission checks for values already validated at task definition time.

Similarly, `TaskScope.resourceClass` supplies a task-level default replacing the template's default without
requiring additional permissions. Specifying a different resource class in a job override remains subject
to `job:resource-class` permission checks.

## Scope Permissions

Task mutation endpoints validate properties using `JobSpecOverridePermissions`. Mutating a task's
environment, labels, or default resource class requires `job:spec:environment`, `job:spec:labels`, and
`job:resource-class` respectively.

Resolution occurs twice: initially at job enqueue (pinning the selected resource class onto `JobRequest`),
and again upon each dispatch attempt. As with templates, runtime task modifications apply to subsequent dispatches.

Resolved task attributes are persisted in `job.spec`. Re-running a job (`Job.toRequest()`) replays only
`createOverride`, reapplying current task settings cleanly.

## Worker Affinity

`WorkerScheduler.workersForVolumes` requires all volumes attached to a job to reside on the same workerEntity.
Consequently, mounting volumes pins a task to the workerEntity hosting those volumes. `TaskService.attach`
enforces this constraint at mount time (returning 409 Conflict if volumes reside on different workers).

If an individual job override mounts additional volumes that conflict across workers, `JobSpec.requireVolumesIn`
rejects the spec immediately to prevent unschedulable jobs from lingering in the queue.

## Lifecycle

State transitions follow `OPEN` → `CLOSING` → `CLOSED`.

Calling `DELETE /api/project/{projectId}/task/{taskId}` transitions the task to `CLOSING` and executes an initial
teardown pass. `TaskTeardownDispatcher` processes any remaining teardown asynchronously.

`TaskService.teardown` performs the following steps:

1. `PendingJob.cancelActiveInTask`: Cancels unstarted pending jobs in the task.
2. `JobService.stopOpen(Job.listOpenByTask(...))`: Cancels open jobs, sends workerEntity interrupts, and cleans up pending uploads.
3. `TaskVolume.deleteByTask`: Unmounts attached volumes.

Closing a task does not delete physical volumes. Volumes are managed and deleted exclusively through
`DELETE /api/project/{projectId}/volume/{volumeId}`, which fails if the volume is still mounted by any task.

Task records remain after transitioning to `CLOSED`. The `job.task_id` column does not enforce a foreign
key cascade, allowing job execution history to persist indefinitely.

## `JobService.stopOpen`

`JobService.stopOpen` handles stopping active executions across project deletion, project archiving,
and task closure. While project deletion subsequently removes database rows, task closure preserves
historical records.
