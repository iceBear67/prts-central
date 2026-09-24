# Worker Protocol & WebSocket Interface

`WorkerWebSocket` (`/ws/worker`) manages persistent full-duplex communication with execution workers.

## Message Model

Every message travels inside an envelope, `ClientboundEnvelope` or `ServerboundEnvelope`:

```json
{"id": "...", "replyTo": null, "message": {"type": "createJob", "...": "..."}}
```

- An envelope with no `replyTo` was sent on its own initiative. The receiver answers it **exactly
  once**, with an envelope whose `replyTo` is that `id`.
- An envelope carrying a `replyTo` is itself an answer and is never answered again.
- An answer carries `Ack(ok, message)`, or the result message that request defines — today only
  `PresignedUpload`, the answer to `uploadArtifactRequest`.

The rule holds in both directions, so no message is ever matched to an answer by arrival order, and
two requests may be in flight at once.

The payloads are two sealed interfaces:
- `ServerboundMessage`: from workers (`Ack`, `Register`, `UpdateResourceInfo`, `JobStateUpdate`,
  `UpdateJobLog`, `UploadArtifactRequest`, `AgentAttached`, `AgentFrame`, `AgentDetached`, `Unknown`).
- `ClientboundMessage`: to workers (`Ack`, `CreateJob`, `CancelJob`, `InterruptJob`,
  `PresignedUpload`, `CreateVolume`, `DeleteVolume`, `AgentFrame`).

A worker that implements this protocol for tests — no containers, scripted per job — lives in
[worker-mock/README.md](../worker-mock/README.md). It models these messages itself rather than
importing the interfaces above, so it is also the executable statement of the wire format.

### Serialization & Dispatch

- **Polymorphism**: the payload is serialized via Jackson using property `"type"`. New message types
  must be registered in `@JsonSubTypes` and handled in `WorkerWebSocket#acceptMessage`. That switch is
  exhaustive over the sealed interface, so a type left unhandled fails the build rather than going
  unanswered.
- **A type this version does not know** decodes to `ServerboundMessage.Unknown` through
  `defaultImpl`, and is refused with an `Ack(false, ...)` naming the envelope. Only an envelope that
  cannot be decoded at all falls to `@OnError`, whose refusal carries no `replyTo` because there is no
  id to name.
- **Validation**: handlers are `@Blocking`. Unauthenticated requests and handler failures return
  `Ack(false, reason)` addressed to the message that caused them.
- **No handler may wait for the worker's answer to something.** The endpoint reads a connection's
  next message only once the previous handler returns, so such a wait can never be satisfied.

## Worker State Representation

- **`Worker` (In-Memory)**: Active connection session, `WorkerClient` transport handle, and live
  `Info` snapshot. Tracked in `WorkerService.activeWorkers`.
- **`WorkerEntity` (Persistent Entity)**: Database record storing persistent identity, capacity
  configuration, and `disabled` status. Upserted on initial registration.

### Active Registration Protection

