# Worker Protocol & WebSocket Interface

`WorkerWebSocket` (`/ws/worker`) manages persistent full-duplex communication with execution workers.

## Message Model

The protocol is defined by two sealed interfaces:
- `ServerboundMessage`: Inbound messages from workers (`Register`, `UpdateResourceInfo`, `JobStateUpdate`, `UpdateJobLog`, `UploadArtifactRequest`).
- `ClientboundMessage`: Outbound messages to workers (`Response`, `CreateJob`, `CancelJob`, `InterruptJob`, `PresignedUpload`).

### Serialization & Dispatch
- **Polymorphism**: Serialized via Jackson using property `"type"`. New message types must be registered in `@JsonSubTypes` and handled in `WorkerWebSocket#acceptMessage`.
- **Validation**: Handlers are `@Blocking`. Unauthenticated requests or messages failing validation/decoding return `Response(false, reason)` via `@OnError`.

## Worker State Representation

- **`RegisteredWorker` (In-Memory)**: Active connection session, `WorkerClient` RPC handle, and live `Info` snapshot. Tracked in `WorkerService.activeWorkers`.
- **`Worker` (Persistent Entity)**: Database record storing persistent identity, capacity configuration, and `disabled` status. Upserted on initial registration.

## Placement & Scheduling (`WorkerScheduler`)

`WorkerScheduler` executes synchronous placement checks:
1. **Filtering**: Matches required `ResourceClass` capacity and verifies volume affinity (all volumes required by a job must reside on the same worker).
2. **Selection**: Picks the eligible worker with the fewest pending jobs.
3. **Concurrency Locks**:
   - Acquires a transient in-memory create lock on the chosen worker during the 30s `createJob` RPC.
   - Acquires the project-scoped `JobLock` if `spec.lock()` is defined.
4. **No Internal Queueing**: Returns `scheduled = false` if no worker qualifies or locks cannot be acquired. The caller (`PendingJobDispatcher`) handles requeueing.

## RPC Mechanism (`WorkerClient`)

- **Correlation**: `WorkerClient.outstanding` correlates responses using `requestId` (unique per dispatch attempt).
- **Timeouts**: `createJob` blocks for up to 30 seconds for `JobCreated` acknowledgment.
- **Cancellation**: `cancelJob` is fire-and-forget.
- **Interruption (`InterruptJob`)**: Signals immediate container termination without expecting terminal status callbacks (used during project deletion).

