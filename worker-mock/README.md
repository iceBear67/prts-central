# worker-mock

A worker that speaks the control plane's protocol without running anything. It connects to
`/ws/worker`, registers, answers every request, and does exactly what a test tells it to about the
jobs it is given — so an e2e test can drive a job from placement to its end state, and watch what the
control plane does with its logs, artifacts and volumes on the way, without a container runtime.

It is a Gradle subproject with **no dependency on the control plane's classes**. It models the
protocol itself, the way the real workers do (they live in another repository), so the two sides can
only be kept honest by the wire format rather than by sharing a type. `WireContractTest` pins this
side of it: every message is written out by hand and compared as JSON. What the control plane actually
accepts is settled by `MockWorkerEntityE2ETest`, which is the only place the two meet.

```
worker-mock/
  src/main/java/io/ib67/prts/worker/mock/
    MockWorker.java        the worker: connection, the outstanding answers, the job roster
    JobScript.java         what to do with a job, as composable steps
    JobRun.java            one job as a script sees it
    AgentBehaviour.java    how a job's agent answers the frames it is handed
    VolumeHandler.java     whether to accept a volume
    MockWorkerMain.java    the same worker as a standalone process
    Sink.java / WorkerSocket.java   the transport, and its only real implementation
    Wire.java              the JSON codec
    protocol/              this module's own model of the messages
  src/test/java/...        the wire contract, the behaviour, the upload PUT
```

## Using it

```java
var worker = MockWorker.builder(baseUri, "test-worker-secret")   // http(s):// or ws(s)://
        .name("w1")
        .onJob(JobScript.started()
                .andThen(JobScript.log("building"))
                .andThen(JobScript.upload("report.txt", "done"))
                .andThen(JobRun::succeeded))
        .start();                                                 // connect, then register
```

`baseUri` may be the control plane's base URL — the mock appends `/ws/worker` — or the endpoint
itself. The token goes in `X-Worker-Token`; a refused handshake (HTTP 401) is an
`IllegalStateException` naming the status.

Tear down with `worker.close()`. An empty worker roster is **not** the end of the control plane's own
teardown: wait for the work tail as well (`Job.listOpenByWorker(workerId).isEmpty()`), or the next
test's `TRUNCATE` deadlocks against the transactions still failing those jobs — see
[agent-docs/testing.md](../agent-docs/testing.md).

## Behaviour

### The reply rule

Every message travels inside an envelope: `{"id": ..., "replyTo": ..., "message": {...}}`. An
envelope with no `replyTo` was sent on its own initiative and is answered **exactly once**, by an
envelope whose `replyTo` is that `id`. An envelope that carries a `replyTo` is itself an answer and
is never answered again. The rule holds in both directions.

An answer carries either an `ack` — `ok` plus the reason it was refused — or, for an
`uploadArtifactRequest` the control plane accepted, a `presignedUpload`. An `ack` where the upload's
presigned URL was expected *is* the control plane refusing the upload, which is how the protocol
says it.

The mock keeps what it sent keyed by envelope id and matches each answer to the id it names, so two
requests in flight at once cannot be confused for one another. An answer naming an id nothing is
waiting on is counted in `unclaimedReplies()`. `await` gives up after the reply timeout (10 seconds
by default), naming what was never answered.

### Threading

- Inbound messages are dispatched on **one thread, in arrival order**. A handler therefore sees
  messages in the order the control plane sent them.
- The dispatcher **never waits for a reply**. Everything that arrives unsolicited is answered on
  arrival, except `createJob`, whose answer is the script's to send.
- Each job's script runs on **a thread of its own**, so a script may block — waiting for a
  cancellation, or for the test to release it.
- An `AgentBehaviour` runs **on the dispatcher thread**, as do the `onJob` resolver that picks a
  script and the volume handler. None of them may block, or every later message from the control plane
  waits behind it.

### Jobs

