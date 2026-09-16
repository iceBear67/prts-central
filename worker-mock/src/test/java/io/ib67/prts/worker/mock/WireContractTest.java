package io.ib67.prts.worker.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.ib67.prts.worker.mock.protocol.Inbound;
import io.ib67.prts.worker.mock.protocol.JobSpec;
import io.ib67.prts.worker.mock.protocol.JobState;
import io.ib67.prts.worker.mock.protocol.Outbound;
import io.ib67.prts.worker.mock.protocol.ResourceInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire format this module speaks.
 *
 * <p>The mock keeps its own model of the protocol rather than sharing the control plane's classes, so
 * nothing but these assertions stands between a careless rename here and a worker that no longer
 * talks to anything. Every message the protocol defines is written out by hand and compared as JSON,
 * which is also what makes this file the place to look up what a message contains.
 */
class WireContractTest {

    private static final UUID WORKER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID JOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REQUEST = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VOLUME = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PROJECT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant EXPIRES = Instant.parse("2026-01-01T00:00:00Z");

    // ---------------------------------------------------------------- what a worker sends

    @Test
    void registeringCarriesTheIdentityTheWorkerAssertsForItself() {
        assertWire(Wire.write(new Outbound.Register(WORKER, "w1", ResourceInfo.of(8, 8192, 102400))), """
                {"type":"register",
                 "workerId":"11111111-1111-1111-1111-111111111111",
                 "name":"w1",
                 "info":{"current":{"numCpus":8,"numMemories":8192,"numDisks":102400},
                         "capacity":{"numCpus":8,"numMemories":8192,"numDisks":102400},
                         "pending":0}}""");
    }

    @Test
    void aResourceReportIsTheSnapshotsPlacementFiltersOn() {
        var info = new ResourceInfo(
                new ResourceInfo.Resources(1, 2, 3), new ResourceInfo.Resources(4, 5, 6), 7);

        assertWire(Wire.write(new Outbound.UpdateResourceInfo(info)), """
                {"type":"updateResourceInfo",
                 "info":{"current":{"numCpus":1,"numMemories":2,"numDisks":3},
                         "capacity":{"numCpus":4,"numMemories":5,"numDisks":6},
                         "pending":7}}""");
    }

    @Test
    void acceptingAJobCarriesTheRequestIdItAnswers() {
        assertWire(Wire.write(new Outbound.JobCreated(REQUEST)),
                """
                        {"type":"jobCreated","requestId":"33333333-3333-3333-3333-333333333333"}""");
    }

    @Test
    void aStateReportCarriesTheJobsIdentityAndItsNewState() {
        assertWire(Wire.write(new Outbound.JobStateUpdate(JOB, JobState.RUNNING)), """
                {"type":"jobStateUpdate",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "state":"RUNNING"}""");
    }

    @Test
    void aLogLineCarriesItsTopicAndWhetherItIsAnError() {
        assertWire(Wire.write(new Outbound.UpdateJobLog(JOB, "stderr", "no space left", true)), """
                {"type":"updateJobLog",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "topic":"stderr",
                 "message":"no space left",
                 "error":true}""");
    }

    @Test
    void anArtifactUploadAsksForSpaceWithTheSizeItIntendsToWrite() {
        assertWire(Wire.write(new Outbound.UploadArtifactRequest(JOB, "report.txt", 12)), """
                {"type":"uploadArtifactRequest",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "name":"report.txt",
                 "sizeBytes":12}""");
    }

    @Test
    void aVolumeAcknowledgmentAnswersTheRequestAndSaysWhetherItWorked() {
        assertWire(Wire.write(new Outbound.VolumeAck(REQUEST, false, "no space left")), """
                {"type":"volumeAck",
                 "requestId":"33333333-3333-3333-3333-333333333333",
                 "ok":false,
                 "message":"no space left"}""");
    }

    @Test
    void aVolumeRefusalThatCarriesNoReasonIsStillAcknowledged() {
        assertWire(Wire.write(new Outbound.VolumeAck(REQUEST, true, "")), """
                {"type":"volumeAck",
                 "requestId":"33333333-3333-3333-3333-333333333333",
                 "ok":true,
                 "message":""}""");
    }

    @Test
    void anAgentAttachCarriesTheVerbatimInitializeResult() {
        var initialize = JsonNodeFactory.instance.objectNode();
        initialize.put("protocolVersion", 1);
        initialize.putObject("agentCapabilities").put("loadSession", false);

        assertWire(Wire.write(new Outbound.AgentAttached(JOB, initialize, "session-1")), """
                {"type":"agentAttached",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "initialize":{"protocolVersion":1,"agentCapabilities":{"loadSession":false}},
                 "sessionId":"session-1"}""");
    }

