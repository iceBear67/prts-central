# TODO

Known gaps, deliberately left open. Each entry says what breaks, why it is tolerable now, and what
closing it would take.

## Orphan `PENDING` job rows

`JobLauncher.prepare` commits the `Job` row before the job is offered to a worker — it has to, since
the `createJob` RPC blocks and must not run inside an open transaction, and a worker may report on the
job the moment it accepts it. Between that commit and the `PendingJobDispatcher.discard` that undoes an
unplaceable job, the row exists in `PENDING` with `worker = null`. Two ways it survives:

- the process dies in that window (the delete never runs), or
- `discard` itself throws — the entry is marked `FAILED` and the row is left behind.

Nothing collects it. Every cleanup path keys on something this row lacks: `WorkerService.failJobsOf`
looks up `Job.listOpenByWorker`, `JobService.applyState` needs a worker to report, and `cancel` needs a
caller who knows the id — which only `markDispatched` ever publishes. `PendingJob.resetDispatching`
repairs the *queue entry* on startup, so the request is retried and a *second* job row is created; the
first stays.

Harmless today, and for the same reason it is uncollectable: it holds no `JobLock` (`WorkerScheduler`
releases in its `finally` before reporting the job unplaceable), and it does not skew placement
(`pendingJobCount` reads the worker's self-reported count).

It is also **hidden rather than collected**: `Job.listVisibleByProject` — behind `GET
/project/{projectId}/job` and the `Job.countByProject` counts on `GET /project/{projectId}` — filters
on `state <> PENDING or worker is not null`, so an orphan is not listed as a job nobody will ever
touch. The row still exists, and `GET .../job/{jobId}` would still return it to anyone holding the id,
which nothing publishes.

Closing it means a startup sweep failing `PENDING` rows with `worker IS NULL` older than some
threshold. The threshold must exceed the 30s `createJob` timeout, or it will fail rows still
legitimately in flight. It belongs in `project`, not `pending` — the row is a `Job`. The `VISIBLE`
predicate goes with it.

## `worker_volume.used` is never written

`WorkerVolume.setUsed` has no call site anywhere. `used` is therefore always `0`, `remaining()` always
equals `length`, and the DB check constraint `worker_volume_usage` is never exercised.

The one reader is `WorkerScheduler.workersForVolumes`, where `row.remaining() < need.sizeLimit()` reads
as a reservation but is only a static capacity check: N concurrent jobs each asking for the whole volume
all pass, and none of them consumes anything. It is not a correctness problem today because nothing
depends on the number being true — placement still succeeds, and the worker is what actually runs out of
disk.

Task-shared volumes make it more visible: several jobs of one task mount the same volume at once, which
is exactly the case the check pretends to guard.

Closing it means the worker reporting real usage — a serverbound message alongside `UpdateResourceInfo`,
or a field on it — and `VolumeService` writing it. A control-plane-side reservation would be the wrong
shape: only the worker knows what a job actually wrote.

## Test gaps

Remaining testing gaps and current constraints:

- **Worker WebSocket protocol messages**: `JobStateUpdate`, `UpdateJobLog`, `UploadArtifactRequest`, and `JobCreated` are not covered end-to-end, as well as full `JobLauncher.launch()` execution. `WorkerWebSocketE2ETest` tests `Register` and `UpdateResourceInfo` with a mock client; remaining messages should be tested against a real worker implementation.
- **Artifact upload and storage**: Upload quotas, presigned URL flow, and S3 object deletion in `ProjectService.delete` against LocalStack (requires worker-side upload requests).
- **`ProjectService` concurrent deletion (`Rows.BUSY`)**: Triggering the race condition between `stopWork` and table locking in `deleteRows` requires precise multi-threaded transaction coordination.
- **Worker WebSocket `@OnError` handling**: Error reply behavior through websockets-next needs further verification.
- **Worker reconnection / re-registration**: Verifying that closing an old connection does not unregister a newly re-registered worker session.
- **Concurrent `JobLock` acquisition on new lock names**: Concurrent first-time acquisition races rely on database unique constraint violation handling in `WorkerScheduler.acquireLock`, which requires multi-threaded concurrent transaction testing.