`createJob` starts the script chosen by `onJob(...)` — one script for every job, or a resolver
`Function<JobRun, JobScript>` for a worker given several. Before the script runs, unless it declines,
the mock answers the `createJob` envelope with `ack`: the control plane blocks for up to 30 seconds
on that acknowledgment and places nothing until it arrives. A script that declines never answers,
which is how a worker that will not take the job is written.

A script reports through `JobRun`, and nothing is implicit — a script that reports nothing leaves the
job exactly as the control plane sees it: `PENDING`, assigned to this worker, never finishing.

| Step | What goes on the wire |
| --- | --- |
| `running()` / `succeeded()` / `failed()` / `cancelled()` / `state(s)` | `jobStateUpdate` |
| `log(message)` | `updateJobLog` under `stdout` |
| `log(topic, message)` | `updateJobLog` under that topic |
| `error(message)` | `updateJobLog` under `stderr`, flagged as an error |
| `upload(name, bytes)` | `uploadArtifactRequest`, then a PUT |
| `attachAgent()` | `agentAttached` |
| `agentFrame(frame)` / `detachAgent(reason)` | `agentFrame` / `agentDetached` |

The canned steps compose:

```java
JobScript.started()                  // accept, report RUNNING
        .andThen(JobScript.log("…"))
        .andThen(JobScript.sleep(Duration.ofSeconds(1)))
        .andThen(JobRun::succeeded)
```

`success()` is `started()` plus `SUCCESS`, `failure(reason)` is `started()` plus an error line plus
`FAILED`, `busy()` is `started()` plus waiting forever for a cancellation, and `silent()` accepts
nothing at all. A script that throws is logged and the job is reported `FAILED` best-effort, so a
broken script shows up as a failed job rather than as a job that never ends.

A script that has reported a terminal state is done as far as the control plane is concerned: later
log lines are refused (`job already completed`), and the mock sends them without waiting for an
answer, so nothing surfaces. Compose accordingly.

### Cancellation and interruption

`cancelJob` and `interruptJob` mark the job and release anything waiting in `awaitCancellation()`,
which is what `busy()` does. Nothing is reported either way — by the time the control plane asks, it
has already moved the job to `CANCELLED` itself. Afterwards `wasCancelled()`, `wasInterrupted()` and
`interruptReason()` say which happened.

Note that a cancellation only counts for the *script*: reach for `awaitCancellation()` if the job
should behave like one that stops when told. A script that ignores it keeps running, which is how a
worker that does not honour a cancellation can be imitated.

Losing the connection — `disconnect()`, `abort()`, or the control plane going away — stops every job
that has not reported a terminal state, the way a real worker does: the control plane fails them all
and accepts no report on them. They end up `wasCancelled()` with no `cancelJob` in `inbox()`, and a
script still waiting on a reply is failed at once rather than at the reply timeout. Volumes are kept.

### Artifacts

`upload(name, content)` announces `content.length` as the size, waits for the presigned URL and then
PUTs the bytes to it with the method the control plane named, throwing on any non-2xx. The size the
control plane reserved is the size of the array: **the control plane records nothing until its sweeper
finds an object of exactly that size**, and the sweeper only promotes an upload while the job is still
open and assigned to the worker that sent it — and it looks every two seconds. So an artifact shows up
a few seconds after the upload, a script that uploads and then finishes at once loses it, and a worker
that writes a different number of bytes uploads into nothing.

### Volumes

`createVolume` and `deleteVolume` are answered with `ack`. `VolumeHandler` decides: the
default accepts everything, `VolumeHandler.refusing(reason)` refuses everything the way a host out of
disk would. A refused allocation is deleted by the control plane, so it is not left `PROVISIONING`;
one left unanswered, because the connection dropped first, stays `PROVISIONING` until it is deleted.
What the mock accepted is visible from `worker.volumes()` and `worker.deletedVolumes()`.

### The agent

