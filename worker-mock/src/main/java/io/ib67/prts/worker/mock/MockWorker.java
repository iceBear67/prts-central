package io.ib67.prts.worker.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ib67.prts.worker.mock.protocol.Inbound;
import io.ib67.prts.worker.mock.protocol.JobSpec;
import io.ib67.prts.worker.mock.protocol.JobState;
import io.ib67.prts.worker.mock.protocol.Outbound;
import io.ib67.prts.worker.mock.protocol.ResourceClass;
import io.ib67.prts.worker.mock.protocol.ResourceInfo;

import java.net.URI;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A worker that behaves like one of the real ones, without running containers: it connects to the
 * control plane, registers, answers every request the protocol defines, and does whatever a
 * {@link JobScript} says about the jobs it is given.
 *
 * <p>Its own logs, artifacts and agent frames are simulated; nothing here talks to Docker or S3.
 * The point is to let an e2e test drive the control plane's half of the protocol — placement,
 * state, logs, artifacts, volumes, cancellation — from a process that always does exactly what the
 * test asked for.
 *
 * <h2>Threading</h2>
 * Inbound messages are dispatched on one thread, in the order they arrive; each script runs on a
 * thread of its own. A script therefore may block, but an {@link AgentBehaviour} may not.
 *
 * <h2>Replies</h2>
 * The control plane answers every message this worker sends with exactly one message, in order, and
 * without a request id. Replies are matched here in that order, so a worker whose account of what it
 * sent drifts from the control plane's will be told so rather than silently mismatched.
 */
