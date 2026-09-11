# Worker Protocol & WebSocket Interface

`WorkerWebSocket` (`/ws/worker`) manages persistent full-duplex communication with execution workers.

## Message Model

The protocol is defined by two sealed interfaces:
- `ServerboundMessage`: Inbound messages from workers (`Register`, `UpdateResourceInfo`, `JobCreated`, `JobStateUpdate`, `UpdateJobLog`, `UploadArtifactRequest`, `VolumeAck`).
- `ClientboundMessage`: Outbound messages to workers (`Response`, `CreateJob`, `CancelJob`, `InterruptJob`, `PresignedUpload`, `CreateVolume`, `DeleteVolume`).

### Serialization & Dispatch
- **Polymorphism**: Serialized via Jackson using property `"type"`. New message types must be registered in `@JsonSubTypes` and handled in `WorkerWebSocket#acceptMessage`.
- **Validation**: Handlers are `@Blocking`. Unauthenticated requests or messages failing validation/decoding return `Response(false, reason)` via `@OnError`.

## Worker State Representation

- **`RegisteredWorker` (In-Memory)**: Active connection session, `WorkerClient` RPC handle, and live `Info` snapshot. Tracked in `WorkerService.activeWorkers`.
- **`Worker` (Persistent Entity)**: Database record storing persistent identity, capacity configuration, and `disabled` status. Upserted on initial registration.

### Registration Refuses a Live Id

`Register.workerId` is self-asserted, so `WorkerService.registerWorker` **refuses** a second
registration while the id's current session is still open (`Response(false, ...)`, logged as a warning)
instead of displacing it: the claimant would otherwise inherit every job — and every project secret —
routed to that id. A session that is already closed but not yet unregistered is replaced, so a
reconnect after a dropped connection still lands. A worker whose old session lingers must retry, or an
admin has to close it with `POST /worker/{id}/disconnect`.

## Placement & Scheduling (`WorkerScheduler`)

`WorkerScheduler` executes synchronous placement checks:
1. **Filtering**: Matches required `ResourceClass` capacity and verifies volume affinity (all volumes required by a job must reside on the same worker).
2. **Selection**: Picks the eligible worker with the fewest pending jobs.
3. **Concurrency Locks**:
   - Acquires a transient in-memory create lock on the chosen worker during the 30s `createJob` RPC.
   - Acquires the project-scoped `JobLock` if `spec.lock()` is defined.
4. **No Internal Queueing**: Returns `scheduled = false` if no worker qualifies or locks cannot be acquired. The caller (`PendingJobDispatcher`) handles requeueing.

## RPC Mechanism (`WorkerClient`)

- **Correlation**: `WorkerClient.outstanding` correlates responses using `requestId` (unique per request). Job creation and volume operations share the table; `WorkerClient.await` is the single blocking send.
- **Timeouts**: `createJob` blocks for up to 30 seconds for `JobCreated`; `createVolume` / `deleteVolume` for up to 60, since allocating a disk can outlast starting a container.
- **Cancellation**: `cancelJob` is fire-and-forget.
- **Interruption (`InterruptJob`)**: Signals immediate container termination without expecting terminal status callbacks (used during project deletion).

## Volumes

`CreateVolume` and `DeleteVolume` are both answered by a single `VolumeAck(requestId, ok, message)`. A
refusal (`ok = false`) completes the pending future exceptionally, so the worker's reason — "no space",
say — reaches the HTTP caller instead of being flattened into a generic failure.

Volume rows are committed *before* the RPC (`VolumeState.PROVISIONING`), because a transaction may not
span one. `VolumeService` promotes to `READY` on acknowledgment and drops the row if the worker refuses.
`RELEASING` is deliberately sticky: a delete whose acknowledgment never arrived stays releasing rather
than handing the volume back out while its data may already be gone. Only `READY` volumes are mountable
(`VolumeState.isUsable`), enforced in both `JobSpec.requireVolumesIn` and `WorkerScheduler.workersForVolumes`.

