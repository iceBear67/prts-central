# Transaction style

Services do **not** rely on a single `@Transactional` boundary for the multi-step flows, because
scheduling makes a blocking RPC that must not run inside an open transaction. The recurring shape is:

1. `QuarkusTransaction.requiringNew().call(...)` to prepare and persist (`JobLauncher.prepare`,
   `JobService.prepareCancel`),
2. do the RPC / scheduling *outside* any transaction,
3. compensate in another `requiringNew()` on failure (mark `FAILED`, release the lock).

Because entities are read in a closed transaction and used afterwards, associations are pulled in
explicitly by `join fetch` finders — hence the `...Fetched` naming (`Job.findByIdFetched`,
`JobSpecTemplate.listAllFetched`). Use `LockModeType.PESSIMISTIC_WRITE` when reading a job you are
about to claim or cancel.

`@Transactional` does not apply to a bean calling its own method, which is why the launcher and the
services open `QuarkusTransaction` blocks explicitly instead of annotating private helpers.

## Boundaries, per step

| Step | Boundary | Why |
| --- | --- | --- |
| `JobLauncher.authorize` → `resolve` | `requiringNew` | reads only; nothing persisted |
| `JobLauncher.prepare` | `requiringNew` | the row must be committed before a worker is asked |
| `WorkerScheduler` `isSchedulable` / `acquireLock` / `claimJob` / `releaseLock` | `requiringNew` each | the 30s ack wait sits between them |
| `WorkerClient.createJob` / `cancelJob` | none | blocking I/O |
| `JobLauncher.dispatch` → `JobService.applyState` | `@Transactional` | compensation, on a throw only |
| `JobService.discard` | `requiringNew` | compensation |
| `JobService.cancel` | `prepareCancel` in `requiringNew`, RPC, `logCancelOutcome` in `requiringNew` | state first, so a late worker report is ignored |
| `PendingJobService.enqueue` | `requiringNew` | the caller has already authorized; this only persists |
| `PendingJobService.claimDue` / `mark*` / `requeue` | `@Transactional` each | the replay in between makes RPCs |
| `ArtifactUploadService.begin` → `reserve` | `requiringNew` | presign happens after the reservation is committed |

`ProjectService.delete` is the other shape — see [project-deletion.md](project-deletion.md): it is
deliberately **not** `@Transactional` at all, and only its row half opens one.
