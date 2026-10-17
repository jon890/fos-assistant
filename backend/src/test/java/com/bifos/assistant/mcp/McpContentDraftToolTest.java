package com.bifos.assistant.mcp;

import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ContentDraftInputReader;
import com.bifos.assistant.chat.application.ContentDraftService;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.infra.ContentDraftAssetRepository;
import com.bifos.assistant.chat.infra.ContentDraftCreationCallRepository;
import com.bifos.assistant.chat.infra.ContentDraftCreationRequestRepository;
import com.bifos.assistant.chat.infra.ContentDraftRepository;
import com.bifos.assistant.chat.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.dto.McpCallContext;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.mcp.application.McpContentDraftTools;
import com.bifos.assistant.mcp.application.McpCurrentCallerGuard;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 실제 HTTP 인증·서명·원고 TX를 검사한다. Hermes와 외부 서비스는 호출하지 않는다. */
@BackendIntegrationTest
class McpContentDraftToolTest {
    private static final String PROFILE = "mcp-content-draft-test";
    private static final String MARKER = "SYNTHETIC_DRAFT_PRIVATE_3189";
    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AppUserRepository users;
    @Autowired
    AllowedPersonRepository people;
    @Autowired
    AgentRepository agents;
    @Autowired
    AgentExecutionRepository executions;
    @Autowired
    ConversationRepository conversations;
    @Autowired
    AgentTokenService tokens;
    @Autowired
    ContentDraftRepository drafts;
    @Autowired
    ContentDraftRevisionRepository revisions;
    @Autowired
    ContentDraftAssetRepository assets;
    @Autowired
    ContentDraftCreationRequestRepository requests;
    @Autowired
    ContentDraftCreationCallRepository calls;
    @Autowired
    ContentDraftService service;
    @Autowired
    McpCurrentCallerGuard guard;
    @Autowired
    TestClock clock;
    private final HttpClient client = HttpClient.newHttpClient();
    private AppUser owner;
    private Conversation conversation;
    private AgentExecution origin;
    private String root;
    private String token;