    @Test
    void anAgentFrameIsRelayedVerbatim() {
        var frame = JsonNodeFactory.instance.objectNode()
                .put("jsonrpc", "2.0")
                .put("id", 7)
                .put("method", "session/cancel");

        assertWire(Wire.write(new Outbound.AgentFrame(JOB, frame)), """
                {"type":"agentFrame",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "frame":{"jsonrpc":"2.0","id":7,"method":"session/cancel"}}""");
    }

    @Test
    void anAgentDetachSaysWhyTheAgentWentAway() {
        assertWire(Wire.write(new Outbound.AgentDetached(JOB, "the agent exited")), """
                {"type":"agentDetached",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "reason":"the agent exited"}""");
    }

    /** A message added to the interface but left out of the assertions above shows up here. */
    @Test
    void everyMessageAWorkerCanSendIsAccountedFor() {
        var covered = Stream.of(
                        new Outbound.Register(WORKER, "w1", ResourceInfo.of(1, 1, 1)),
                        new Outbound.UpdateResourceInfo(ResourceInfo.of(1, 1, 1)),
                        new Outbound.JobCreated(REQUEST),
                        new Outbound.JobStateUpdate(JOB, JobState.RUNNING),
                        new Outbound.UpdateJobLog(JOB, "stdout", "hi", false),
                        new Outbound.UploadArtifactRequest(JOB, "a.txt", 1),
                        new Outbound.VolumeAck(REQUEST, true, ""),
                        new Outbound.AgentAttached(JOB, JsonNodeFactory.instance.objectNode(), "s"),
                        new Outbound.AgentFrame(JOB, JsonNodeFactory.instance.objectNode()),
                        new Outbound.AgentDetached(JOB, "gone"))
                .map(message -> read(Wire.write(message)).path("type").asText())
                .collect(Collectors.toSet());

        assertEquals(Set.of("register", "updateResourceInfo", "jobCreated", "jobStateUpdate",
                "updateJobLog", "uploadArtifactRequest", "volumeAck", "agentAttached", "agentFrame",
                "agentDetached"), covered);
    }

    // ---------------------------------------------------------------- what a worker is sent

    @Test
    void aReplyIsTheResultMessageAndCarriesNoRequestId() {
        var reply = assertInstanceOf(Inbound.Response.class,
                Wire.read("""
                        {"type":"result","ok":true,"message":""}"""));

        assertTrue(reply.ok());
        assertEquals("", reply.message());
    }

    @Test
    void aRefusalCarriesItsReason() {
        var reply = assertInstanceOf(Inbound.Response.class,
                Wire.read("""
                        {"type":"result","ok":false,"message":"not registered"}"""));

        assertEquals("not registered", reply.message());
    }

    @Test
    void aJobArrivesWithItsSpecItsClassAndItsSecrets() {
        var create = assertInstanceOf(Inbound.CreateJob.class, Wire.read("""
                {"type":"createJob",
                 "requestId":"33333333-3333-3333-3333-333333333333",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "spec":{"image":"alpine:3.20",
                         "description":"say hello",
                         "environment":{"GREETING":"hello"},
                         "labels":{"tier":"test"},
                         "command":["sh","-c","echo $GREETING"],
                         "volumes":{"44444444-4444-4444-4444-444444444444":
                                    {"mountPoint":"/data","sizeLimit":1024}},
                         "timeout":60,
                         "lock":"build"},
                 "resourceClass":{"name":"small","numCpus":1,"memCount":512,"diskSize":1024,
                                  "shared":true},
                 "secrets":{"TOKEN":"s3cret"}}"""));

        assertEquals(REQUEST, create.requestId());
        assertEquals(JOB, create.jobId());
        assertEquals("alpine:3.20", create.spec().image());
        assertEquals(Map.of("GREETING", "hello"), create.spec().environment());
        assertEquals(List.of("sh", "-c", "echo $GREETING"), create.spec().command());
        assertEquals("build", create.spec().lock());
        assertEquals(new JobSpec.VolumeSpec("/data", 1024), create.spec().volumes().get(VOLUME));
        assertEquals("small", create.resourceClass().name());
        assertEquals(512, create.resourceClass().memCount());
        assertTrue(create.resourceClass().shared());
        assertEquals(Map.of("TOKEN", "s3cret"), create.secrets());
    }

