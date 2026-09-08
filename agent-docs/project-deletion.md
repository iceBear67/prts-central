# Project Deletion Architecture

`ProjectService.delete(projectId)` executes outside-in cleanup to ensure external resources (containers, S3 objects) are eliminated before database records:

```mermaid
flowchart TD
    A["1. stopWork: Cancel queue, cancel open jobs, send InterruptJob to workers"] --> B["2. deleteObjects: Delete S3 artifact objects"]
    B --> C["3. deleteRows: PESSIMISTIC_WRITE lock project, cascade delete DB records"]
```

## Deletion Phases

1. **`stopWork(projectId, reason)`**:
   - Cancels active queue entries (`PendingJobService#cancelActive`).
   - For all open jobs: marks `CANCELLED`, dispatches `ClientboundMessage.InterruptJob` to workers, and purges unassigned pending entries.
   - *Note*: `InterruptJob` informs the worker that the job no longer exists on the server. Workers must drop the container immediately without reporting terminal state updates.
   - **Also used by `ProjectService.archive`**: Cancels pending work and interrupts active jobs before setting the project to read-only. The `reason` parameter informs the worker whether the interruption was caused by deletion or archiving.
2. **`deleteObjects`**:
   - Deletes all S3 objects associated with the project's jobs before database rows are dropped.
3. **`deleteRows`**:
   - Executes inside an independent transaction (`requiringNew()`).
   - Acquires a `PESSIMISTIC_WRITE` lock on the `project` row to block concurrent job insertions.
   - If any open jobs remain (e.g. from an in-flight dispatch), returns `BUSY`. `delete()` retries `stopWork` up to `MAX_STOP_ROUNDS` before failing with a 409 Conflict.

## Database Cascades & Manual Deletions

- **Foreign Key Cascades**: Automatically drop jobs, logs, artifacts, templates, volumes, secrets, locks, queue entries, and project memberships.
- **Manual Removals** (tables lacking project foreign keys):
  - **Permissions**: `PermissionService.revokeAllInProject(projectId)` removes `user_permission` records and invalidates user permission caches.
  - **Sub-Accounts**: Explicitly deletes associated `prts_user` records for sub-accounts owned by the project.
  - **Resource Classes**: Deletes project-scoped `ResourceClass` entities via bulk JPQL following an explicit `flush()` (to prevent constraint violations with `job` FKs).

