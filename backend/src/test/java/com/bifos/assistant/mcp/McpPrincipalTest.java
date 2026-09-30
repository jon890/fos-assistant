package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP 토큰은 profile 만 증명하고 요청자는 서명한 session 으로 찾은 origin 실행의 사용자라는 것을 실제
 * {@code /mcp} 경계에서 고정한다(ADR-032, ADR-037).
 *
 * <p>같은 GROUP profile 을 사용자 A 와 B 가 함께 써도 호출마다 자기 실행의 사용자로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpPrincipalTest {
    private static final String SHARED = "shared-group";
    private static final String PRIVATE_A = "private-a";
    private static final String INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";

    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @Autowired MemoryRepository memoryRepository;
    @Autowired MemoryService memories;
    @Autowired ConversationRepository conversations;
    @Autowired AgentExecutionRepository executions;
    @Autowired ArtifactStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubagentSessionRegistrar registrar;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser userA;
    private AppUser userB;
    private String sharedToken;
    private String privateAToken;
    private Memory memoryA;
    private Memory memoryB;
    private Conversation conversationA;
    private Conversation conversationB;

    @BeforeEach
    void 준비한다() {
        McpCallSigner.clearRuns(jdbc, List.of(SHARED, PRIVATE_A));
        memoryRepository.deleteAll();
        tokenRepository.deleteAll();
        users.deleteAll();
        userA = users.save(AppUser.of("principal-a@example.com", "가", 1L, UserRole.MEMBER));
        userB = users.save(AppUser.of("principal-b@example.com", "나", 1L, UserRole.MEMBER));
        sharedToken = tokens.issue(SHARED, "shared").rawToken();
        privateAToken = tokens.issue(PRIVATE_A, "private").rawToken();
        memoryA = memories.create(current(userA), MemoryScope.USER, "색인 가", "가의 본문", false);
        memoryB = memories.create(current(userB), MemoryScope.USER, "색인 나", "나의 본문", false);
        conversationA = conversations.save(Conversation.startedBy(userA.id(), "", null));
        conversationB = conversations.save(Conversation.startedBy(userB.id(), "", null));
    }

    @Test
    void 개인_profile_토큰은_그_사용자의_도는_실행으로_읽고_쓴다() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), PRIVATE_A, root);

        String path = uniquePath("own");

        assertBody(readMemory(privateAToken, root, memoryA.id()), "가의 본문");
        JsonNode written = body(writeArtifact(privateAToken, root, conversationA, path));
        assertThat(written.path("result").path("isError").asBoolean()).as("자기 대화에 쓴 결과: %s", written).isFalse();
        assertThat(store.resolveInside(conversationA.id(), path)).isPresent();
    }

    @Test
    void 공유_profile_토큰은_뿌리_session_의_실행_사용자로_돈다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        String rootB = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, rootA);
        McpCallSigner.running(executions, userB.id(), conversationB.id(), SHARED, rootB);

        assertBody(readMemory(sharedToken, rootA, memoryA.id()), "가의 본문");
        assertBody(readMemory(sharedToken, rootB, memoryB.id()), "나의 본문");
    }

    @Test
    void 공유_profile_의_하위_에이전트는_부모가_모두_끝난_뒤에도_등록한_origin_의_사용자로_돈다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        String rootB = McpCallSigner.newRoot();
        AgentExecution runA = McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, rootA);
        AgentExecution runB = McpCallSigner.running(executions, userB.id(), conversationB.id(), SHARED, rootB);
        String sa = newSubagent();
        String sb = newSubagent();
        registrar.register(SHARED, rootA, rootA, sa);
        registrar.register(SHARED, rootB, rootB, sb);
        finish(runA);
        finish(runB);

        assertBody(readMemory(sharedToken, rootA, sa, memoryA.id()), "가의 본문");
        assertHidden(readMemory(sharedToken, rootA, sa, memoryB.id()));
        assertBody(readMemory(sharedToken, rootB, sb, memoryB.id()), "나의 본문");
        assertHidden(readMemory(sharedToken, rootB, sb, memoryA.id()));
    }

    @Test
    void 두_사용자의_실행이_함께_돌아도_호출마다_자기_사용자로_돌고_섞이지_않는다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        String rootB = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, rootA);
        McpCallSigner.running(executions, userB.id(), conversationB.id(), SHARED, rootB);

        for (int i = 0; i < 3; i++) {
            assertBody(readMemory(sharedToken, rootA, memoryA.id()), "가의 본문");
            assertBody(readMemory(sharedToken, rootB, memoryB.id()), "나의 본문");
        }

        Map<String, Long> memoryOf = Map.of(rootA, memoryA.id(), rootB, memoryB.id());
        Map<String, String> expected = Map.of(rootA, "가의 본문", rootB, "나의 본문");
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 20; i++) order.add(i % 2 == 0 ? rootA : rootB);
        Map<Integer, Future<HttpResponse<String>>> futures = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < order.size(); i++) {
                String root = order.get(i);
                futures.put(i, pool.submit(() -> readMemory(sharedToken, root, memoryOf.get(root))));
            }
            for (Map.Entry<Integer, Future<HttpResponse<String>>> entry : futures.entrySet()) {
                String root = order.get(entry.getKey());
                assertBody(entry.getValue().get(), expected.get(root));
            }
        }
    }

    @Test
    void 다른_profile_의_실행_뿌리로_서명하면_호출_맥락_오류다() throws Exception {
        String sharedRoot = McpCallSigner.newRoot();
        String privateRoot = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, sharedRoot);
        McpCallSigner.running(executions, userA.id(), conversationA.id(), PRIVATE_A, privateRoot);

        assertInvalidContext(readMemory(privateAToken, sharedRoot, memoryA.id()));
        assertInvalidContext(readMemory(sharedToken, privateRoot, memoryA.id()));
        String path = uniquePath("cross");
        assertInvalidContext(writeArtifact(sharedToken, privateRoot, conversationA, path));
        assertThat(store.resolveInside(conversationA.id(), path)).isEmpty();
    }

    @Test
    void 남의_뿌리로_남의_Memory_를_읽으면_없는_항목과_같은_응답이다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        String rootB = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, rootA);
        McpCallSigner.running(executions, userB.id(), conversationB.id(), SHARED, rootB);

        JsonNode missing = body(readMemory(sharedToken, rootA, 999_999_999L)).path("result");
        JsonNode aReadsB = body(readMemory(sharedToken, rootA, memoryB.id())).path("result");
        JsonNode bReadsA = body(readMemory(sharedToken, rootB, memoryA.id())).path("result");

        assertThat(missing.path("content").get(0).path("text").asString()).isEqualTo("Memory 항목을 읽을 수 없습니다.");
        assertThat(aReadsB).isEqualTo(missing);
        assertThat(bReadsA).isEqualTo(missing);
    }

    @Test
    void 남의_대화에는_쓰지_못하고_같은_사용자의_다른_대화에는_쓴다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, rootA);
        Conversation otherOfA = conversations.save(Conversation.startedBy(userA.id(), "", null));

        String forbiddenPath = uniquePath("b");
        String otherPath = uniquePath("other");
        JsonNode forbidden = body(writeArtifact(sharedToken, rootA, conversationB, forbiddenPath)).path("result");
        JsonNode other = body(writeArtifact(sharedToken, rootA, otherOfA, otherPath)).path("result");

        assertThat(forbidden.path("isError").asBoolean()).isTrue();
        assertThat(forbidden.path("content").get(0).path("text").asString()).isEqualTo("결과물을 저장할 수 없습니다.");
        assertThat(store.resolveInside(conversationB.id(), forbiddenPath)).isEmpty();
        assertThat(other.path("isError").asBoolean()).as("같은 사용자의 다른 대화: %s", other).isFalse();
        assertThat(store.resolveInside(otherOfA.id(), otherPath)).isPresent();
    }

    @Test
    void 뿌리_칸이_빈_옛_대화의_session_으로_서명해도_그_실행의_사용자로_돈다() throws Exception {
        McpCallSigner.running(executions, userB.id(), conversationB.id(), SHARED, "legacy-session");

        assertBody(readMemory(sharedToken, "legacy-session", memoryB.id()), "나의 본문");
    }

    @Test
    void 폐기한_토큰과_모르는_토큰과_헤더_없음은_401_이다() throws Exception {
        String revoked = tokens.issue(SHARED, "revoked").rawToken();
        tokens.revoke(tokenRepository.findByTokenHash(AgentTokenService.hash(revoked)).orElseThrow().id());
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, root);

        assertThat(readMemory(revoked, root, memoryA.id()).statusCode()).isEqualTo(401);
        assertThat(readMemory("unknown-" + UUID.randomUUID(), root, memoryA.id()).statusCode()).isEqualTo(401);
        assertThat(send(null, readMemoryRequest(sharedToken, root, memoryA.id())).statusCode()).isEqualTo(401);
    }

    @Test
    void Control_Plane_이_시작하지_않은_run_의_호출은_본문_없이_거절된다() throws Exception {
        HttpResponse<String> response = readMemory(sharedToken, "cron-session-1", memoryA.id());

        assertInvalidContext(response);
        assertThat(response.body()).doesNotContain("가의 본문", "나의 본문");
    }

    @Test
    void 요청자를_정하지_못한_이유가_무엇이든_응답_본문은_바이트까지_같다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, rootA);
        String finishedRoot = McpCallSigner.newRoot();
        McpCallSigner.save(executions, userA.id(), conversationA.id(), SHARED, finishedRoot, ExecutionStatus.SUCCEEDED);
        String doubledRoot = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, doubledRoot);
        McpCallSigner.running(executions, userB.id(), conversationB.id(), SHARED, doubledRoot);
        String privateRoot = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), PRIVATE_A, privateRoot);
        String registered = newSubagent();
        registrar.register(SHARED, rootA, rootA, registered);
        String rootB = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userB.id(), conversationB.id(), SHARED, rootB);

        ObjectNode wrongSig = McpCallSigner.context(sharedToken, "memory_read", rootA);
        String sig = wrongSig.path("sig").asString();
        wrongSig.put("sig", sig.substring(0, 63) + (sig.endsWith("0") ? "1" : "0"));
        ObjectNode stringVersion = McpCallSigner.context(sharedToken, "memory_read", rootA);
        stringVersion.put("v", "1");
        // 모델이 서명을 흉내 내려면 key 가 필요하다. 다른 토큰의 해시로 서명한 것은 통과하지 못한다.
        ObjectNode forged = McpCallSigner.context(privateAToken, "memory_read", rootA);
        // 다른 도구의 서명은 이 도구에 쓰지 못한다.
        ObjectNode otherTool = McpCallSigner.context(sharedToken, "artifact_write", rootA);

        Map<String, String> rejections = new LinkedHashMap<>();
        rejections.put("_fos_ctx 없음", send(sharedToken, memoryReadRequest(memoryA.id(), null)).body());
        rejections.put("sig 가 틀림", send(sharedToken, memoryReadRequest(memoryA.id(), wrongSig)).body());
        rejections.put("v 가 문자열", send(sharedToken, memoryReadRequest(memoryA.id(), stringVersion)).body());
        rejections.put("흉내 낸 서명", send(sharedToken, memoryReadRequest(memoryA.id(), forged)).body());
        rejections.put("다른 도구의 서명", send(sharedToken, memoryReadRequest(memoryA.id(), otherTool)).body());
        rejections.put("끝난 실행", readMemory(sharedToken, finishedRoot, memoryA.id()).body());
        rejections.put("도는 실행 둘", readMemory(sharedToken, doubledRoot, memoryA.id()).body());
        rejections.put("다른 profile", readMemory(sharedToken, privateRoot, memoryA.id()).body());
        rejections.put("시작하지 않은 run", readMemory(sharedToken, "cron-session-1", memoryA.id()).body());
        rejections.put("등록 없는 하위 session", readMemory(sharedToken, rootA, newSubagent(), memoryA.id()).body());
        rejections.put("등록과 다른 뿌리", readMemory(sharedToken, rootB, registered, memoryA.id()).body());

        String first = rejections.values().iterator().next();
        JsonNode parsed = json.readTree(first);
        assertThat(parsed.path("result").path("isError").asBoolean()).isTrue();
        assertThat(parsed.path("result").path("content").get(0).path("text").asString()).isEqualTo(INVALID_CONTEXT);
        rejections.forEach((reason, responseBody) ->
                assertThat(responseBody).as("거절 이유 %s 의 응답 본문", reason).isEqualTo(first));
    }

    @Test
    void 모델이_인자에_사용자나_profile_을_더해도_요청자가_바뀌지_않는다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), conversationA.id(), SHARED, rootA);

        ObjectNode readArguments = json.createObjectNode();
        readArguments.put("id", memoryB.id());
        readArguments.put("user_id", userB.id());
        readArguments.put("profile", PRIVATE_A);
        readArguments.set("_fos_ctx", McpCallSigner.context(sharedToken, "memory_read", rootA));
        assertHidden(send(sharedToken, toolCall("memory_read", readArguments)));
        readArguments.put("id", memoryA.id());
        readArguments.set("_fos_ctx", McpCallSigner.context(sharedToken, "memory_read", rootA));
        assertBody(send(sharedToken, toolCall("memory_read", readArguments)), "가의 본문");

        ObjectNode writeArguments = json.createObjectNode();
        writeArguments.put("conversation_id", conversationB.publicId().toString());
        String path = uniquePath("injected");
        writeArguments.put("path", path);
        writeArguments.put("content", "<p>x</p>");
        writeArguments.put("user_id", userB.id());
        writeArguments.set("_fos_ctx", McpCallSigner.context(sharedToken, "artifact_write", rootA));
        JsonNode written = body(send(sharedToken, toolCall("artifact_write", writeArguments)));
        assertThat(written.path("error").path("code").asInt()).as("모르는 인자: %s", written).isEqualTo(-32602);
        assertThat(store.resolveInside(conversationB.id(), path)).isEmpty();
    }

    /** 결과물 폴더는 디스크에 남아 다른 검사의 대화 번호와 겹칠 수 있으므로 경로를 새로 만든다. */
    private static String uniquePath(String folder) {
        return folder + "/" + UUID.randomUUID() + ".html";
    }

    private HttpResponse<String> readMemory(String token, String root, Long memoryId) throws Exception {
        return send(token, readMemoryRequest(token, root, memoryId));
    }

    /** 뿌리 {@code root} 아래 하위 에이전트 session {@code session} 에서 부른 것처럼 서명해 읽는다. */
    private HttpResponse<String> readMemory(String token, String root, String session, Long memoryId) throws Exception {
        return send(token, memoryReadRequest(memoryId,
                McpCallSigner.context(token, "memory_read", root, session, "call_" + UUID.randomUUID())));
    }

    private static String newSubagent() {
        return "하위-" + UUID.randomUUID();
    }

    private void finish(AgentExecution execution) {
        jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", ExecutionStatus.SUCCEEDED.name(), execution.id());
    }

    private String readMemoryRequest(String token, String root, Long memoryId) {
        return memoryReadRequest(memoryId, McpCallSigner.context(token, "memory_read", root));
    }

    private String memoryReadRequest(Long memoryId, ObjectNode fosCtx) {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("id", memoryId);
        if (fosCtx != null) arguments.set("_fos_ctx", fosCtx);
        return toolCall("memory_read", arguments);
    }

    private HttpResponse<String> writeArtifact(String token, String root, Conversation conversation, String path) throws Exception {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("conversation_id", conversation.publicId().toString());
        arguments.put("path", path);
        arguments.put("content", "<p>결과물</p>");
        arguments.set("_fos_ctx", McpCallSigner.context(token, "artifact_write", root));
        return send(token, toolCall("artifact_write", arguments));
    }

    /** 응답 본문을 바이트까지 견주므로 요청 번호는 늘 같다. */
    private String toolCall(String name, ObjectNode arguments) {
        ObjectNode params = json.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        return json.writeValueAsString(request);
    }

    private HttpResponse<String> send(String token, String request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertBody(HttpResponse<String> response, String expected) {
        JsonNode result = body(response).path("result");
        assertThat(result.path("isError").asBoolean()).as("읽기 결과: %s", result).isFalse();
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo(expected);
    }

    private void assertHidden(HttpResponse<String> response) {
        JsonNode result = body(response).path("result");
        assertThat(result.path("isError").asBoolean()).as("숨긴 결과: %s", result).isTrue();
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("Memory 항목을 읽을 수 없습니다.");
    }

    private void assertInvalidContext(HttpResponse<String> response) {
        JsonNode result = body(response).path("result");
        assertThat(result.path("isError").asBoolean()).as("거절 결과: %s", result).isTrue();
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo(INVALID_CONTEXT);
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("HTTP 상태, 본문: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }
}
