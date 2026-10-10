package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.MediaObservationService;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.MediaObservationBodies;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
import com.bifos.assistant.chat.infra.MediaObservationRequestRepository;
import com.bifos.assistant.crypto.domain.KeyEncryptionKeys;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpMediaObservationTools;
import com.bifos.assistant.mcp.presentation.McpDtos.MediaObservationRecordArguments;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 필터·Jackson·저장 서비스를 거치는 HTTP 관찰 계약이다. 합성 사진만 쓴다. */
@BackendIntegrationTest
class McpMediaObservationToolTest {
    private static final String PROFILE = "mcp-media-observation-test";
    private static final String SECRET = "SYNTHETIC_OCR_PRIVATE_9163";
    private static final byte[] IMAGE = {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3};

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    AllowedPersonRepository people;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentService agentService;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    AttachmentService attachmentService;

    @Autowired
    AttachmentProperties properties;

    @Autowired
    MediaObservationService service;

    @Autowired
    MediaObservationRepository observations;

    @Autowired
    MediaObservationRequestRepository requests;

    @Autowired
    MediaObservationBodies bodies;

    @Autowired
    TextCipher cipher;

    @Autowired
    KeyEncryptionKeys keks;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    TestClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Path> directories = new ArrayList<>();
    private AppUser user;
    private Agent agent;
    private Conversation conversation;
    private ChatAttachment photo;
    private AgentExecution origin;
    private String token;
    private String root;

