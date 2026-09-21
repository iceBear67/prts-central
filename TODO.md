# TODO

Known gaps, deliberately left open. Each entry says what breaks, why it is tolerable now, and what
closing it would take.

## Orphan `PENDING` job rows

`JobLauncher.prepare` commits the `Job` row before the job is offered to a workerEntity — it has to, since
the `createJob` RPC blocks and must not run inside an open transaction, and a workerEntity may report on the
job the moment it accepts it. Between that commit and the `PendingJobDispatcher.discard` that undoes an
unplaceable job, the row exists in `PENDING` with `workerEntity = null`. Two ways it survives:

- the process dies in that window (the delete never runs), or
- `discard` itself throws — the entry is marked `FAILED` and the row is left behind.

Nothing collects it. Every cleanup path keys on something this row lacks: `WorkerService.failJobsOf`
looks up `Job.listOpenByWorker`, `JobService.applyState` needs a workerEntity to report, and `cancel` needs a
caller who knows the id — which only `markDispatched` ever publishes. `PendingJob.resetDispatching`
repairs the *queue entry* on startup, so the request is retried and a *second* job row is created; the
first stays.

Harmless today, and for the same reason it is uncollectable: it holds no `JobLock` (`WorkerScheduler`
releases in its `finally` before reporting the job unplaceable), and it does not skew placement
(`pendingJobCount` reads the workerEntity's self-reported count).

It is also **hidden rather than collected**: `Job.listVisible` — behind `GET
/project/{projectId}/job` and the `Job.countByProject` counts on `GET /project/{projectId}` — filters
on `state <> PENDING or workerEntity is not null`, so an orphan is not listed as a job nobody will ever
touch. The row still exists, and `GET .../job/{jobId}` would still return it to anyone holding the id,
which nothing publishes.

Closing it means a startup sweep failing `PENDING` rows with `workerEntity IS NULL` older than some
threshold. The threshold must exceed the 30s `createJob` timeout, or it will fail rows still
legitimately in flight. It belongs in `project`, not `pending` — the row is a `Job`. The
`VISIBLE_ROW` / `VISIBLE_ALIASED` predicate goes with it.

## `worker_volume.used` is never written

`WorkerVolume.setUsed` has no call site. `used` is always `0`, `remaining()` always
equals `length`, and the DB check constraint `worker_volume_usage` is never exercised.

`WorkerScheduler.workersForVolumes` checks `row.remaining() < need.sizeLimit()`, which acts as a
static capacity check rather than dynamic reservation: concurrent jobs mounting the volume can pass
placement simultaneously without tracking consumption.

Fixing this requires workers to report real usage (via `UpdateResourceInfo` or a dedicated message),
which `VolumeService` would persist. Control-plane-side reservation is insufficient since actual disk
usage is workerEntity-determined.

## ACP: `$/cancel_request` not supported

`$/cancel_request` carries `params.requestId`, requiring the proxy to inspect and rewrite IDs inside the payload rather than just the envelope. `session/cancel` currently serves user cancellation needs. Supporting this requires parameter-level ID mapping in `AgentChannel`.

## ACP: per-frame serverbound acknowledgments

`WorkerWebSocket` sends a `Response` for every `ServerboundMessage.AgentFrame`, adding per-chunk ack overhead during streaming turns. Omitting acks would require returning `Uni<Void>` for ACP frames, diverging from the current uniform acknowledgment contract.

## ACP: uncoalesced transcript storage

`agent_event` stores each frame verbatim, producing one row per streamed token chunk. Coalescing consecutive chunks (`agent_message_chunk`, `agent_thought_chunk`) by `messageId` in `AgentTranscript` would reduce row volume at the expense of buffering.

## A schema shared by a request and a response publishes no `required`

`describeShapes` states `required` from the declaration, but withholds it from any schema a request
body reaches at any depth. Four are shared: `CreateJobRequest`, `JobSpecOverride`, `TaskScope` and
`JobSpec.VolumeSpec`. As a response they publish every key optional, which is accurate for *sending*
and wrong for *receiving* — `TaskScope.environment` may be omitted on the way in (the constructor
normalizes null to empty) and is always present on the way out. A client that wants the received
shape has to restate those four types by hand.

Closing it means splitting the response shape from the request shape **in the document**, not in
Java: `JobView.createRequest` is a `CreateJobRequest` on purpose, because a re-run posts it straight
back. The filter would clone each shared schema, state `required` on the copy, and rewrite the refs
reaching it from the response side — a fixed point, since a clone is itself response-side. The
reference sites cooperate (`TaskView.scope`, `SpecView.volumes`, `JobView.createRequest` and
`PendingJobView.request` are all response-only), but the copy has to be deep along any rewritten path:
MP OpenAPI's `getAll`/`setAll` copy is shallow, so mutating a child in place would mutate the
original's too. Four extra component schemas for the client, and names for them.

## A job can finish before its placement is recorded

`WorkerScheduler.schedule0` sends `createJob`, waits for the workerEntity's acknowledgment, and only then
calls `claimJob`, which stamps `Job.workerEntity` and `startedAt` in a transaction of its own. Nothing orders
that claim against the messages the workerEntity sends next, so a workerEntity that reports a terminal state inside
the window wins the race: `claimJob` finds a completed job, returns `false`, quietly cancels on the
workerEntity, and the job is left finished with `workerEntity = null` — nobody is recorded as having run it. The
guard itself is deliberate (`isSchedulable` and this branch handle the job cancelled while
dispatching); the problem is that a *fast* workerEntity is indistinguishable from a cancelled one.

Tolerable today because a real workerEntity takes seconds to start a container, and the mock workerEntity that
surfaced it is the only participant quick enough to answer and finish within milliseconds.
`MockWorkerEntityE2ETest` asserts placement on a job it holds open rather than on one that has already
finished, for this reason.

Closing it means claiming before the acknowledgment is observable: `WorkerClient.complete` would claim
ahead of completing the future, so what unblocks the launcher happens only once the placement is
committed. That moves the claim — and the "cancelled while dispatching" compensation — onto the
WebSocket thread, which is the part to think through.

## An upload that lands in the last sweep of a job's life is dropped

`ArtifactService.tryPromote` refuses an upload whose job has already ended (`lockAssignedOpen` throws
once the job is terminal) and `discard`s the session, which deletes the object as well. The sweeper
only looks every two seconds, so a workerEntity that PUTs its artifact and reports `SUCCESS` immediately
loses it whenever a tick falls between the two — silently, apart from one `LOG.info`.

Tolerable because that window is milliseconds wide against a two-second tick, and because a test that
holds the job open until the artifact appears never hits it — which is what `MockWorkerEntityE2ETest` does,
and what [workerEntity-mock/README.md](workerEntity-mock/README.md) warns a script author about.

Closing it means deciding what a completed job's late upload becomes: record the artifact from the
session rather than through the job row (weakening the open-and-assigned check to "assigned to this
workerEntity"), or keep the object until the presign expires and let the expiry sweep rule on it.

## Test gaps

Remaining testing gaps and current constraints:

- **Worker WebSocket protocol messages**: `Register` and `UpdateResourceInfo` are exercised by
  `WorkerEntityWebSocketE2ETest`, and `ack`, `jobStateUpdate`, `updateJobLog` and `uploadArtifactRequest`
  — plus full `JobLauncher.launch()` execution — by `MockWorkerEntityE2ETest` over
  [`workerEntity-mock`](workerEntity-mock/README.md). What is left is the agent's three messages
  (`AgentAttached`, `AgentFrame`, `AgentDetached`) arriving from a workerEntity:
  `AgentWebSocketE2ETest` drives the ACP socket, but no test yet runs a job's agent to the end of a
  conversation through a workerEntity.
- **Artifact upload and storage**: the presigned URL flow is exercised end to end by
  `MockWorkerE2ETest.anArtifactTheMockUploadsIsRecorded` — the mock PUTs real bytes to LocalStack and
  the artifact appears once the sweeper has seen the size match. It runs in CI only, so treat it as
  coverage once CI is green, not before. Still open: upload quotas, and S3 object deletion in
  `ProjectService.delete`.
- **`ProjectService` concurrent deletion (`Rows.BUSY`)**: Triggering the race condition between `stopWork` and table locking in `deleteRows` requires precise multi-threaded transaction coordination.
- **Worker WebSocket `@OnError` handling**: Error reply behavior through websockets-next needs further verification.
- **ACP viewer socket OIDC authentication**: `AgentWebSocketE2ETest` tests handshake auth via PAT. Browser OIDC session cookie authentication is unexercised because `%test` disables OIDC.
- **Worker reconnection / re-registration**: Verifying that closing an old connection does not unregister a newly re-registered workerEntity session.
- **Concurrent `JobLock` acquisition on new lock names**: Concurrent first-time acquisition races rely on database unique constraint violation handling in `WorkerScheduler.acquireLock`, which requires multi-threaded concurrent transaction testing.