    /** The spec is written without a description, labels, command or lock when none was given. */
    @Test
    void aBareSpecIsReadAsEmptyRatherThanAbsent() {
        var create = assertInstanceOf(Inbound.CreateJob.class, Wire.read("""
                {"type":"createJob",
                 "requestId":"33333333-3333-3333-3333-333333333333",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "spec":{"image":"alpine:3.20","description":"","environment":{},"labels":{},
                         "command":[],"volumes":{},"timeout":0,"lock":""},
                 "resourceClass":{"name":"small","numCpus":1,"memCount":512,"diskSize":1024,
                                  "shared":true},
                 "secrets":{}}"""));

        assertTrue(create.spec().labels().isEmpty());
        assertTrue(create.spec().volumes().isEmpty());
        assertEquals("", create.spec().description());
        assertTrue(create.secrets().isEmpty());
    }

    @Test
    void aCancellationNamesTheJobToStop() {
        var cancel = assertInstanceOf(Inbound.CancelJob.class, Wire.read("""
                {"type":"cancelJob","jobId":"22222222-2222-2222-2222-222222222222"}"""));

        assertEquals(JOB, cancel.jobId());
    }

    @Test
    void anInterruptionCarriesItsReason() {
        var interrupt = assertInstanceOf(Inbound.InterruptJob.class, Wire.read("""
                {"type":"interruptJob",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "reason":"the project was deleted"}"""));

        assertEquals("the project was deleted", interrupt.reason());
    }

    @Test
    void anAcceptedUploadComesBackAsTheUrlToPutTheBytesTo() {
        var upload = assertInstanceOf(Inbound.PresignedUpload.class, Wire.read("""
                {"type":"presignedUpload",
                 "uploadId":"66666666-6666-6666-6666-666666666666",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "name":"report.txt",
                 "objectKey":"jobs/2/report.txt",
                 "url":"http://storage.example/jobs/2/report.txt",
                 "method":"PUT",
                 "expiresAt":"2026-01-01T00:00:00Z",
                 "contentLength":12}"""));

        assertEquals("PUT", upload.method());
        assertEquals("http://storage.example/jobs/2/report.txt", upload.url());
        assertEquals(EXPIRES, upload.expiresAt());
        assertEquals(12, upload.contentLength());
    }

    @Test
    void aVolumeRequestNamesItsRequestIdSoTheAckCanBeMatched() {
        var create = assertInstanceOf(Inbound.CreateVolume.class, Wire.read("""
                {"type":"createVolume",
                 "requestId":"33333333-3333-3333-3333-333333333333",
                 "volumeId":"44444444-4444-4444-4444-444444444444",
                 "projectId":"55555555-5555-5555-5555-555555555555",
                 "name":"shared",
                 "sizeBytes":1024}"""));

        assertEquals(REQUEST, create.requestId());
        assertEquals(VOLUME, create.volumeId());
        assertEquals(PROJECT, create.projectId());
        assertEquals("shared", create.name());
        assertEquals(1024, create.sizeBytes());
    }

    @Test
    void aVolumeReleaseNamesTheVolumeToDestroy() {
        var delete = assertInstanceOf(Inbound.DeleteVolume.class, Wire.read("""
                {"type":"deleteVolume",
                 "requestId":"33333333-3333-3333-3333-333333333333",
                 "volumeId":"44444444-4444-4444-4444-444444444444"}"""));

        assertEquals(VOLUME, delete.volumeId());
    }

    @Test
    void aFrameForAnAgentArrivesAsItWasWritten() {
        var frame = assertInstanceOf(Inbound.AgentFrame.class, Wire.read("""
                {"type":"agentFrame",
                 "jobId":"22222222-2222-2222-2222-222222222222",
                 "frame":{"jsonrpc":"2.0","id":1,"method":"session/prompt"}}"""));

        assertEquals(JOB, frame.jobId());
        assertEquals("session/prompt", frame.frame().path("method").asText());
    }

    // ---------------------------------------------------------------- living with the control plane

    /**
     * The control plane may add fields at any time. A worker that refused the message would be the
     * thing that breaks, so unknown properties are ignored.
     */
    @Test
    void aFieldThisMockDoesNotKnowAboutIsIgnored() {
        var reply = assertInstanceOf(Inbound.Response.class, Wire.read("""
                {"type":"result","ok":true,"message":"","traceId":"abc"}"""));

        assertTrue(reply.ok());
    }

    @Test
    void aMessageThisMockDoesNotKnowAboutIsRefusedByType() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Wire.read("""
                        {"type":"somethingTheMockHasNeverHeardOf"}"""));

        assertTrue(failure.getMessage().contains("somethingTheMockHasNeverHeardOf"), failure.getMessage());
    }

    // ---------------------------------------------------------------- helpers

    private static void assertWire(String actual, String expected) {
        assertEquals(read(expected), read(actual), actual);
    }

    private static JsonNode read(String json) {
        try {
            return Wire.mapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("not JSON: " + json, e);
        }
    }
}
