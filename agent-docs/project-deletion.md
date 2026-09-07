# Deleting a project

A project owns things that are **not rows** — containers running on workers, objects in S3 — and no
foreign key reaches them, so `ProjectService.delete` works outside-in: `stopWork` cancels the queue and
then per open job does `CANCELLED` + `WorkerService.interrupt` + `discardPendingOf` — in that order,
so the job refuses new uploads before the in-flight ones are dropped — `deleteObjects` deletes the
artifact objects, and only then `deleteRows`. Objects before rows deliberately —
a failure part-way leaves orphaned objects, whose keys are in the log, rather than rows nothing can
reach. Every step but the last is best-effort, and `delete` itself is **not** `@Transactional` — the row
half opens its own `requiringNew()`, because the worker RPC must not run inside one.

Cancelling the queue cannot call back a dispatch attempt already in flight, which may place a job
after `stopWork` read them. So `deleteRows` locks the project `PESSIMISTIC_WRITE` (a job insert takes
`FOR KEY SHARE` on that row, so a persisting attempt is either committed and visible or waits and fails
its FK) and answers `BUSY` while any job is open; `delete` then stops work again, up to
`MAX_STOP_ROUNDS`, and 409s beyond. On the queue side the attempt's finalizers (`markDispatched`,
`requeue`, `markFailed`) write only to an entry still `DISPATCHING`, so one `cancelActive` took cannot
be put back in line.

`ClientboundMessage.InterruptJob` is the third clientbound message and is **not** `CancelJob`: cancel
means "stop it, the row is terminal but still expects your final word", interrupt means "the job no
longer exists here, so your state updates, logs and uploads have nowhere to land". **The worker repo
needs the matching record**; until it does, that `type` fails to deserialize there.

`deleteRows` is then the row half only. Foreign keys carry jobs, logs, artifacts, templates,
volumes, secrets, locks, the queue and the roster; what it does by hand is the two things keyed on the
project with **no FK** — `user_permission` (via `PermissionService.revokeAllInProject`, which also
invalidates each holder's cache) and project-scoped `resource_class` — plus the sub-accounts, whose
`prts_user` rows are project property and would outlive the cascade that takes their link. The
`resource_class` delete runs after an explicit `flush()`: bulk JPQL runs immediately while `delete()`
only queues an `em.remove`, and `job` / `job_spec_template` reference those rows by a two-column FK.
