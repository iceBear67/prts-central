# Worker Protocol & WebSocket Interface

`WorkerWebSocket` (`/ws/worker`) manages persistent full-duplex communication with execution workers.

## Message Model

The protocol is defined by two sealed interfaces:
- `ServerboundMessage`: Inbound messages from workers (`Register`, `UpdateResourceInfo`, `JobCreated`, `JobStateUpdate`, `UpdateJobLog`, `UploadArtifactRequest`, `VolumeAck`, `AgentAttached`, `AgentFrame`, `AgentDetached`).
- `ClientboundMessage`: Outbound messages to workers (`Response`, `CreateJob`, `CancelJob`, `InterruptJob`, `PresignedUpload`, `CreateVolume`, `DeleteVolume`, `AgentFrame`).

A worker that implements this protocol for tests — no containers, scripted per job — lives in
[worker-mock/README.md](../worker-mock/README.md). It models these messages itself rather than
importing the interfaces above, so it is also the executable statement of the wire format.

### Serialization & Dispatch
- **Polymorphism**: Serialized via Jackson using property `"type"`. New message types must be registered in `@JsonSubTypes` and handled in `WorkerWebSocket#acceptMessage`.
- **Validation**: Handlers are `@Blocking`. Unauthenticated requests or messages failing validation/decoding return `Response(false, reason)` via `@OnError`.

## Worker State Representation

- **`RegisteredWorker` (In-Memory)**: Active connection session, `WorkerClient` RPC handle, and live `Info` snapshot. Tracked in `WorkerService.activeWorkers`.
- **`Worker` (Persistent Entity)**: Database record storing persistent identity, capacity configuration, and `disabled` status. Upserted on initial registration.

### Active Registration Protection

`Register.workerId` is self-asserted. To prevent session hijacking, `WorkerService.registerWorker`
rejects duplicate registrations if an existing session with the same ID is still open (`Response(false, ...)`).
If the previous session is already closed, registration succeeds and replaces the existing record. If an old
session lingers, the worker must retry or an administrator can terminate it via `POST /worker/{id}/disconnect`.

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

## ACP Agent Frames

- `AgentAttached(jobId, initialize, sessionId)`: Announces that the worker initialized an ACP agent session. Central caches the `initialize` response to serve subsequent browser joins.
- `AgentFrame(jobId, frame)`: Relays raw JSON-RPC frames bidirectionally.
- `AgentDetached(jobId, reason)`: Signals that the agent process terminated while the job remains active.

`ClientboundMessage.AgentFrame` is unacknowledged; correlation is handled at the JSON-RPC layer by `AgentService`. Serverbound `AgentFrame` messages receive standard `Response` acknowledgments.

See [agent-docs/agent-acp.md](../agent-docs/agent-acp.md) for routing, method allowlists, and transcript persistence.

## Volumes

`CreateVolume` and `DeleteVolume` are acknowledged via `VolumeAck(requestId, ok, message)`. A rejection
(`ok = false`) completes the pending future exceptionally, propagating the worker's failure message to the caller.

Volume rows are committed before the RPC with `VolumeState.PROVISIONING` because transactions cannot span RPCs.
Upon successful acknowledgment, `VolumeService` transitions the state to `READY`; if refused, the row is deleted.
Failed or unacknowledged deletions retain `RELEASING` state to prevent reuse of partially destroyed volumes.
Only `READY` volumes are usable (`VolumeState.isUsable`), enforced in `JobSpec.requireVolumesIn` and
`WorkerScheduler.workersForVolumes`.