`attachAgent()` sends `agentAttached` carrying the configured behaviour's `initialize()` verbatim and
its root session id, and waits for the control plane to accept it before returning — so a frame
arriving straight afterwards finds the job attached. `AgentBehaviour` then answers every frame the
control plane relays, through `reply`, `fail` and `notify`, which are JSON-RPC 2.0 as ACP spells it.
`detachAgent(reason)` ends the agent while the job keeps running.

`AgentBehaviour.echoing()` is the canned one: a `session/prompt` is answered with a `session/update`
(`agent_message_chunk`) echoing the prompt's text, then `{"stopReason":"end_turn"}`; `session/cancel`
answers `{"stopReason":"cancelled"}`; any other request is refused with `-32601`. It does not model
the proxy: which methods may cross, and to whom, is the control plane's business, so a behaviour may
answer anything.

A frame addressed to a job with no attached agent is dropped with a warning — the mock does not fail
the job over it.

### What it reports about itself

The mock reports the resources it was built with (8 CPUs, 8192 memory units and 102400 disk units by
default) and nothing else. It does **not** keep capacity for the jobs it is running: the snapshot
does not change when a job starts, so placement will go on choosing it. Use `reportInfo(...)` or
`reportPending(...)` to say otherwise, which is also how a worker that runs out of room is imitated.

`nextInbound(type, timeout)` takes a message (removing it from `inbox()`), `onInbound`/`onSent` watch
both directions, and `sent()`, `inbox()`, `jobs()`, `job(id)`, `volumes()` and `deletedVolumes()` are
the record of what happened.

### What it deliberately does not do

- No containers, no images, no real logs: everything a test can observe is what a script reported.
- No capacity bookkeeping of its own (above), and no placement decisions — those are the control
  plane's.
- No ACP allowlist or id rewriting: it relays what a behaviour tells it to.
- No reconnection or retry: `close()` drops the connection and shuts its threads down, and a worker
  closed that way is not usable again. `disconnect()` is the one that can connect again.

## In dev mode

The control plane runs one of these for itself: `MockWorkerRunner` (`%dev` only) connects a worker
back to dev mode at startup, so what the UI creates is actually placed and run. Its scripts, the
`prts.mock` label a job picks one with, and the configuration are in
[agent-docs/build-and-run.md](../agent-docs/build-and-run.md). This module reaches that classpath
through `compileOnly` and `quarkusDev`, never a production one.

## As a standalone process

The same worker runs out of process for a control plane that is already up — dev mode, a staging
deployment, or a test that wants the worker outside its own JVM:

```
./gradlew :worker-mock:run --args="--url http://localhost:8080 --token allo --script succeed"
```

```
--url <url>        control plane base URL (required)
--token <secret>   the shared worker secret (default: allo, the dev value)
--name <name>      the name to register under (default: mock-worker)
--id <uuid>        the worker id to assert (default: a fresh one)
--cpus/--mem/--disk  reported resources (default: 8 / 8192 / 102400)
--script <name>    succeed | fail | hang | upload | silent (default: succeed)
--agent            let every job attach an echoing agent
--jobs <n>         exit once n jobs have finished (default: run until killed)
--help             print this list
```

Every message in either direction is printed, `>` for what the worker sends and `<` for what it
receives. `--jobs` does not combine with `--script hang`, which never finishes anything.

## Testing

- `./gradlew :worker-mock:test` runs this module's own tests: the wire contract, the script and
  cancellation behaviour, the agent, volumes, and the upload PUT against a local HTTP endpoint. No
  containers, so it runs anywhere.
- `MockWorkerEntityE2ETest` in the control plane (`./gradlew e2eTest`, tagged `e2e`) is where the mock meets
  the real control plane: placement, state, logs, secrets, artifacts, volumes and cancellation. Tier
  C needs Docker and is CI's job; see [agent-docs/testing.md](../agent-docs/testing.md).

`JobScript.silent()` reaches the control plane's 30-second create timeout, which fails the job and
throws from `JobLauncher.launch`. The mock's own tests cover the mock's half of it; there is no e2e
test for it, because half a minute per run is a poor trade for one path.
