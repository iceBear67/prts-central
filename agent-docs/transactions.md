# Transaction Management & Boundaries

PRTS-Central avoids long-lived `@Transactional` boundaries because worker scheduling and S3 transfers involve blocking network I/O.

## Standard Multi-Step Pattern

1. **Prepare & Persist**: Use `QuarkusTransaction.requiringNew()` to commit state before external I/O (`JobLauncher.prepare`, `JobService.prepareCancel`).
2. **External I/O**: Execute blocking RPCs or network calls outside of any active transaction (`WorkerClient.createJob`, S3 operations).
3. **Compensation**: On failure or timeout, open a new `requiringNew()` transaction to transition state (`applyState(FAILED)`, release `JobLock`, requeue).

### Entity Fetching & Locking Rules
- **Closed Contexts**: Because entities outlive their persistence context, eager associations must be loaded via explicit `join fetch` queries (`...Fetched`, e.g., `Job.findByIdFetched`).
- **Pessimistic Locking**: Use `LockModeType.PESSIMISTIC_WRITE` when checking and transitioning mutable entities (job state updates, lock acquisition, pending queue claims).
- **Self-Invocation**: Because `@Transactional` interceptors do not intercept same-class method calls, services invoke `QuarkusTransaction.requiringNew()` explicitly.

## Transaction Boundaries

| Operation | Boundary | Purpose |
| --- | --- | --- |
| `JobLauncher.authorize` | `requiringNew` | Read-only validation; nothing persisted. |
| `JobLauncher.prepare` | `requiringNew` | Persists `PENDING` job before scheduling begins. |
| `WorkerScheduler` operations | `requiringNew` (each) | Short transactions for `tryAcquire`, `claimJob`, and lock release. |
| `WorkerClient` calls | None | Blocking network I/O. |
| `JobService.discard` | `requiringNew` | Cleanup compensation for unplaceable jobs. |
| `JobService.cancel` | `requiringNew` (`prepareCancel`), RPC, then `requiringNew` (logging) | Commits `CANCELLED` state before sending non-blocking worker notification. |
| `PendingJobService.enqueue` | `requiringNew` | Persists queue entry. |
| `PendingJobService.claimDue` / `mark*` | `@Transactional` (each) | Discrete state updates between dispatch attempts. |
| `ArtifactService.begin` | `requiringNew` (`reserve`) | Commits quota reservation before presigning S3 URL. |
| `ProjectService.delete` | None (`deleteRows` uses `requiringNew`) | External stopWork and S3 deletions precede database row deletion. |

