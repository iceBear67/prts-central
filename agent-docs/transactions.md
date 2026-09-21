# Transaction Management & Boundaries

PRTS-Central avoids long-lived `@Transactional` boundaries because workerEntity scheduling and S3 transfers involve blocking network I/O.

## Standard Multi-Step Pattern

1. **Prepare & Persist**: Use `QuarkusTransaction.requiringNew()` to commit state before external I/O (`JobLauncher.prepare`, `JobService.prepareCancel`).
2. **External I/O**: Execute blocking RPCs or network calls outside of any active transaction (`WorkerService.createJob`, S3 operations).
3. **Compensation**: On failure or timeout, open a new `requiringNew()` transaction to transition state (`applyState(FAILED)`, release `JobLock`, requeue).

### Entity Fetching & Locking Rules
- **Closed Contexts**: Because entities outlive their persistence context, eager associations must be loaded via explicit `join fetch` queries (`...Fetched`, e.g., `Job.findByIdFetched`).
- **Pessimistic Locking**: Use `LockModeType.PESSIMISTIC_WRITE` when checking and transitioning mutable entities (job state updates, lock acquisition, pending queue claims).
- **Self-Invocation**: Because `@Transactional` interceptors do not intercept same-class method calls, services invoke `QuarkusTransaction.requiringNew()` explicitly.
- **Joined Transaction Rollback**: When a `@Transactional` method joins an active transaction and throws, the transaction is marked rollback-only regardless of whether the caller catches the exception. Optional operations should return status (e.g. `Optional`) instead of throwing; for example, `NotificationService.notifyIfPresent` avoids aborting job transitions if the requester no longer exists.

## Transaction Boundaries

| Operation | Boundary | Purpose |
| --- | --- | --- |
| `JobLauncher.authorize` | `requiringNew` | Read-only validation; nothing persisted. |
| `JobLauncher.prepare` | `requiringNew` | Persists `PENDING` job before scheduling begins. |
| `WorkerScheduler` operations | `requiringNew` (each) | Short transactions for `tryAcquire`, `claimJob`, and lock release. |
| `WorkerService` calls to a worker | None | Blocking network I/O. |
| `JobService.discard` | `requiringNew` | Cleanup compensation for unplaceable jobs. |
| `JobService.cancel` | `requiringNew` (`prepareCancel`), RPC, then `requiringNew` (logging) | Commits `CANCELLED` state before waiting for the workerEntity to acknowledge the cancellation. |
| `JobService.applyState` | `@Transactional` | Transition, log and the requester's failure message commit together. |
| `PendingJobService.enqueue` | `requiringNew` | Persists queue entry. |
| `PendingJobService.claimDue` / `mark*` | `@Transactional` (each) | Discrete state updates between dispatch attempts. |
| `ArtifactService.begin` | `requiringNew` (`reserve`) | Commits quota reservation before presigning S3 URL. |
| `ProjectService.delete` | None (`deleteRows` uses `requiringNew`) | External stopWork and S3 deletions precede database row deletion. |

