# Task Scope

A `Task` groups a project's jobs under one topic and gives them a shared substrate. It **does not
orchestrate**: it declares no steps, resolves no dependencies, and never creates a job itself.

## What a task owns, and what it does not

| | Owner | Lifetime |
| --- | --- | --- |
| `TaskScope` (environment, labels, default resource class) | the task | dies with it |
| Volume **mounts** (`task_volume`, carrying `mountPoint`) | the task | dropped on close |
| The **volumes** themselves (`worker_volume`) | the **project** | outlive every task |
| Jobs (`job.task_id`) | the project | outlive the task, as its record |

`task_volume` is many-to-many: one volume may be mounted by several tasks, each at a path of its own —
which is why `mountPoint` sits on the mount row rather than on the volume.

`Task` deliberately owns nothing that needs its own resolution or its own permission scope. There are no
task-scoped secrets, templates or resource classes, and no task-scoped grants: `Permission` is keyed
`(userId, permission, projectId)` and `RequirePermissionInterceptor` resolves exactly one scope, a
project. Keeping tasks out of that is what makes them cheap.

## Injection into a job's spec

`JobLauncher.resolve` merges four layers, in order:

1. `template.getSpec()`
2. `TaskScope.defaultsTo` — the task's `environment` and `labels`, which a job **may** specialize
3. `JobSpecOverride.applyTo` — the caller's override, gated field by field as before
4. `TaskScope.bindTo` — the task's volumes and its identity (`PRTS_TASK_ID`, the `prts.task` label),
   which a job **may not** override

Layer 4 has to win: a caller that could write `PRTS_TASK_ID` itself could claim membership in someone
else's task.

**Task values never pass through `JobSpecOverride`.** That path calls the authorizer for every supplied
field — including empty collections, which `JobSpecOverrideTest.anEmptyOverrideIsStillSuppliedAndStillGated`
pins down — so routing them through it would charge the requester `job:spec:environment` and friends for
values the task was already permitted to set. `defaultsTo` and `bindTo` take no authorizer.

The same reasoning covers the resource class: `TaskScope.resourceClass` stands in for the template's
default, and keeping it costs no `job:resource-class`. Naming a *third* class is still an override and
is still gated.

`resolve` runs **twice** — once at enqueue (its merged spec is discarded; only the pinned resource class
survives, on `JobRequest`) and again on every dispatch attempt. The task is re-read each time, so an
edit between the two takes effect, exactly as a template edit does.

Task-injected values land in the persisted `job.spec` jsonb. They are not secrets, and `Job.toRequest()`
replays only `createOverride`, so a re-run re-applies the task layer once rather than twice.

## Worker affinity

`WorkerScheduler.workersForVolumes` places a job only on a worker holding **all** of its volumes, so a
task that mounts anything is effectively pinned to one worker. `TaskService.attach` enforces this at
mount time (409, naming the worker already in use) rather than storing a pin on the task.

A job may still add its own volumes through an override. `JobSpec.requireVolumesIn` rejects a spec whose
volumes span two workers, naming both — placement cannot report this itself, since every reason it fails
collapses into `"no available worker can run this job"` and the entry would just back off until it
expired.

## Lifecycle

`OPEN` → `CLOSING` → `CLOSED`. `DELETE .../task/{taskId}` marks the task `CLOSING` and runs one teardown
pass; `TaskTeardownDispatcher` finishes any that could not complete.

`TaskService.teardown` does three things and stops:

1. `PendingJob.cancelActiveInTask`
2. `JobService.stopOpen(Job.listOpenByTask(...))` — cancel, interrupt on the worker, discard pending uploads
3. `TaskVolume.deleteByTask` — **unmount only**

A job dispatched while work was being stopped leaves the task `CLOSING` for the next sweep. There is no
retry ceiling: unlike `ProjectService.delete`, no HTTP response is waiting on it.

**Closing a task never sends `DeleteVolume`.** Volumes leave a worker only through
`DELETE /project/{projectId}/volume/{volumeId}`, which conflicts while any task still mounts them.

The row is kept after `CLOSED` — the jobs it scoped are still readable, and `job.task_id` carries no
foreign key precisely so they outlive it.

## `JobService.stopOpen`

The "quiesce" half of tearing down a scope, shared by project deletion, project archiving and task
closure — each differs only in which rows it hands over (`Job.listOpenByProject` vs `listOpenByTask`).
The other half, deleting rows, is **not** shared: a task is closed, not deleted.
