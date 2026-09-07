# Worker session & scheduling

`WorkerWebSocket` (`/ws/worker`) is a thin dispatcher. The protocol is two **sealed interfaces**,
`ServerboundMessage` and `ClientboundMessage`, serialized by Jackson with polymorphic type info on a
property named `"type"` — not `visible`, so Jackson consumes it and never binds it, which is why
`Register` spells its own field `workerId` rather than `id`. Adding a message means adding a record
*and* its `@JsonSubTypes` entry, then a case in the `switch` in `WorkerWebSocket#acceptMessage` (the
switch is exhaustive, so the compiler will point you there). Handlers are `@Blocking` and return a
`ClientboundMessage`.

Anything but `Register` on a connection that has not registered is refused, and a message that fails
to *decode* — a missing required component, since every one of them is `requireNonNull`ed in the
canonical constructor — reaches `@OnError`, which answers a `Response(false, ...)` instead of letting
the connection close unexplained.

Two distinct notions of "worker" — keep them straight:

- `agent.worker.RegisteredWorker` — the **live, in-memory** session (name, `WorkerClient` RPC handle,
  last reported `Info` snapshot). Keyed by worker UUID in `WorkerService.activeWorkers`.
- `agent.worker.entity.Worker` — the **persistent** identity row, upserted on register.

`WorkerService` owns the `activeWorkers` map; `WorkerScheduler` (package-private, deliberately)
holds the placement logic:

- picks the eligible worker with the fewest pending jobs, filtered by `ResourceClass` capacity and by
  **volume affinity** — all volumes in a spec must live on one worker, or the job is unplaceable;
- takes a short-lived per-worker *create lock* while the blocking `createJob` RPC is in flight,
  released on the `JobCreated` ack;
- acquires the spec's `JobLock` when it names one;
- **the scheduler itself does not queue**: it places the job or says why it could not, and waiting is
  the caller's business (see [job-lifecycle.md](job-lifecycle.md)). `schedule0` returns the reason — no
  eligible worker, or the `JobLock` is held — `null` meaning nothing more is needed, which also covers
  "job went terminal meanwhile". `WorkerService.schedule` logs that reason and reduces it to a
  `boolean`; it deliberately throws no `WebApplicationException`, because what to do about it is the
  caller's call. `JobLauncher.dispatch` reports `CreatedJob(job, scheduled=false)` and touches nothing;
  only `PendingJobDispatcher` acts on it, by discarding the job and requeueing the entry.

`WorkerClient` is the request/response side. Its `outstanding` map is keyed by **attempt id**
(`requestId`), not job id, so a late ack for an abandoned attempt cannot be mistaken for an ack of a
retry. `createJob` blocks up to 30s; `cancelJob` is fire-and-forget because our state is already
terminal.

`ClientboundMessage.InterruptJob` is the third of the job-directed clientbound messages (beside
`Response` and `PresignedUpload`) and is **not** `CancelJob`: cancel
means "stop it, the row is terminal but still expects your final word", interrupt means "the job no
longer exists here, so your state updates, logs and uploads have nowhere to land". **The worker repo
needs the matching record**; until it does, that `type` fails to deserialize there. See
[project-deletion.md](project-deletion.md), its only sender.

Workers are administered over HTTP by a human through `WorkerResource` — see
[http-surface.md](http-surface.md).