    @BeforeEach
    void prepare() {
        clock.set(Instant.parse("2026-10-10T01:00:00Z"));
        owner = users.save(AppUser.of(UUID.randomUUID() + "@example.test", "원고 주인", 1L, UserRole.MEMBER));
        people.save(AllowedPerson.of(owner.email(), "원고 주인", PROFILE));
        var agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(owner.id(), "원고", agent.id(), clock.instant()));
        token = tokens.issue(PROFILE, "원고 시험").rawToken();
        root = McpCallSigner.newRoot();
        origin = McpCallSigner.running(executions, agents, owner.id(), conversation.id(), PROFILE, root);
    }


    @AfterEach
    void cleanup() {
        calls.deleteAll();
        requests.deleteAll();
        assets.deleteAll();
        revisions.deleteAll();
        drafts.deleteAll();
        jdbc.update("delete from allowed_person where hermes_profile=?", PROFILE);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("update agent set enabled=true,deleted_at=null,hermes_profile=? where id=?", PROFILE, origin.agentId());
    }


    @Test
    @DisplayName("실제 commit 뒤 프록시 응답 단절과 두 재호출은 같은 원고이며 CAS 뒤 현재 판을 읽는다")
    void retriesAfterCommittedHttpResponseIsDropped() throws Exception {
        var args = creating();
        var committed = new CountDownLatch(1);
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/mcp", exchange -> {
            try {
                send(exchange.getRequestBody().readAllBytes());
                committed.countDown();
            } catch (Exception ex) {
                throw new IOException(ex);
            } finally {
                // upstream 응답을 받았지만 downstream HTTP 응답은 보내지 않는다.
                exchange.close();
            }
        });
        proxy.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort() + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(rpc(McpContentDraftTools.WRITE, args, root, "call_1"))).build();
            assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
            assertThat(committed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            proxy.stop(0);
        }
        var first = data(call(McpContentDraftTools.WRITE, args, root, "call_1"));
        var id = first.path("draft").path("draftId").stringValue();
        assertThat(data(call(McpContentDraftTools.WRITE, args, root, "call_2")).path("draft").path("draftId").stringValue()).isEqualTo(id);
        var update = json.createObjectNode().put("draftId", id).put("expectedRevision", 1);
        update.set("draft", body("현재"));
        assertThat(data(call(McpContentDraftTools.WRITE, update, root, "call_3")).path("draft").path("revision").longValue()).isEqualTo(2);
        var retry = data(call(McpContentDraftTools.WRITE, args, root, "call_4"));
        assertThat(retry.path("draft").path("title").stringValue()).isEqualTo("현재");
        assertThat(retry.path("draft").path("revision").longValue()).isEqualTo(2);
        assertThat(drafts.count()).isEqualTo(1);
        assertThat(revisions.count()).isEqualTo(2);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(3);
    }


    @Test
    @DisplayName("동시 HTTP 같은 서명 전송은 한 원고와 한 생성 및 전송 대응만 저장한다")
    void serializesTwoHttpRequestsAtDatabaseBoundary() throws Exception {
        var args = creating();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = List.of(1, 2).stream().map(index -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return data(call(McpContentDraftTools.WRITE, args, root, "call_1")).path("draft").path("draftId").stringValue();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(pending.get(0).get(10, TimeUnit.SECONDS)).isEqualTo(pending.get(1).get(10, TimeUnit.SECONDS));
        }
        assertThat(drafts.count()).isEqualTo(1);
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("최상위 null unknown requestId 교체와 revision coercion은 안전한 -32602이고 저장하지 않는다")
    void rejectsStrictArgumentShapesBeforeServiceWrites() throws Exception {
        for (String key : List.of("requestId", "draftId", "expectedRevision", "profile", "conversationId", "userId")) {
            var args = creating();
            args.putNull(key);
            var response = call(McpContentDraftTools.WRITE, args, root, "call_1");
            assertThat(response.path("error").path("code").intValue()).isEqualTo(-32602);
            assertThat(response.toString()).doesNotContain(MARKER);
        }
        for (JsonNode revision : List.of(json.readTree("\"1\""), json.readTree("1.0"), json.readTree("9007199254740991"))) {
            var args = json.createObjectNode().put("draftId", UUID.randomUUID().toString());
            args.set("expectedRevision", revision);
            args.set("draft", body(MARKER));
            assertThat(call(McpContentDraftTools.WRITE, args, root, "call_1").path("error").path("code").intValue()).isEqualTo(-32602);
        }
        assertThat(drafts.count()).isZero();
        assertThat(requests.count()).isZero();
    }


    @Test
    @DisplayName("완료 origin의 등록 child는 읽고 origin 취소와 현재 agent 철회는 본문과 번호를 차단한다")
    void preservesCompletedChildAndCancellationContract() throws Exception {
        var args = creating();
        var id = data(call(McpContentDraftTools.WRITE, args, root, "call_1")).path("draft").path("draftId").stringValue();
        var child = "child-" + UUID.randomUUID();
        var registered = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/hermes/session-bindings/subagent"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(McpCallSigner.subagentBody(token, root, root, child)))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(registered.statusCode()).isEqualTo(201);
        jdbc.update("update agent_execution set status='SUCCEEDED' where id=?", origin.id());
        var read = json.createObjectNode().put("draftId", id);
        assertThat(data(call(McpContentDraftTools.READ, read, child, "call_2")).path("draft").path("draftId").stringValue()).isEqualTo(id);
        jdbc.update("update agent_execution set status='CANCELLED' where id=?", origin.id());
        assertContext(call(McpContentDraftTools.READ, read, child, "call_3"));
        assertContext(call(McpContentDraftTools.WRITE, args, child, "call_4"));
        assertThat(drafts.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("서비스 commit 직후 agent 권한을 잃으면 성공과 typed 오류의 본문과 번호를 차단한다")
    void guardsResponsesAfterCommittedWorkAndTypedErrors() {
        var user = new CurrentUser(owner.id(), owner.email(), "원고 주인", 1L, UserRole.MEMBER);
        var caller = new McpCaller(user, origin, new McpCallContext(root, root, "call_1"));
        var fake = mock(ContentDraftService.class);
        when(fake.createIdempotent(any(), any(), any(), any())).thenAnswer(invocation -> {
            var result = service.createIdempotent(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
            jdbc.update("update agent set enabled=false where id=?", origin.agentId());
            return result;
        });
        var tools = new McpContentDraftTools(fake, new ContentDraftInputReader(), guard, json);
        var result = json.valueToTree(tools.write(caller, UUID.randomUUID(), null, null, body(MARKER)));
        assertThat(result.toString()).contains("호출 맥락을 확인할 수 없습니다").doesNotContain(MARKER, "currentRevision");
        assertThat(drafts.count()).isEqualTo(1);
        jdbc.update("update agent set enabled=true where id=?", origin.agentId());
        when(fake.read(any(), any(), any(), any())).thenAnswer(invocation -> {
            jdbc.update("update agent set enabled=false where id=?", origin.agentId());
            throw new ContentDraftException(
                ErrorCode.CONTENT_DRAFT_CONFLICT, 17L);
        });
        var denied = json.valueToTree(tools.read(caller, UUID.randomUUID(), null));
        assertThat(denied.toString()).contains("호출 맥락을 확인할 수 없습니다").doesNotContain("currentRevision", "17");
        tools.write(caller, UUID.randomUUID(), null, null, body(MARKER));
        Mockito.verify(fake, Mockito.times(1)).createIdempotent(any(), any(), any(), any());
    }


    @Test
    @DisplayName("MCP 생성과 수정은 같은 사진의 반복을 고정 코드와 안내로 거절하고 서로 다른 사진은 중복 검사를 지난다")
    void rejectsDuplicatePhotoOnMcpCreateAndRevise() throws Exception {
        var repeated = photos(MARKER, "7", "7");
        var create = creating();
        create.set("draft", repeated);
        assertDuplicate(call(McpContentDraftTools.WRITE, create, root, "call_1"));
        assertThat(drafts.count()).isZero();
        assertThat(requests.count()).isZero();

        var id = data(call(McpContentDraftTools.WRITE, creating(), root, "call_2")).path("draft").path("draftId").stringValue();
        var update = json.createObjectNode().put("draftId", id).put("expectedRevision", 1);
        update.set("draft", repeated);
        assertDuplicate(call(McpContentDraftTools.WRITE, update, root, "call_3"));
        assertThat(revisions.count()).isEqualTo(1);

        // 없는 첨부라 저장되지는 않지만 중복 검사가 아닌 참조 검사에서 멈춘다.
        update.set("draft", photos(MARKER, "7", "8"));
        var distinct = call(McpContentDraftTools.WRITE, update, root, "call_4");
        assertThat(distinct.path("result").path("isError").booleanValue()).isTrue();
        assertThat(json.readTree(distinct.path("result").path("content").get(0).path("text").stringValue()).path("code").stringValue())
            .isEqualTo("ATTACHMENT_GONE");
    }


    private ObjectNode photos(String title, String... assetIds) {
        var body = body(title);
        for (String assetId : assetIds) {
            ((ArrayNode)body.get("blocks")).addObject().put("type", "image").put("assetId", assetId);
        }
        return body;
    }


    private void assertDuplicate(JsonNode response) {
        assertThat(response.path("result").path("isError").booleanValue()).as(response.toString()).isTrue();
        var failure = json.readTree(response.path("result").path("content").get(0).path("text").stringValue());
        assertThat(failure.path("code").stringValue()).isEqualTo("CONTENT_DRAFT_DUPLICATE_PHOTO");
        assertThat(failure.path("message").stringValue()).isEqualTo(ContentDraftInputReader.DUPLICATE_PHOTO_MESSAGE);
        assertThat(response.toString()).doesNotContain(MARKER);
    }


    private ObjectNode body(String title) {
        var body = json.createObjectNode().put("title", title).put("category", "분류");
        body.putArray("tags");
        body.putArray("blocks");
        return body;
    }


    private ObjectNode creating() {
        var args = json.createObjectNode().put("requestId", UUID.randomUUID().toString());
        args.set("draft", body(MARKER));
        return args;
    }


    private JsonNode call(String tool, ObjectNode args, String session, String callId) throws Exception {
        return json.readTree(send(rpc(tool, args, session, callId).getBytes(StandardCharsets.UTF_8)).body());
    }


    private String rpc(String tool, ObjectNode args, String session, String callId) {
        var signed = args.deepCopy();
        signed.set("_fos_ctx", McpCallSigner.context(token, tool, root, session, callId));
        var rpc = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        rpc.putObject("params").put("name", tool).set("arguments", signed);
        return json.writeValueAsString(rpc);
    }


    private HttpResponse<String> send(byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }


    private JsonNode data(JsonNode response) {
        assertThat(response.path("result").path("isError").booleanValue()).as(response.toString()).isFalse();
        String text = response.path("result").path("content").get(0).path("text").stringValue();
        String begin = "\n<external-data>\n";
        return json.readTree(text.substring(text.indexOf(begin) + begin.length(), text.lastIndexOf("</external-data>")));
    }


    private static void assertContext(JsonNode response) {
        assertThat(response.toString()).contains("호출 맥락을 확인할 수 없습니다").doesNotContain(MARKER, "currentRevision");
        assertThat(response.path("result").path("isError").booleanValue()).isTrue();
    }
}