`Register.workerId` is self-asserted. To prevent session hijacking, `WorkerService.registerWorker`
rejects duplicate registrations if an existing session with the same ID is still open (`Ack(false, ...)`).
If the previous session is already closed, registration succeeds and replaces the existing record. If an old
session lingers, the worker must retry or an administrator can terminate it via `POST /worker/{id}/disconnect`.
Before a registration is accepted, every job still open on that worker is failed (see
[Disconnection](#disconnection)); if that fails, the registration is refused and the worker retries.

## Disconnection

A session ends when its connection closes, when `POST /worker/{id}/disconnect` closes it, when a
registration of the same id replaces it after it closed and before its `@OnClose` ran, or when this
process stops.

- **The control plane fails the worker's jobs.** Every open job whose `Job.worker` is that worker
  becomes `FAILED`, and every request still waiting on the session fails at once. `@OnClose` does it
  for a connection that closed, `WorkerService.registerWorker` for whatever a worker still has open
  when it registers, and `WorkerService.failJobsWithoutASession` (a `StartupEvent` observer) for the
  sessions a previous run of this process held. `Job.worker` names the worker, not the session, so a
  job the worker accepted before `claimJob` committed is failed by `WorkerScheduler` once it has.
- **It releases nothing the worker holds.** The `WorkerEntity` row, its volumes in whatever state and
  their task mounts stay; a queued job that needs those volumes waits for the worker to return, or for
  its queue entry to expire.
- **The worker stops those jobs itself** and keeps its volumes. Nobody will tell it to stop, and once
  it reconnects a `jobStateUpdate` for one of them is ignored and its log lines and uploads are
  refused, since the jobs are already terminal.

`WorkerService` ends a session, registers the next one and runs the startup pass under one lock, so a
reconnecting worker is given jobs only once everything its previous sessions left open has failed.

## Placement & Scheduling (`WorkerScheduler`)

`WorkerScheduler` executes synchronous placement checks:
1. **Filtering**: Matches required `ResourceClass` capacity and verifies volume affinity (all volumes required by a job must reside on the same worker).
2. **Selection**: Picks the eligible worker with the fewest pending jobs.
3. **Concurrency Locks**:
   - Acquires a transient in-memory create lock on the chosen worker during the `createJob` call.
   - Acquires the project-scoped `JobLock` if `spec.lock()` is defined.
4. **No Internal Queueing**: Returns `scheduled = false` if no worker qualifies or locks cannot be acquired. The caller (`PendingJobDispatcher`) handles requeueing.

## Reports on a Job

`jobStateUpdate` and `updateJobLog` are accepted only from the worker the job was placed on; any
other worker gets `Ack(false, "job not assigned to this worker: ...")` and nothing is applied.
Before it sends `createJob`, `WorkerScheduler` publishes `WorkerEvent.ASSIGNED`, from which
`WorkerService` keeps a cache of the worker each job was offered to: the worker may report as soon as
it has the job, before `claimJob` commits `Job.worker`. A job missing from the cache is looked up in
`Job.worker`.
The cache holds `worker.placement-cache-size` entries (10000); only a placement not yet claimed
depends on its entry, so it has to hold what can be placed within one `create-job` timeout. `uploadArtifactRequest` and the agent messages check `Job.worker`
themselves, in `ArtifactService` and `AgentTranscript` / `AgentChannels`.

## Sending to a Worker

`WorkerClient` owns one send primitive, `call(message, timeout)`: it puts the envelope on the wire and
completes when the worker answers it. An `Ack(ok = false)` is a refusal of the operation, so it
completes the future exceptionally with a `WorkerRefusedException` carrying the worker's own
explanation; a send that never left and an answer that does not arrive in time fail it with other
exceptions, since the worker may still have acted on them. The entry is dropped from the outstanding
table however it ends.

The named operations live on `WorkerService` — `createJob`, `cancelJob`, `interruptJob`,
`createVolume`, `deleteVolume`, `sendAgentFrame` — each taking the worker's id and looking the session
up itself. Nothing outside the `agent.worker` package reaches a `WorkerClient`. A caller that does not
care about the answer simply does not join the returned future.

Each operation's bound is configuration, under `worker.timeout` (`WorkerConfig.Timeout`):
`create-job` 30s, `cancel-job` 5s, `interrupt-job` 10s, `volume` 60s, `agent-frame` 3s, `close` 5s.
The bound matters because the wait sits between two committed transactions: without it a silent worker
blocks its caller forever.

## ACP Agent Frames

- `AgentAttached(jobId, initialize, sessionId)`: Announces that the worker initialized an ACP agent session. Central caches the `initialize` response to serve subsequent browser joins.
- `AgentFrame(jobId, frame)`: Relays raw JSON-RPC frames bidirectionally.
- `AgentDetached(jobId, reason)`: Signals that the agent process terminated while the job remains active.

The three are answered `Ack(true, "")` by `WorkerWebSocket` as soon as they are dispatched;
`AgentService` handles them off the event bus and reports its own failures to the log only.
It handles them on a worker thread, one at a time in the order they arrived, so a job's frames reach
the transcript and the viewers in the order the worker sent them.
Correlation of the frames themselves is the JSON-RPC layer's business, in `AgentService`.
`AgentService.forwardToAgent` does not wait for the worker's acknowledgment of a frame it sends: it
runs while a viewer or another frame is being served, and a round trip per frame would stall that
path.

See [agent-docs/agent-acp.md](../agent-docs/agent-acp.md) for routing, method allowlists, and transcript persistence.

## Volumes

`CreateVolume` and `DeleteVolume` are answered with `Ack`. A refusal (`ok = false`) completes the
pending future exceptionally, propagating the worker's failure message to the caller.

Volume rows are committed before the call with `VolumeState.PROVISIONING` because transactions cannot span RPCs.
Upon successful acknowledgment, `VolumeService` transitions the state to `READY`; if refused, the row is deleted.
Without an answer (a timeout, or the session ending) the row stays `PROVISIONING`: the worker may have
allocated the volume and lost only the answer, and deleting the row is what sends the `DeleteVolume`
releasing it. A worker therefore answers `Ack(true)` to a `DeleteVolume` for a volume it does not hold.
Failed or unacknowledged deletions retain `RELEASING` state to prevent reuse of partially destroyed volumes.
Only `READY` volumes are usable (`VolumeState.isUsable`), enforced in `JobSpec.requireVolumesIn` and
`WorkerScheduler.workersForVolumes`.
