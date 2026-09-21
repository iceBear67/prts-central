package io.ib67.prts.worker.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.ib67.prts.worker.mock.protocol.Inbound;
import io.ib67.prts.worker.mock.protocol.JobState;
import io.ib67.prts.worker.mock.protocol.ResourceInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the mock does with what it is sent, driven over a transport the test owns.
 *
 * <p>Nothing here opens a socket or starts a control plane: the worker is handed a {@link Sink} the
 * test writes, which is the only way to say exactly what arrives, in what order, and when.
 */
class MockWorkerTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final UUID WORKER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOLUME = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final FakeControlPlane controlPlane = new FakeControlPlane();
    private final List<MockWorker> workers = new ArrayList<>();

    @AfterEach
    void stop() {
        workers.forEach(MockWorker::close);
    }

    // ---------------------------------------------------------------- registering

    @Test
    void registeringSendsTheIdentityTheWorkerAssertsForItself() {
        var worker = start(worker().name("w1"));

        assertTrue(worker.isRegistered());
        assertEquals(List.of("register"), types());
        assertEquals("w1", controlPlane.sent().get(0).path("name").asText());
        assertEquals(WORKER.toString(), controlPlane.sent().get(0).path("workerId").asText());
    }

    @Test
    void theResourcesTheWorkerWasBuiltWithAreWhatItRegisters() {
        start(worker().resources(2, 512, 1024));

        var info = controlPlane.sent().get(0).path("info");
        assertEquals(2, info.path("current").path("numCpus").asInt());
        assertEquals(512, info.path("capacity").path("numMemories").asInt());
        assertEquals(1024, info.path("capacity").path("numDisks").asInt());
        assertEquals(0, info.path("pending").asInt());
    }

    @Test
    void aRefusedRegistrationIsReportedWithItsReason() {
        controlPlane.answer("register", message -> reply(false, "already has a live session"));
        var worker = connect(worker());

        var failure = assertThrows(IllegalStateException.class, worker::register);

        assertTrue(failure.getMessage().contains("already has a live session"), failure.getMessage());
        assertFalse(worker.isRegistered());
    }

    @Test
    void aRegistrationNobodyAnswersGivesUpNamingWhatWasAsked() {
        controlPlane.answer("register", message -> null);
        var worker = connect(worker().replyTimeout(Duration.ofMillis(200)));

        var failure = assertThrows(IllegalStateException.class, worker::register);

        assertTrue(failure.getMessage().contains("the registration"), failure.getMessage());
    }

    @Test
    void sendingOnADisconnectedWorkerIsRefused() {
        var worker = start(worker());
        worker.disconnect();

        var failure = assertThrows(IllegalStateException.class, () -> worker.reportPending(1));

        assertTrue(failure.getMessage().contains("not connected"), failure.getMessage());
    }

    // ---------------------------------------------------------------- running a job

    @Test
    void aJobIsAcceptedBeforeItsScriptReportsAnything() {
        start(worker().onJob(JobScript.started()
                .andThen(JobScript.log("building the thing"))
                .andThen(JobRun::succeeded)));

        deliver(createJob(JOB));

        await(() -> states().contains(JobState.SUCCESS), "the job never finished");
        assertEquals(List.of("register", "ack", "jobStateUpdate", "updateJobLog",
                "jobStateUpdate"), types());
        assertEquals(JobState.RUNNING, states().get(0));
        var log = controlPlane.firstSent("updateJobLog");
        assertEquals("stdout", log.path("topic").asText());
        assertEquals("building the thing", log.path("message").asText());
        assertFalse(log.path("error").asBoolean());
    }

    @Test
    void aScriptThatThrowsLeavesTheJobFailed() {
        start(worker().onJob(JobScript.doing(job -> {
            job.running();
            throw new IllegalStateException("the script came apart");
        })));

        deliver(createJob(JOB));

        await(() -> states().contains(JobState.FAILED), "the failure was never reported");
        assertEquals(List.of(JobState.RUNNING, JobState.FAILED), states());
    }

    @Test
    void aScriptThatReportsNothingLeavesTheJobWhereItWas() {
        start(worker().onJob(JobScript.silent()));

        deliver(createJob(JOB));

        await(() -> !awaitJobQuietly().isEmpty(), "the job never arrived");
        assertEquals(List.of("register"), types());
        assertFalse(awaitJobQuietly().get(0).isAcknowledged());
    }

    @Test
    void aJobIsHandedTheSpecTheClassAndTheSecretsItCameWith() {
        start(worker().onJob(JobRun::succeeded));

        deliver(createJob(JOB));

        await(() -> !awaitJobQuietly().isEmpty(), "the job never arrived");
        var job = awaitJobQuietly().get(0);
        assertEquals("alpine:3.20", job.spec().image());
        assertEquals("small", job.resourceClass().name());
        assertEquals("s3cret", job.secrets().get("TOKEN"));
        assertEquals(JOB, job.jobId());
    }

    @Test
    void aWorkerWithAResolverChoosesAScriptForEachJob() {
        var offered = new CopyOnWriteArrayList<UUID>();
        start(worker().onJob(run -> {
            offered.add(run.jobId());
            return JobScript.state(JobState.FAILED);
        }));
        var second = UUID.randomUUID();

        deliver(createJob(JOB));
        deliver(createJob(second));

        await(() -> states().size() == 2, "both jobs were never given a script");
        assertEquals(List.of(JOB, second), offered);
        assertEquals(List.of("FAILED", "FAILED"), controlPlane.stateNames());
    }

    // ---------------------------------------------------------------- stopping a job

    @Test
    void aCancellationReleasesAScriptWaitingOnIt() throws InterruptedException {
        var released = new CountDownLatch(1);
        start(worker().onJob(job -> {
            job.running();
            job.awaitCancellation();
            released.countDown();
        }));
        deliver(createJob(JOB));
        await(() -> states().contains(JobState.RUNNING), "the job never started");

        deliver("""
                {"type":"cancelJob","jobId":"22222222-2222-2222-2222-222222222222"}""");

        assertTrue(released.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the job was never released");
        var job = awaitJob();
        assertTrue(job.wasCancelled());
        assertFalse(job.wasInterrupted());
    }

    @Test
    void anInterruptionCarriesItsReasonToTheScript() {
        var released = new CountDownLatch(1);
        start(worker().onJob(job -> {
            job.running();
            job.awaitCancellation();
            released.countDown();
        }));
        deliver(createJob(JOB));
        await(() -> states().contains(JobState.RUNNING), "the job never started");

        deliver("""
                {"type":"interruptJob",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "reason":"the project was deleted"}""");

        await(() -> released.getCount() == 0, "the interruption never reached the script");
        var job = awaitJob();
        assertTrue(job.wasInterrupted());
        assertEquals("the project was deleted", job.interruptReason());
    }

    @Test
    void anInterruptionForAJobThisWorkerNeverRanIsIgnored() {
        var worker = start(worker().onJob(JobScript.success()));

        deliver("""
                {"type":"interruptJob","jobId":"22222222-2222-2222-2222-222222222222",
                 "reason":"too late"}""");
        worker.reportPending(3);

        await(() -> types().contains("updateResourceInfo"), "the worker stopped answering");
        assertFalse(types().contains("jobStateUpdate"));
    }

    // ---------------------------------------------------------------- volumes

    @Test
    void aVolumeRequestIsAcknowledgedAndRemembered() {
        var worker = start(worker());

        deliver(createVolume());

        await(() -> types().contains("ack"), "the volume was never acknowledged");
        assertTrue(controlPlane.firstSent("ack").path("ok").asBoolean());
        assertEquals(VOLUME, worker.volumes().get(0).volumeId());
    }

    @Test
    void aRefusedVolumeIsAcknowledgedWithTheReasonInstead() {
        var worker = start(worker().volumes(VolumeHandler.refusing("no space left on the host")));

        deliver(createVolume());

        await(() -> types().contains("ack"), "the volume was never acknowledged");
        var ack = controlPlane.firstSent("ack");
        assertFalse(ack.path("ok").asBoolean());
        assertEquals("no space left on the host", ack.path("message").asText());
        assertTrue(worker.volumes().isEmpty());
    }

    @Test
    void aVolumeReleaseIsAcknowledgedAndRemembered() {
        var worker = start(worker());

        deliver("""
                {"type":"deleteVolume",
                 "volumeId":"44444444-4444-4444-4444-444444444444"}""");

        await(() -> types().contains("ack"), "the release was never acknowledged");
        assertEquals(List.of(VOLUME), worker.deletedVolumes());
    }

    // ---------------------------------------------------------------- artifacts

    @Test
    void anArtifactIsPutWhereTheControlPlaneSaysToPutIt() {
        try (var storage = new LocalStorage()) {
            controlPlane.answer("uploadArtifactRequest",
                    request -> presignedUpload(request, storage.url("/obj")));
            start(worker().onJob(JobScript.started()
                    .andThen(JobScript.upload("report.txt", "hello from the mock"))
                    .andThen(JobRun::succeeded)));

            deliver(createJob(JOB));

            await(() -> !storage.stored().isEmpty(), "nothing was uploaded");
            var upload = storage.stored().get(0);
            assertEquals("PUT", upload.method());
            assertEquals("/obj", upload.path());
            assertArrayEquals("hello from the mock".getBytes(StandardCharsets.UTF_8), upload.body());
            await(() -> states().contains(JobState.SUCCESS), "the job never finished");
        }
    }

    @Test
    void anUploadTheControlPlaneRefusesFailsTheScript() {
        var refusal = new CompletableFuture<String>();
        controlPlane.answer("uploadArtifactRequest", request -> reply(false, "over the project quota"));
        start(worker().onJob(JobScript.doing(job -> {
            job.running();
            try {
                job.upload("report.txt", new byte[]{1});
            } catch (RuntimeException e) {
                refusal.complete(e.getMessage());
                throw e;
            }
        })));

        deliver(createJob(JOB));

        await(() -> refusal.isDone(), "the refusal never surfaced");
        assertTrue(refusal.join().contains("over the project quota"), refusal.join());
        await(() -> states().contains(JobState.FAILED), "the failure was never reported");
    }

    // ---------------------------------------------------------------- the agent

    @Test
    void anAgentAttachIsOfferedToTheControlPlaneBeforeTheScriptGoesOn() {
        var attached = new CountDownLatch(1);
        start(worker()
                .agent(AgentBehaviour.echoing())
                .onJob(job -> {
                    job.attachAgent();
                    attached.countDown();
                }));

        deliver(createJob(JOB));

        await(() -> attached.getCount() == 0, "the agent was never attached");
        var attach = controlPlane.firstSent("agentAttached");
        assertEquals("agent-session", attach.path("sessionId").asText());
        assertEquals(1, attach.path("initialize").path("protocolVersion").asInt());
    }

    @Test
    void aPromptIsAnsweredByTheConfiguredBehaviour() throws InterruptedException {
        var attached = new CountDownLatch(1);
        start(worker()
                .agent(AgentBehaviour.echoing())
                .onJob(job -> {
                    job.running();
                    job.attachAgent();
                    attached.countDown();
                    job.awaitCancellation();
                }));
        deliver(createJob(JOB));
        assertTrue(attached.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the agent was never attached");

        deliver("""
                {"type":"agentFrame",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "frame":{"jsonrpc":"2.0","id":1,"method":"session/prompt",
                          "params":{"sessionId":"session-1",
                                    "prompt":[{"type":"text","text":"say hello"}]}}}""");

        await(() -> controlPlane.agentFrames().size() == 2, "the prompt was never answered");
        var frames = controlPlane.agentFrames();
        assertEquals("session/update", frames.get(0).path("frame").path("method").asText());
        assertEquals("say hello", frames.get(0).path("frame").path("params").path("update")
                .path("content").path("text").asText());
        assertEquals("end_turn", frames.get(1).path("frame").path("result")
                .path("stopReason").asText());
    }

    @Test
    void aMethodTheAgentDoesNotServeIsRefused() {
        var attached = new CountDownLatch(1);
        start(worker()
                .agent(AgentBehaviour.echoing())
                .onJob(job -> {
                    job.running();
                    job.attachAgent();
                    attached.countDown();
                    job.awaitCancellation();
                }));
        deliver(createJob(JOB));
        await(() -> attached.getCount() == 0, "the agent was never attached");

        deliver("""
                {"type":"agentFrame",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "frame":{"jsonrpc":"2.0","id":9,"method":"session/load"}}""");

        await(() -> !controlPlane.agentFrames().isEmpty(), "nothing was answered");
        var refusal = controlPlane.agentFrames().get(0);
        assertEquals(AgentExchange.METHOD_NOT_FOUND,
                refusal.path("frame").path("error").path("code").asInt());
    }

    /**
     * A frame carrying no method is a reply to a request the agent never sent, so it must not be
     * answered as a request would be.
     */
    @Test
    void aReplyToTheAgentIsNotRefusedAsARequest() {
        var attached = new CountDownLatch(1);
        start(worker()
                .agent(AgentBehaviour.echoing())
                .onJob(job -> {
                    job.running();
                    job.attachAgent();
                    attached.countDown();
                    job.awaitCancellation();
                }));
        deliver(createJob(JOB));
        await(() -> attached.getCount() == 0, "the agent was never attached");

        deliver("""
                {"type":"agentFrame",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "frame":{"jsonrpc":"2.0","id":9,"result":{"outcome":"selected"}}}""");
        // Frames are dispatched in order, so the refusal of this one would already be here.
        deliver("""
                {"type":"agentFrame",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "frame":{"jsonrpc":"2.0","id":10,"method":"session/load"}}""");

        await(() -> !controlPlane.agentFrames().isEmpty(), "nothing was answered");
        assertEquals(1, controlPlane.agentFrames().size());
        assertEquals(10, controlPlane.agentFrames().get(0).path("frame").path("id").asInt());
    }

    @Test
    void aJobWithoutAnAgentBehaviourCannotAttachOne() {
        start(worker().onJob(job -> {
            job.running();
            job.attachAgent();
        }));

        deliver(createJob(JOB));

        await(() -> states().contains(JobState.FAILED), "the failure was never reported");
    }

    @Test
    void aDetachedAgentLeavesTheJobRunning() {
        var detached = new CountDownLatch(1);
        start(worker()
                .agent(AgentBehaviour.echoing())
                .onJob(job -> {
                    job.running();
                    job.attachAgent();
                    job.detachAgent("the agent exited");
                    detached.countDown();
                    job.awaitCancellation();
                }));

        deliver(createJob(JOB));

        await(() -> detached.getCount() == 0, "the agent never detached");
        var detach = controlPlane.firstSent("agentDetached");
        assertEquals("the agent exited", detach.path("reason").asText());
        assertEquals(JobState.RUNNING, awaitJob().state());
    }

    // ---------------------------------------------------------------- observing

    @Test
    void anInboundMessageIsRecordedUntilSomethingTakesIt() {
        var worker = start(worker());
        assertTrue(worker.nextInbound(Inbound.Ack.class, WAIT).ok(), "the registration's answer");

        worker.reportPending(2);

        assertTrue(worker.nextInbound(Inbound.Ack.class, WAIT).ok(), "the report's answer");
        assertTrue(worker.inbox().isEmpty());
        assertThrows(NoSuchElementException.class,
                () -> worker.nextInbound(Inbound.Ack.class, Duration.ofMillis(50)));
    }

    /**
     * Every message is answered once and every answer names what it answers, so nothing the control
     * plane sends back goes unclaimed however the traffic interleaves.
     */
    @Test
    void everyAnswerIsMatchedToTheMessageItNames() {
        var worker = start(worker().onJob(JobScript.started().andThen(JobRun::succeeded)));

        deliver(createJob(JOB));
        worker.reportPending(1);

        await(() -> states().contains(JobState.SUCCESS), "the job never finished");
        assertEquals(0, worker.unclaimedReplies());
    }

    @Test
    void anObserverSeesEveryInboundMessageAndAWatcherEveryOutboundOne() {
        var worker = start(worker());
        var inbound = new CopyOnWriteArrayList<String>();
        var outbound = new CopyOnWriteArrayList<String>();

        worker.onInbound(message -> inbound.add(message.getClass().getSimpleName()));
        worker.onSent(message -> outbound.add(message.getClass().getSimpleName()));
        worker.reportInfo(ResourceInfo.of(1, 1, 1));

        await(() -> !inbound.isEmpty(), "the observer saw nothing");
        assertEquals(List.of("Ack"), inbound);
        assertEquals(List.of("UpdateResourceInfo"), outbound);
    }

    // ---------------------------------------------------------------- harness

    private MockWorker.Builder worker() {
        return MockWorker.builder(URI.create("ws://control-plane.test"), "a-token").workerId(WORKER);
    }

    /** Builds the worker over the fake transport and registers it. */
    private MockWorker start(MockWorker.Builder builder) {
        var worker = connect(builder);
        worker.register();
        return worker;
    }

    private MockWorker connect(MockWorker.Builder builder) {
        var worker = new MockWorker(builder, controlPlane);
        controlPlane.deliverTo(worker);
        workers.add(worker);
        return worker;
    }

    /** The last worker started, which is the one a test is about. */
    private MockWorker started() {
        return workers.get(workers.size() - 1);
    }

    /** The jobs of the first worker started, which is the only one most tests start. */
    private List<JobRun> jobs() {
        return workers.get(0).jobs();
    }

    private List<JobRun> awaitJobQuietly() {
        await(() -> !jobs().isEmpty(), "no job ever arrived at the worker");
        return jobs();
    }

    private JobRun awaitJob() {
        return awaitJobQuietly().get(0);
    }

    private List<String> types() {
        return controlPlane.sent().stream()
                .map(message -> message.path("type").asText())
                .collect(Collectors.toList());
    }

    private List<JobState> states() {
        return controlPlane.states();
    }

    private void deliver(String message) {
        controlPlane.receive(message);
    }

    private static String createJob(UUID jobId) {
        return """
                {"type":"createJob",
                 "jobId":"%s",
                 "spec":{"image":"alpine:3.20","description":"","environment":{},"labels":{},
                         "command":[],"volumes":{},"timeout":60,"lock":""},
                 "resourceClass":{"name":"small","numCpus":1,"memCount":512,"diskSize":1024,
                                  "shared":true},
                 "secrets":{"TOKEN":"s3cret"}}""".formatted(jobId);
    }

    private static String createVolume() {
        return """
                {"type":"createVolume",
                 "volumeId":"44444444-4444-4444-4444-444444444444",
                 "projectId":"55555555-5555-5555-5555-555555555555",
                 "name":"shared",
                 "sizeBytes":1024}""";
    }

    private static String reply(boolean ok, String message) {
        return """
                {"type":"ack","ok":%s,"message":"%s"}""".formatted(ok, message);
    }

    private static String presignedUpload(JsonNode request, String url) {
        return """
                {"type":"presignedUpload",
                 "uploadId":"66666666-6666-6666-6666-666666666666",
                 "jobId":"%s","name":"%s","objectKey":"obj","url":"%s","method":"PUT",
                 "expiresAt":"2026-01-01T00:00:00Z","contentLength":%d}"""
                .formatted(request.path("jobId").asText(), request.path("name").asText(), url,
                        request.path("sizeBytes").asLong());
    }

    private static void await(BooleanSupplier settled, String message) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!settled.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(message + ", after " + WAIT);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(message);
            }
        }
    }

    /**
     * The control plane as far as the mock can tell: it records what arrives and answers every
     * message the way the test asked — one reply per message, naming the envelope it answers, which
     * is the rule the protocol states and the one the mock's bookkeeping depends on.
     *
     * <p>{@link #sent()} holds the messages themselves; the envelopes carrying them are this class's
     * own business.
     */
    private static final class FakeControlPlane implements Sink {
        private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
        private final Map<String, Function<JsonNode, String>> answers = new ConcurrentHashMap<>();
        private final AtomicBoolean open = new AtomicBoolean();

        private volatile MockWorker worker;

        void deliverTo(MockWorker worker) {
            this.worker = worker;
            open.set(true);
        }

        /**
         * Answers one message type. Returning null from the answer sends nothing, which is how a
         * test makes the control plane go quiet.
         */
        void answer(String type, Function<JsonNode, String> answer) {
            answers.put(type, answer);
        }

        List<JsonNode> sent() {
            return List.copyOf(sent);
        }

        /** The first message of the given type, which is what most assertions are about. */
        JsonNode firstSent(String type) {
            return sent.stream()
                    .filter(message -> type.equals(message.path("type").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the worker never sent " + type + "; it sent " + sent));
        }

        List<JobState> states() {
            return sent.stream()
                    .filter(message -> "jobStateUpdate".equals(message.path("type").asText()))
                    .map(message -> JobState.valueOf(message.path("state").asText()))
                    .collect(Collectors.toList());
        }

        List<String> stateNames() {
            return states().stream().map(Enum::name).collect(Collectors.toList());
        }

        List<JsonNode> agentFrames() {
            return sent.stream()
                    .filter(message -> "agentFrame".equals(message.path("type").asText()))
                    .collect(Collectors.toList());
        }

        /** Hands the worker a message on the control plane's own initiative. */
        void receive(String message) {
            deliver(envelope(null, message));
        }

        @Override
        public void send(String text) {
            var envelope = tree(text);
            var message = envelope.path("message");
            sent.add(message);
            if (!envelope.path("replyTo").isNull()) {
                // An answer is never answered; this one closed a request the control plane made.
                return;
            }
            var answer = answers.get(message.path("type").asText());
            var reply = answer == null ? reply(true, "") : answer.apply(message);
            if (reply != null) {
                deliver(envelope(UUID.fromString(envelope.path("id").asText()), reply));
            }
        }

        private void deliver(String envelope) {
            var worker = this.worker;
            if (worker == null) {
                throw new IllegalStateException("no worker is connected to this control plane");
            }
            worker.enqueue(envelope);
        }

        /** Wraps one message for the wire, naming what it answers when it answers anything. */
        private static String envelope(UUID replyTo, String message) {
            var node = Wire.mapper().createObjectNode();
            node.put("id", UUID.randomUUID().toString());
            node.put("replyTo", replyTo == null ? null : replyTo.toString());
            node.set("message", tree(message));
            return node.toString();
        }

        private static JsonNode tree(String json) {
            try {
                return Wire.mapper().readTree(json);
            } catch (JsonProcessingException e) {
                throw new AssertionError("not JSON: " + json, e);
            }
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() {
            open.set(false);
        }

        @Override
        public void abort() {
            open.set(false);
        }
    }
}
