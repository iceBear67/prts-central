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