public final class MockWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(MockWorker.class.getName());
    private static final Duration POLL = Duration.ofMillis(10);
    private static final Sink NOT_CONNECTED = new Sink() {
        @Override
        public void send(String text) {
            throw new IllegalStateException("this mock worker is not connected");
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void close() {
        }

        @Override
        public void abort() {
        }
    };

    private final Settings settings;
    private final ExecutorService dispatcher;
    private final ExecutorService runs;
    private final ArtifactUploader uploader = new ArtifactUploader();
    /** Held while an expectation is recorded and its message sent, so the two orders agree. */
    private final Object outbox = new Object();
    private final Deque<Pending> pending = new ConcurrentLinkedDeque<>();
    private final List<Inbound> recorded = new CopyOnWriteArrayList<>();
    private final List<Outbound> sent = new CopyOnWriteArrayList<>();
    private final List<Consumer<Inbound>> observers = new CopyOnWriteArrayList<>();
    private final List<Consumer<Outbound>> watchers = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final List<Inbound.CreateVolume> volumes = new CopyOnWriteArrayList<>();
    private final List<UUID> deletedVolumes = new CopyOnWriteArrayList<>();

    private volatile Sink sink = NOT_CONNECTED;
    private volatile ResourceInfo info;
    private volatile boolean registered;

    private MockWorker(Builder builder) {
        this.settings = builder.settings();
        this.info = settings.info();
        this.dispatcher = Executors.newSingleThreadExecutor(daemon("prts-mock-dispatch"));
        this.runs = Executors.newCachedThreadPool(daemon("prts-mock-run"));
    }

    /** Runs the worker over a caller-supplied transport, for tests that never open a socket. */
    MockWorker(Builder builder, Sink sink) {
        this(builder);
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public static Builder builder(URI controlPlane, String token) {
        return new Builder(controlPlane, token);
    }

    // ---------------------------------------------------------------- connecting

    /**
     * Opens the worker WebSocket without registering.
     *
     * @throws IllegalStateException if this worker is already connected
     */
    public MockWorker connect() {
        if (sink.isOpen()) {
            throw new IllegalStateException("worker " + workerId() + " is already connected");
        }
        sink = WorkerSocket.open(
                settings.controlPlane(), settings.token(), this::enqueue, this::onTransportGone);
        return this;
    }

    /**
     * Registers this worker, reporting the resources it was configured with and waiting for the
     * control plane to accept it.
     *
     * @throws IllegalStateException if the control plane refuses the registration, or does not answer
     */
    public MockWorker register() {
        var answer = await(post(new Outbound.Register(workerId(), name(), info), "the registration"));
        if (!(answer instanceof Inbound.Response response) || !response.ok()) {
            throw new IllegalStateException(
                    "the control plane refused worker " + workerId() + ": " + why(answer));
        }
        registered = true;
        return this;
    }

    /** Closes this worker's connection politely, leaving it able to connect again. */
    public void disconnect() {
        registered = false;
        sink.close();
    }

    /** Drops this worker's connection without a close frame, the way a killed host would. */
    public void abort() {
        registered = false;
        sink.abort();
    }

    @Override
    public void close() {
        try {
            disconnect();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "closing worker " + workerId() + " failed", e);
        }
        dispatcher.shutdownNow();
        runs.shutdownNow();
    }

    public boolean isOpen() {
        return sink.isOpen();
    }

    public boolean isRegistered() {
        return registered;
    }

    // ---------------------------------------------------------------- what it is

    public UUID workerId() {
        return settings.workerId();
    }

    public String name() {
        return settings.name();
    }

    /** The snapshot this worker reports, as last sent. */
    public ResourceInfo info() {
        return info;
    }

    // ---------------------------------------------------------------- sending

    /** Sends a message without waiting for its reply. */
    public void send(Outbound message) {
        post(message, "the message " + message.getClass().getSimpleName());
    }

    /** Reports a resource snapshot, which is what placement filters on. */
    public void reportInfo(ResourceInfo info) {
        this.info = info;
        post(new Outbound.UpdateResourceInfo(info), "the resource report");
    }

    /** Reports a queue depth, keeping the capacity already reported. */
    public void reportPending(int pending) {
        reportInfo(info.withPending(pending));
    }

    /** Every message this worker has sent, in order. */
    public List<Outbound> sent() {
        return List.copyOf(sent);
    }

    // ---------------------------------------------------------------- observing

    /** Every message received so far that no {@link #nextInbound} has taken, in order. */
    public List<Inbound> inbox() {
        return List.copyOf(recorded);
    }

    /**
     * Takes the next message of the given type, waiting for it if it has not arrived.
     *
     * <p>Taking removes it from {@link #inbox()}, so a second call waits for the next one.
     *
     * @throws NoSuchElementException if nothing of the kind arrived in time
     */
    public <T extends Inbound> T nextInbound(Class<T> type, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (var message : recorded) {
                if (type.isInstance(message) && recorded.remove(message)) {
                    return type.cast(message);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new NoSuchElementException(
                        "no " + type.getSimpleName() + " arrived within " + timeout + "; got " + inbox());
            }
            sleep(POLL);
        }
    }

    /** Watches every inbound message, on the dispatch thread. */
    public MockWorker onInbound(Consumer<Inbound> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
        return this;
    }

    /** Watches every outbound message, on whichever thread sent it. */
    public MockWorker onSent(Consumer<Outbound> watcher) {
        watchers.add(Objects.requireNonNull(watcher, "watcher"));
        return this;
    }

    // ---------------------------------------------------------------- its jobs

    /** Every job this worker has been asked to run, finished or not. */
    public List<JobRun> jobs() {
        return List.copyOf(jobs.values());
    }

    public JobRun job(UUID jobId) {
        var job = jobs.get(jobId);
        if (job == null) {
            throw new NoSuchElementException("this worker was never asked to run job " + jobId);
        }
        return job;
    }

    /** Volumes this worker was asked to allocate and accepted. */
    public List<Inbound.CreateVolume> volumes() {
        return List.copyOf(volumes);
    }

    /** Volumes this worker was asked to release and accepted. */
    public List<UUID> deletedVolumes() {
        return List.copyOf(deletedVolumes);
    }

    // ---------------------------------------------------------------- the wire in

    /**
     * Funnels one message in from the transport. Tests hand messages in here too, which is how the
     * behaviour is exercised without a control plane.
     */
    void enqueue(String text) {
        try {
            dispatcher.execute(() -> onMessage(text));
        } catch (RejectedExecutionException e) {
            // The worker was closed; a message still in flight is nobody's business now.
        }
    }

    private void onTransportGone() {
        registered = false;
    }

    private void onMessage(String text) {
        Inbound message;
        try {
            message = Wire.read(text);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot decode a message from the control plane: " + text, e);
            return;
        }
        recorded.add(message);
        for (var observer : observers) {
            try {
                observer.accept(message);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "an inbound observer threw on " + message, e);
            }
        }
        switch (message) {
            case Inbound.Response reply -> completeReply(reply);
            case Inbound.PresignedUpload upload -> completeReply(upload);
            case Inbound.CreateJob create -> accept(create);
            case Inbound.CancelJob cancel -> cancel(cancel);
            case Inbound.InterruptJob interrupt -> interrupt(interrupt);
            case Inbound.CreateVolume volume -> createVolume(volume);
            case Inbound.DeleteVolume volume -> deleteVolume(volume);
            case Inbound.AgentFrame frame -> agentFrame(frame);
        }
    }

    private void completeReply(Inbound message) {
        var entry = pending.pollFirst();
        if (entry == null) {
            LOG.log(System.Logger.Level.WARNING, "the control plane sent a reply nobody asked for: " + message);
            return;
        }
        if (message instanceof Inbound.Response || entry.expected().isInstance(message)) {
            entry.reply().complete(message);
            return;
        }
        LOG.log(System.Logger.Level.ERROR,
                "expected " + entry.expected().getSimpleName() + " for " + entry.what()
                        + " but the control plane sent " + message);
        pending.addFirst(entry);
    }

    private void accept(Inbound.CreateJob request) {
        var job = new Job(request);
        if (jobs.putIfAbsent(request.jobId(), job) != null) {
            LOG.log(System.Logger.Level.WARNING,
                    "the control plane asked for job " + request.jobId() + " twice on this worker");
        }
        var script = settings.script().apply(job);
        runs.execute(() -> runScript(job, script));
    }

    private void runScript(Job job, JobScript script) {
        try {
            if (script.acknowledge()) {
                job.acknowledge();
            }
            script.run(job);
        } catch (InterruptedException e) {
            // This worker is being torn down mid-job; there is nobody left to report to.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "the script of job " + job.jobId() + " threw", e);
            job.endQuietly(JobState.FAILED);
        }
    }

    private void cancel(Inbound.CancelJob cancel) {
        var job = jobs.get(cancel.jobId());
        if (job == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "asked to cancel job " + cancel.jobId() + ", which this worker is not running");
            return;
        }
        job.markCancelled();
    }

    private void interrupt(Inbound.InterruptJob interrupt) {
        var job = jobs.get(interrupt.jobId());
        if (job == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "asked to interrupt job " + interrupt.jobId() + ", which this worker is not running");
            return;
        }
        job.markInterrupted(interrupt.reason());
    }

    private void createVolume(Inbound.CreateVolume request) {
        var refusal = settings.volumes().onCreate(request);
        if (refusal == null) {
            volumes.add(request);
        }
        post(new Outbound.VolumeAck(request.requestId(), refusal == null, refusal == null ? "" : refusal),
                "the acknowledgment of volume " + request.volumeId());
    }

    private void deleteVolume(Inbound.DeleteVolume request) {
        var refusal = settings.volumes().onDelete(request);
        if (refusal == null) {
            deletedVolumes.add(request.volumeId());
        }
        post(new Outbound.VolumeAck(request.requestId(), refusal == null, refusal == null ? "" : refusal),
                "the acknowledgment of volume " + request.volumeId());
    }

    private void agentFrame(Inbound.AgentFrame frame) {
        var job = jobs.get(frame.jobId());
        var behaviour = settings.agent();
        if (job == null || !job.hasAgent() || behaviour == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "dropped an agent frame for job " + frame.jobId() + ": no agent is attached there");
            return;
        }
        try {
            behaviour.onFrame(new Exchange(job, frame.frame()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "the agent behaviour threw on job " + frame.jobId(), e);
        }
    }

    // ---------------------------------------------------------------- the wire out

    private Pending post(Outbound message, String what) {
        return post(message, Inbound.Response.class, what);
    }

    private Pending post(Outbound message, Class<? extends Inbound> expected, String what) {
        synchronized (outbox) {
            if (!sink.isOpen()) {
                throw new IllegalStateException("worker " + workerId() + " is not connected; cannot send " + what);
            }
            var entry = new Pending(what, expected, new CompletableFuture<>());
            pending.addLast(entry);
            sent.add(message);
            for (var watcher : watchers) {
                try {
                    watcher.accept(message);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "an outbound watcher threw on " + message, e);
                }
            }
            sink.send(Wire.write(message));
            return entry;
        }
    }

    private Inbound await(Pending entry) {
        try {
            return entry.reply().get(settings.replyTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(entry);
            throw new IllegalStateException(
                    "the control plane did not answer " + entry.what() + " within " + settings.replyTimeout(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + entry.what(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(entry.what() + " failed", e.getCause());
        }
    }

    private static String why(Inbound answer) {
        if (answer instanceof Inbound.Response response) {
            return response.message().isEmpty() ? "refused without a reason" : response.message();
        }
        return answer.toString();
    }

    private record Pending(String what, Class<? extends Inbound> expected, CompletableFuture<Inbound> reply) {
    }

    // ---------------------------------------------------------------- one job

    /** One job's side of the conversation, which is what a script is handed. */
    private final class Job implements JobRun {
        private final Inbound.CreateJob request;
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean acknowledged = new AtomicBoolean();

        private volatile JobState reported = JobState.PENDING;
        private volatile String agentSessionId;
        private volatile boolean cancelled;
        private volatile String interruptReason;

        private Job(Inbound.CreateJob request) {
            this.request = request;
        }

        @Override
        public UUID jobId() {
            return request.jobId();
        }

        @Override
        public JobSpec spec() {
            return request.spec();
        }

        @Override
        public ResourceClass resourceClass() {
            return request.resourceClass();
        }

        @Override
        public Map<String, String> secrets() {
            return request.secrets();
        }

        @Override
        public Inbound.CreateJob request() {
            return request;
        }

        @Override
        public JobState state() {
            return reported;
        }

        @Override
        public void acknowledge() {
            if (!acknowledged.compareAndSet(false, true)) {
                return;
            }
            var answer = await(post(new Outbound.JobCreated(request.requestId()), "the acceptance of job " + jobId()));
            if (!(answer instanceof Inbound.Response response) || !response.ok()) {
                throw new IllegalStateException(
                        "the control plane refused job " + jobId() + ": " + why(answer));
            }
        }

        @Override
        public boolean isAcknowledged() {
            return acknowledged.get();
        }

        @Override
        public void log(String message) {
            log("stdout", message);
        }

        @Override
        public void log(String topic, String message) {
            post(new Outbound.UpdateJobLog(jobId(), topic, message, false), "a log line of job " + jobId());
        }

        @Override
        public void error(String message) {
            post(new Outbound.UpdateJobLog(jobId(), "stderr", message, true), "an error line of job " + jobId());
        }

        @Override
        public void state(JobState state) {
            reported = state;
            post(new Outbound.JobStateUpdate(jobId(), state), "the state of job " + jobId() + " as " + state);
            if (state.isTerminal()) {
                // A script waiting on a cancellation is released by the end of the job as well: nothing
                // else is going to happen to it.
                terminal.countDown();
                stopped.countDown();
            }
        }

        @Override
        public void running() {
            state(JobState.RUNNING);
        }

        @Override
        public void succeeded() {
            state(JobState.SUCCESS);
        }

        @Override
        public void failed() {
            state(JobState.FAILED);
        }

        @Override
        public void cancelled() {
            state(JobState.CANCELLED);
        }

        @Override
        public void upload(String name, byte[] content) {
            var answer = await(post(
                    new Outbound.UploadArtifactRequest(jobId(), name, content.length),
                    Inbound.PresignedUpload.class,
                    "the upload of " + name + " for job " + jobId()));
            if (!(answer instanceof Inbound.PresignedUpload presigned)) {
                throw new IllegalStateException(
                        "the control plane refused the upload of " + name + ": " + why(answer));
            }
            uploader.put(presigned, content);
        }

        @Override
        public void attachAgent() {
            var behaviour = requireAgent();
            attachAgent(behaviour.rootSessionId());
        }

        @Override
        public void attachAgent(String sessionId) {
            var behaviour = requireAgent();
            var answer = await(post(
                    new Outbound.AgentAttached(jobId(), behaviour.initialize(), sessionId),
                    "the agent attach of job " + jobId()));
            if (!(answer instanceof Inbound.Response response) || !response.ok()) {
                throw new IllegalStateException(
                        "the control plane refused the agent of job " + jobId() + ": " + why(answer));
            }
            agentSessionId = sessionId;
        }

        @Override
        public void agentFrame(JsonNode frame) {
            post(new Outbound.AgentFrame(jobId(), frame), "an agent frame of job " + jobId());
        }

        @Override
        public void detachAgent(String reason) {
            agentSessionId = null;
            post(new Outbound.AgentDetached(jobId(), reason), "the agent detach of job " + jobId());
        }

        @Override
        public void awaitCancellation() {
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean awaitCancellation(Duration timeout) {
            try {
                return stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }

        @Override
        public boolean wasCancelled() {
            return cancelled;
        }

        @Override
        public boolean wasInterrupted() {
            return interruptReason != null;
        }

        @Override
        public String interruptReason() {
            return interruptReason;
        }

        @Override
        public boolean awaitTerminal(Duration timeout) {
            try {
                return terminal.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private AgentBehaviour requireAgent() {
            var behaviour = settings.agent();
            if (behaviour == null) {
                throw new IllegalStateException(
                        "worker " + workerId() + " was built without an agent behaviour");
            }
            return behaviour;
        }

        private boolean hasAgent() {
            return agentSessionId != null;
        }

        private String agentSessionId() {
            return agentSessionId;
        }

        private void markCancelled() {
            cancelled = true;
            stopped.countDown();
        }

        private void markInterrupted(String reason) {
            interruptReason = reason;
            stopped.countDown();
        }

        /** Reports a terminal state without letting a closed connection mask the failure. */
        private void endQuietly(JobState state) {
            try {
                state(state);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "cannot report " + state + " for job " + jobId(), e);
            }
        }
    }

    /** The agent-side view of one forwarded frame. */
    private final class Exchange implements AgentExchange {
        private final Job job;
        private final JsonNode frame;

        private Exchange(Job job, JsonNode frame) {
            this.job = job;
            this.frame = frame;
        }

        @Override
        public UUID jobId() {
            return job.jobId();
        }

        @Override
        public String sessionId() {
            return job.agentSessionId();
        }

        @Override
        public JsonNode frame() {
            return frame;
        }

        @Override
        public void reply(JsonNode id, ObjectNode result) {
            var answer = envelope();
            answer.set("id", id);
            answer.set("result", result);
            job.agentFrame(answer);
        }

        @Override
        public void fail(JsonNode id, int code, String message) {
            var answer = envelope();
            answer.set("id", id);
            var error = answer.putObject("error");
            error.put("code", code);
            error.put("message", message);
            job.agentFrame(answer);
        }

        @Override
        public void notify(String method, ObjectNode params) {
            var notification = envelope();
            notification.put("method", method);
            notification.set("params", params);
            job.agentFrame(notification);
        }

        @Override
        public void detach(String reason) {
            job.detachAgent(reason);
        }

        private static ObjectNode envelope() {
            return JsonNodeFactory.instance.objectNode().put("jsonrpc", "2.0");
        }
    }

    // ---------------------------------------------------------------- building

    /** Settings as the builder last set them. */
    private record Settings(
            URI controlPlane,
            String token,
            UUID workerId,
            String name,
            ResourceInfo info,
            Duration replyTimeout,
            Function<JobRun, JobScript> script,
            VolumeHandler volumes,
            AgentBehaviour agent
    ) {
    }

    /**
     * Configures a mock worker. Everything has a working default, so the shortest useful worker is a
     * URL, a token and {@link #start()}.
     */
    public static final class Builder {
        private final URI controlPlane;
        private final String token;

        private UUID workerId = UUID.randomUUID();
        private String name = "mock-worker";
        private ResourceInfo info = ResourceInfo.of(8, 8192, 102400);
        private Duration replyTimeout = Duration.ofSeconds(10);
        private JobScript script = JobScript.success();
        private Function<JobRun, JobScript> resolver;
        private VolumeHandler volumes = VolumeHandler.ACCEPT;
        private AgentBehaviour agent;

        private Builder(URI controlPlane, String token) {
            this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
            this.token = Objects.requireNonNull(token, "token");
            if (controlPlane.getAuthority() == null) {
                throw new IllegalArgumentException(controlPlane + " has no host");
            }
        }

        /** The id this worker asserts. Two workers claiming one id are how a takeover is tested. */
        public Builder workerId(UUID workerId) {
            this.workerId = Objects.requireNonNull(workerId, "workerId");
            return this;
        }

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** What this worker reports it has, which placement filters on. */
        public Builder resources(int numCpus, int numMemories, int numDisks) {
            this.info = ResourceInfo.of(numCpus, numMemories, numDisks);
            return this;
        }

        /** How long to wait for the control plane to answer a message. */
        public Builder replyTimeout(Duration timeout) {
            this.replyTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** What this worker does with every job it is given. */
        public Builder onJob(JobScript script) {
            this.script = Objects.requireNonNull(script, "script");
            this.resolver = null;
            return this;
        }

        /** Chooses a script per job, for a worker handed more than one of them. */
        public Builder onJob(Function<JobRun, JobScript> resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        public Builder volumes(VolumeHandler handler) {
            this.volumes = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Lets jobs announce an agent. Without one, {@link JobRun#attachAgent()} is refused. */
        public Builder agent(AgentBehaviour behaviour) {
            this.agent = Objects.requireNonNull(behaviour, "behaviour");
            return this;
        }

        /** Connects without registering. */
        public MockWorker connect() {
            return new MockWorker(this).connect();
        }

        /** Connects and registers, which is what an e2e test usually wants. */
        public MockWorker start() {
            return connect().register();
        }

        private Settings settings() {
            var chosen = resolver == null ? (Function<JobRun, JobScript>) ignored -> script : resolver;
            return new Settings(controlPlane, token, workerId, name, info, replyTimeout, chosen, volumes, agent);
        }
    }

    // ---------------------------------------------------------------- odds and ends

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    private static ThreadFactory daemon(String prefix) {
        var counter = new AtomicInteger();
        return runnable -> {
            var thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