    @BeforeEach
    void prepare() {
        clock.set(Instant.parse("2026-10-10T01:00:00Z"));
        requests.deleteAll();
        observations.deleteAll();
        attachments.deleteAll();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("delete from allowed_person where hermes_profile=?", PROFILE);
        user = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "주인", 1L, UserRole.MEMBER, clock.instant()));
        people.save(AllowedPerson.of(user.email(), "주인", PROFILE, clock.instant()));
        agent = McpCallSigner.agentFor(agents, PROFILE);
        jdbc.update(
                "update agent set enabled=true, deleted_at=null, visibility='GROUP', owner_user_id=null, hermes_profile=? where id=?",
                PROFILE,
                agent.id());
        conversation = conversations.save(Conversation.startedBy(user.id(), "사진 관찰", agent.id(), clock.instant()));
        photo = photo();
        token = tokens.issue(PROFILE, "관찰 시험").rawToken();
        root = McpCallSigner.newRoot();
        origin = McpCallSigner.running(executions, agents, user.id(), conversation.id(), PROFILE, root);
        jdbc.update(
                "update agent_execution set provider='requested-provider', model='requested-model' where id=?",
                origin.id());
    }

    @AfterEach
    void cleanup() throws Exception {
        requests.deleteAll();
        observations.deleteAll();
        attachments.deleteAll();
        jdbc.update("delete from proactive_check where user_id=?", user.id());
        jdbc.update("delete from allowed_person where hermes_profile=?", PROFILE);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update(
                "update agent set enabled=true, deleted_at=null, visibility='GROUP', owner_user_id=null, hermes_profile=? where id=?",
                PROFILE,
                agent.id());
        for (Path directory : directories) {
            if (Files.exists(directory)) {
                try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("도구 이름 순서와 중첩 schema는 기존 도구를 보존한다")
    void exposesSchemasWithoutRemovingOrDuplicatingExistingTools() throws Exception {
        var list = json.readTree(send("/mcp", token, "{\"id\":1,\"method\":\"tools/list\"}", false)
                        .body())
                .path("result")
                .path("tools");
        assertThat(list)
                .extracting(item -> item.path("name").asString())
                .doesNotHaveDuplicates()
                .containsSubsequence(
                        "memory_read",
                        "artifact_write",
                        "agent_list",
                        "agent_delegate",
                        "agent_status",
                        "agent_stop",
                        "follow_up_propose",
                        McpMediaObservationTools.LIST,
                        McpMediaObservationTools.RECORD,
                        "memory_remember");
        for (JsonNode tool : list) {
            if (McpMediaObservationTools.RECORD.equals(tool.path("name").asString())) {
                var schema = tool.path("inputSchema");
                assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
                assertThat(schema.path("required"))
                        .extracting(JsonNode::asString)
                        .containsExactlyInAnyOrder("assetId", "expectedRevision", "requestId", "observation");
                assertThat(schema.path("properties")
                                .path("observation")
                                .path("additionalProperties")
                                .asBoolean())
                        .isFalse();
                assertThat(schema.path("properties")
                                .path("observation")
                                .path("properties")
                                .has("evidence"))
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("서명한 HTTP 관찰 저장 재시도와 뒤 실행 조회는 UNKNOWN 출처를 보존한다")
    void roundTripsAndCreatesNewUnknownRevisionForAnotherExecution() throws Exception {
        assertThat(data(call(McpMediaObservationTools.LIST, json.createObjectNode()))
                        .path("items")
                        .get(0)
                        .path("revision")
                        .asLong())
                .isZero();
        UUID id = UUID.randomUUID();
        var args = recording(photo.id(), 0, id, "첫 관찰");
        var first = data(call(McpMediaObservationTools.RECORD, args));
        assertThat(first.path("sourceAssurance").asString()).isEqualTo("MODEL_UNVERIFIED");
        assertThat(first.path("provenance").path("provider").asString()).isEqualTo("UNKNOWN");
        assertThat(first.path("provenance").path("model").asString()).isEqualTo("UNKNOWN");
        assertThat(first.path("provenance").path("providerVersion").isNull()).isTrue();
        assertThat(first.path("provenance").path("modelVersion").isNull()).isTrue();
        clock.advance(Duration.ofSeconds(5));
        assertThat(data(call(McpMediaObservationTools.RECORD, args))).isEqualTo(first);
        assertThat(call(McpMediaObservationTools.RECORD, recording(photo.id(), 0, id, "다른 본문"))
                        .toString())
                .contains("MEDIA_OBSERVATION_CONFLICT", "currentRevision")
                .doesNotContain("첫 관찰");
        jdbc.update("update agent_execution set status='SUCCEEDED' where id=?", origin.id());
        root = McpCallSigner.newRoot();
        var secondOrigin = McpCallSigner.running(executions, agents, user.id(), conversation.id(), PROFILE, root);
        var second = data(call(McpMediaObservationTools.RECORD, recording(photo.id(), 1, UUID.randomUUID(), "둘째 관찰")));
        assertThat(second.path("revision").asLong()).isEqualTo(2);
        assertThat(second.path("observation").path("summary").asString()).isEqualTo("둘째 관찰");
        assertThat(second.path("provenance").path("executionId").asLong()).isEqualTo(secondOrigin.id());
        assertThat(data(call(McpMediaObservationTools.LIST, json.createObjectNode()))
                        .path("items")
                        .get(0))
                .isEqualTo(second);
        assertThat(observations.count()).isEqualTo(2);
        assertThat(requests.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("파싱 실패는 인증된 HTTP에서 고정 -32700이고 인증 오류는 401과 403이다")
    void parseAndAuthenticationFailuresNeverExposeBodyOrCauseOrWrite() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (String method : List.of("tools/call", "initialize", "tools/list")) {
                for (String tool : List.of(McpMediaObservationTools.RECORD, "memory_remember", "agent_list")) {
                    for (String end : List.of("", " invalid}")) {
                        String body = "{\"id\":37,\"method\":\"" + method + "\",\"params\":{\"name\":\"" + tool
                                + "\",\"arguments\":{\"observation\":\"" + SECRET + "\"" + end;
                        var response = send("/mcp", token, body, false);
                        assertThat(response.statusCode()).isEqualTo(200);
                        var parsed = json.readTree(response.body());
                        assertThat(parsed.path("id").isNull()).isTrue();
                        assertThat(parsed.path("error").path("code").asInt()).isEqualTo(-32700);
                        assertThat(parsed.path("error").path("message").asString())
                                .isEqualTo("Parse error");
                        assertThat(response.body()).doesNotContain(SECRET);
                    }
                }
            }
            String malformed = "{\"summary\":\"" + SECRET + "\", ";
            assertThat(send("/mcp", null, malformed, false).statusCode()).isEqualTo(401);
            assertThat(send("/mcp", "invalid-token", malformed, false).statusCode())
                    .isEqualTo(401);
            assertThat(send("/mcp", token, malformed, true).statusCode()).isEqualTo(403);
            tokens.revokeAllFor(PROFILE);
            assertThat(send("/mcp", token, malformed, false).statusCode()).isEqualTo(401);
            assertThat(new MediaObservationRecordArguments(
                                    photo.id().toString(),
                                    0,
                                    UUID.randomUUID(),
                                    json.createObjectNode().put("summary", SECRET))
                            .toString())
                    .doesNotContain(SECRET);
            assertThat(observations.count()).isZero();
            assertThat(requests.count()).isZero();
            for (ILoggingEvent log : logs.list) {
                assertThat(log.getFormattedMessage()).doesNotContain(SECRET, "unexpected error");
                for (IThrowableProxy cause = log.getThrowableProxy(); cause != null; cause = cause.getCause()) {
                    assertThat(cause.getMessage()).doesNotContain(SECRET);
                }
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    @DisplayName("서명 뒤 유효 JSON의 잘못된 인자는 -32602이고 모델 출처 주입은 거절된다")
    void rejectsStrictArgumentsAndInjectedProvenance() throws Exception {
        var valid = recording(photo.id(), 0, UUID.randomUUID(), SECRET);
        for (String field : List.of("provider", "executionId", "provenance", "evidence")) {
            var args = valid.deepCopy();
            ((ObjectNode) args.path("observation")).put(field, SECRET);
            invalidArguments(call(McpMediaObservationTools.RECORD, args));
        }
        for (JsonNode invalid : List.of(
                json.readTree("1.0"),
                json.readTree("\"0\""),
                json.readTree("null"),
                json.readTree("9223372036854775808"))) {
            var args = valid.deepCopy();
            args.set("expectedRevision", invalid);
            invalidArguments(call(McpMediaObservationTools.RECORD, args));
        }
        var args = valid.deepCopy();
        args.put("requestId", "1-1-1-1-1");
        invalidArguments(call(McpMediaObservationTools.RECORD, args));
        args = valid.deepCopy();
        args.put("assetId", "9223372036854775808");
        invalidArguments(call(McpMediaObservationTools.RECORD, args));
        args = valid.deepCopy();
        ((ObjectNode) args.path("observation"))
                .putArray("claims")
                .addObject()
                .put("kind", "USER")
                .put("text", SECRET)
                .put("confidence", "CONFIRMED")
                .putArray("evidence")
                .add("사용자 선언");
        invalidArguments(call(McpMediaObservationTools.RECORD, args));
        invalidArguments(
                call(McpMediaObservationTools.LIST, json.createObjectNode().put("limit", 30.0)));
        invalidArguments(
                call(McpMediaObservationTools.LIST, json.createObjectNode().put("conversationId", conversation.id())));
        var optionalNull = json.createObjectNode().putNull("limit").putNull("afterAssetId");
        assertThat(call(McpMediaObservationTools.LIST, optionalNull)
                        .path("result")
                        .path("isError")
                        .asBoolean())
                .isFalse();
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("서명 맥락과 현재 허용 사용자 에이전트 profile 권한은 같은 오류로 차단한다")
    void rejectsMissingTamperedSignatureAndCurrentRevocations() throws Exception {
        var args = json.createObjectNode();
        var request = rpc(McpMediaObservationTools.LIST, args);
        assertInvalid(json.readTree(
                send("/mcp", token, json.writeValueAsString(request), false).body()));
        args.set("_fos_ctx", McpCallSigner.context(token, McpMediaObservationTools.RECORD, root));
        assertInvalid(json.readTree(
                send("/mcp", token, json.writeValueAsString(rpc(McpMediaObservationTools.LIST, args)), false)
                        .body()));
        for (String change : List.of(
                "enabled=false",
                "deleted_at=CURRENT_TIMESTAMP",
                "hermes_profile='different-profile'",
                "visibility='PRIVATE',owner_user_id=999999")) {
            jdbc.update("update agent set " + change + " where id=?", agent.id());
            assertInvalid(call(McpMediaObservationTools.LIST, json.createObjectNode()));
            assertInvalid(call(McpMediaObservationTools.RECORD, recording(photo.id(), 0, UUID.randomUUID(), SECRET)));
            jdbc.update(
                    "update agent set enabled=true,deleted_at=null,hermes_profile=?,visibility='GROUP',owner_user_id=null where id=?",
                    PROFILE,
                    agent.id());
        }
        jdbc.update("update allowed_person set enabled=false where email=?", user.email());
        assertInvalid(call(McpMediaObservationTools.LIST, json.createObjectNode()));
        assertInvalid(call(McpMediaObservationTools.RECORD, recording(photo.id(), 0, UUID.randomUUID(), SECRET)));
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("등록된 native 자식은 끝난 부모 출처를 유지하고 취소와 등록 삭제는 거절한다")
    void preservesFinishedNativeOriginWithoutPretendingRequestedModelIsActual() throws Exception {
        String child = "native-" + UUID.randomUUID();
        var args = recording(photo.id(), 0, UUID.randomUUID(), "native 관찰");
        assertInvalid(callAt(McpMediaObservationTools.RECORD, args, child));
        var registered = send(
                "/internal/hermes/session-bindings/subagent",
                token,
                json.writeValueAsString(McpCallSigner.subagentBody(token, root, root, child)),
                false);
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        jdbc.update("update agent_execution set status='SUCCEEDED' where id=?", origin.id());
        var result = data(callAt(McpMediaObservationTools.RECORD, args, child));
        assertThat(result.path("provenance").path("executionId").asLong()).isEqualTo(origin.id());
        assertThat(result.path("provenance").path("model").asString()).isEqualTo("UNKNOWN");
        assertThat(result.path("sourceAssurance").asString()).isEqualTo("MODEL_UNVERIFIED");
        jdbc.update("update agent_execution set status='FAILED' where id=?", origin.id());
        assertThat(data(callAt(McpMediaObservationTools.LIST, json.createObjectNode(), child))
                        .path("items")
                        .get(0))
                .isEqualTo(result);
        jdbc.update("update agent_execution set status='CANCELLED' where id=?", origin.id());
        assertInvalid(callAt(McpMediaObservationTools.LIST, json.createObjectNode(), child));
        jdbc.update("update agent_execution set status='SUCCEEDED' where id=?", origin.id());
        jdbc.update("delete from hermes_session_binding where profile_name=? and session_id=?", PROFILE, child);
        assertInvalid(callAt(McpMediaObservationTools.LIST, json.createObjectNode(), child));
        assertThat(observations.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("USER 정정 이후 MODEL은 CAS와 UUID를 바꿔도 정정을 대체하지 못한다")
    void respectsUserProtectionAndAttachmentBarriers() throws Exception {
        var source = new ObservationProvenance(
                ObservationProvenanceKind.USER_CORRECTION,
                null,
                null,
                null,
                null,
                null,
                1,
                "media-observation-v1",
                null);
        var input = new MediaObservationInput(
                ObservationStatus.SUCCEEDED,
                "사용자 정정",
                List.of(),
                List.of(),
                new MediaObservationInput.Coverage("ORIGINAL", null, null),
                new MediaObservationInput.Evidence(source.kind(), "USER_CORRECTION"),
                null);
        service.record(current(), conversation.id(), photo.id(), 0, UUID.randomUUID(), input, source);
        var denied = call(McpMediaObservationTools.RECORD, recording(photo.id(), 1, UUID.randomUUID(), SECRET));
        assertThat(denied.toString())
                .contains("MEDIA_OBSERVATION_CONFLICT", "currentRevision")
                .doesNotContain(SECRET, "사용자 정정");
        attachmentService.deleteByUser(current(), conversation.id(), photo.id());
        assertThat(call(McpMediaObservationTools.RECORD, recording(photo.id(), 1, UUID.randomUUID(), SECRET))
                        .toString())
                .contains("ATTACHMENT_GONE");
        assertThat(data(call(McpMediaObservationTools.LIST, json.createObjectNode()))
                        .path("items")
                        .get(0)
                        .path("sourceAssurance")
                        .isNull())
                .isTrue();
    }

    @Test
    @DisplayName("실제 HTTP에서 권한 snapshot 뒤 철회와 쓰기가 경합하면 저장은 남고 응답 본문은 차단한다")
    void keepsCommittedRevisionButWithholdsBodyDuringRevocationRace() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch revoked = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
                    Object snapshot = invocation.callRealMethod();
                    if (reads.incrementAndGet() == 1) {
                        checked.countDown();
                        assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    return snapshot;
                })
                .when(agentService)
                .findById(eq(agent.id()));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(
                    () -> call(McpMediaObservationTools.RECORD, recording(photo.id(), 0, UUID.randomUUID(), SECRET)));
            try {
                assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
                jdbc.update("update agent set enabled=false where id=?", agent.id());
                revoked.countDown();
                var result = pending.get(10, TimeUnit.SECONDS);
                assertInvalid(result);
                assertThat(result.toString()).doesNotContain(SECRET);
                assertThat(observations.count()).isEqualTo(1);
                assertThat(requests.count()).isEqualTo(1);
                assertThat(service.list(current(), conversation.id(), null, 30)
                                .getFirst()
                                .observation()
                                .summary())
                        .isEqualTo(SECRET);
            } finally {
                revoked.countDown();
            }
        }
    }

    @Test
    @DisplayName("본문이 없는 관찰은 null assurance이고 stale 본문은 kind의 assurance를 유지한다")
    void distinguishesUnavailableAndStaleBodiesForBothKinds() throws Exception {
        for (boolean userSource : new boolean[] {false, true}) {
            requests.deleteAll();
            observations.deleteAll();
            Files.write(file(photo), IMAGE);
            var kind = userSource ? ObservationProvenanceKind.USER_CORRECTION : ObservationProvenanceKind.MODEL_RESULT;
            var source = new ObservationProvenance(
                    kind,
                    userSource ? null : origin.id(),
                    userSource ? null : "UNKNOWN",
                    null,
                    userSource ? null : "UNKNOWN",
                    null,
                    1,
                    "media-observation-v1",
                    null);
            var input = new MediaObservationInput(
                    ObservationStatus.PROCESSING,
                    "합성 관찰",
                    List.of(),
                    List.of(),
                    new MediaObservationInput.Coverage("ORIGINAL", null, null),
                    new MediaObservationInput.Evidence(
                            kind, userSource ? "USER_CORRECTION" : origin.id().toString()),
                    null);
            service.record(current(), conversation.id(), photo.id(), 0, UUID.randomUUID(), input, source);
            clock.advance(Duration.ofMinutes(15));
            var stale = data(call(McpMediaObservationTools.LIST, json.createObjectNode()))
                    .path("items")
                    .get(0);
            assertThat(stale.path("errorCode").asString()).isEqualTo("ANALYSIS_STALE");
            assertThat(stale.path("sourceAssurance").asString())
                    .isEqualTo(userSource ? "USER_CORRECTION" : "MODEL_UNVERIFIED");
            var row = observations.findAll().getFirst();
            for (String damaged : List.of("{}", "{\"status\":\"SUCCEEDED\"}")) {
                var sealed = cipher.seal(
                                user.id(),
                                "media_observation:" + row.id() + ":conversation:" + conversation.id() + ":user:"
                                        + user.id(),
                                damaged)
                        .orElseThrow();
                jdbc.update(
                        "update media_observation set body=?,body_key_id=? where id=?",
                        sealed.content(),
                        sealed.keyId(),
                        row.id());
                unavailable(data(call(McpMediaObservationTools.LIST, json.createObjectNode()))
                        .path("items")
                        .get(0));
            }
            jdbc.update("update media_observation set body=? where id=?", "v1.corrupted", row.id());
            unavailable(data(call(McpMediaObservationTools.LIST, json.createObjectNode()))
                    .path("items")
                    .get(0));
            bodies.seal(row, json.writeValueAsString(input));
            observations.saveAndFlush(row);
            byte[] changed = IMAGE.clone();
            changed[changed.length - 1]++;
            Files.write(file(photo), changed);
            unavailable(data(call(McpMediaObservationTools.LIST, json.createObjectNode()))
                    .path("items")
                    .get(0));
        }
    }

    @Test
    @DisplayName("먼저 살펴보기는 list만 허용하고 암호화 실패는 공개 코드만 반환한다")
    void checkTreeAllowsOnlyListAndEncryptionFailureExposesOnlyCode() throws Exception {
        Object active = ReflectionTestUtils.getField(keks, "activeKeyId");
        try {
            ReflectionTestUtils.setField(keks, "activeKeyId", "");
            assertThat(call(McpMediaObservationTools.RECORD, recording(photo.id(), 0, UUID.randomUUID(), SECRET))
                            .toString())
                    .contains("MEDIA_ENCRYPTION_UNAVAILABLE")
                    .doesNotContain(SECRET);
        } finally {
            ReflectionTestUtils.setField(keks, "activeKeyId", active);
        }
        var check = ProactiveCheck.started(
                user.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, false, clock.instant());
        check.attachRoot(origin.id(), root);
        checks.saveAndFlush(check);
        assertThat(call(McpMediaObservationTools.LIST, json.createObjectNode())
                        .path("result")
                        .path("isError")
                        .asBoolean())
                .isFalse();
        assertThat(call(McpMediaObservationTools.RECORD, recording(photo.id(), 0, UUID.randomUUID(), SECRET))
                        .toString())
                .contains("먼저 살펴보기에서는 쓸 수 없는 도구");
        assertThat(observations.count()).isZero();
    }

    private ObjectNode recording(Long asset, long revision, UUID id, String summary) {
        var args = json.createObjectNode()
                .put("assetId", asset.toString())
                .put("expectedRevision", revision)
                .put("requestId", id.toString());
        args.putObject("observation")
                .put("status", "SUCCEEDED")
                .put("summary", summary)
                .putObject("coverage")
                .put("mode", "ORIGINAL");
        return args;
    }

    private JsonNode call(String tool, ObjectNode args) throws Exception {
        return callAt(tool, args, root);
    }

    private JsonNode callAt(String tool, ObjectNode args, String session) throws Exception {
        var signed = args.deepCopy();
        signed.set("_fos_ctx", McpCallSigner.context(token, tool, root, session, "call_" + UUID.randomUUID()));
        var response = send("/mcp", token, json.writeValueAsString(rpc(tool, signed)), false);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private ObjectNode rpc(String tool, ObjectNode args) {
        var rpc = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        rpc.putObject("params").put("name", tool).set("arguments", args);
        return rpc;
    }

    private JsonNode data(JsonNode response) {
        assertThat(response.path("result").path("isError").asBoolean())
                .as(response.toString())
                .isFalse();
        String text =
                response.path("result").path("content").get(0).path("text").asString();
        assertThat(text).contains("<external-data>", "</external-data>");
        return json.readTree(text.substring(
                text.indexOf("\n<external-data>\n") + "\n<external-data>\n".length(),
                text.lastIndexOf("</external-data>")));
    }

    private static void assertInvalid(JsonNode result) {
        assertThat(result.path("result").path("isError").asBoolean()).isTrue();
        assertThat(result.toString()).contains("호출 맥락을 확인할 수 없습니다");
    }

    private static void invalidArguments(JsonNode result) {
        assertThat(result.path("error").path("code").asInt())
                .as(result.toString())
                .isEqualTo(-32602);
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    private static void unavailable(JsonNode result) {
        assertThat(result.path("observation").isNull()).isTrue();
        assertThat(result.path("sourceAssurance").isNull()).isTrue();
        assertThat(result.path("provenance").isObject()).isTrue();
        assertThat(result.path("errorCode").asString()).isEqualTo("CONTENT_UNAVAILABLE");
    }

    private ChatAttachment photo() {
        directories.add(Path.of(properties.root(), "users", AttachmentStore.userDirectoryKey(user.id())));
        var row = attachmentService.upload(
                current(),
                conversation.id(),
                "a.gif",
                "image/gif",
                IMAGE.length,
                () -> new ByteArrayInputStream(IMAGE));
        attachmentService.attach(100L + row.id(), conversation.id(), List.of(row.id()));
        return attachments.findById(row.id()).orElseThrow();
    }

    private Path file(ChatAttachment row) {
        return Path.of(
                properties.root(),
                "users",
                AttachmentStore.userDirectoryKey(user.id()),
                conversation.id().toString(),
                row.storedName());
    }

    private CurrentUser current() {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private HttpResponse<String> send(String route, String bearer, String body, boolean originHeader) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (originHeader) {
            request.header("Origin", "https://example.test");
        }
        return client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
