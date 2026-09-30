package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * {@code agent_list} 와 {@code agent_status} 가 origin 실행의 사용자와 그 실행 나무 안에서만 답하는 것을 실제
 * {@code /mcp} 경계에서 고정한다(ADR-017, ADR-032, ADR-037).
 *
 * <p>같은 GROUP profile 을 사용자 A 와 B 가 함께 써도 각자의 목록과 각자의 위임 실행만 받는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpAgentToolsTest {
    private static final String SHARED = "tools-shared";
    private static final String PRIVATE_A = "tools-private-a";
    private static final String TARGET = "tools-target";
    private static final String INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";
    private static final String GROUP_CODE = "tools-group";
    private static final String OWN_A_CODE = "tools-own-a";
    private static final String OWN_B_CODE = "tools-own-b";
    private static final String OFF_CODE = "tools-off";
    private static final List<String> CODES = List.of(GROUP_CODE, OWN_A_CODE, OWN_B_CODE, OFF_CODE);
    private static final Instant STARTED = Instant.parse("2026-09-30T00:00:00Z");

    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentExecutionRepository executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubagentSessionRegistrar registrar;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser userA;
    private AppUser userB;
    private String sharedToken;
    private String privateAToken;

    @BeforeEach
    void 준비한다() {
        McpCallSigner.clearRuns(jdbc, List.of(SHARED, PRIVATE_A, TARGET));
        CODES.forEach(code -> agents.findByCode(code).ifPresent(agents::delete));
        tokenRepository.deleteAll();
        users.deleteAll();
        userA = users.save(AppUser.of("tools-a@example.com", "가", 1L, UserRole.MEMBER));
        userB = users.save(AppUser.of("tools-b@example.com", "나", 1L, UserRole.MEMBER));
        sharedToken = tokens.issue(SHARED, "shared").rawToken();
        privateAToken = tokens.issue(PRIVATE_A, "private").rawToken();
        agents.save(agent(GROUP_CODE, "함께 쓰는 조사원", AgentVisibility.GROUP, userA.id()));
        agents.save(agent(OWN_A_CODE, "가의 비서", AgentVisibility.PRIVATE, userA.id()));
        agents.save(agent(OWN_B_CODE, "나의 비서", AgentVisibility.PRIVATE, userB.id()));
        Agent off = agent(OFF_CODE, "꺼진 조사원", AgentVisibility.GROUP, userA.id());
        off.changeAccess(false, AgentVisibility.GROUP, userA.id());
        agents.save(off);
    }

    @Test
    void 도구_목록에_두_도구의_규격이_있다() throws Exception {
        JsonNode listed = body(send(sharedToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).path("result").path("tools");
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        listed.forEach(tool -> byName.put(tool.path("name").asString(), tool));

        assertThat(byName).containsKeys("agent_list", "agent_status");
        assertThat(byName.get("agent_list").path("inputSchema").path("properties").isEmpty()).isTrue();
        assertThat(byName.get("agent_list").path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
        JsonNode status = byName.get("agent_status").path("inputSchema");
        assertThat(status.path("properties").path("execution_id").path("type").asString()).isEqualTo("integer");
        assertThat(status.path("required").get(0).asString()).isEqualTo("execution_id");
        assertThat(status.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void agent_list_는_요청자가_쓸_수_있는_켜진_에이전트만_code_와_이름으로_준다() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, PRIVATE_A, root);

        JsonNode listed = agentList(privateAToken, root);

        assertThat(codes(listed)).contains(GROUP_CODE, OWN_A_CODE).doesNotContain(OWN_B_CODE, OFF_CODE);
        listed.forEach(item -> assertThat(item.propertyNames()).containsExactly("code", "name"));
        JsonNode group = find(listed, GROUP_CODE);
        assertThat(group.path("name").asString()).isEqualTo("함께 쓰는 조사원");
    }

    @Test
    void 같은_공유_profile_로_도는_두_사용자는_각자의_목록을_받는다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        String rootB = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, SHARED, rootA);
        McpCallSigner.running(executions, userB.id(), null, SHARED, rootB);

        List<String> listedA = codes(agentList(sharedToken, rootA));
        List<String> listedB = codes(agentList(sharedToken, rootB));

        assertThat(listedA).contains(GROUP_CODE, OWN_A_CODE).doesNotContain(OWN_B_CODE, OFF_CODE);
        assertThat(listedB).contains(GROUP_CODE, OWN_B_CODE).doesNotContain(OWN_A_CODE, OFF_CODE);
    }

    @Test
    void agent_list_응답에는_profile_과_주소와_공개_범위가_없다() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, SHARED, root);

        String text = resultText(agentListCall(sharedToken, root));

        for (String code : CODES) {
            assertThat(text).doesNotContain(profileOf(code), "agent-runtime.test");
        }
        assertThat(text).doesNotContain("GROUP", "PRIVATE", "visibility", "owner", "credential", "hermesProfile", "apiBaseUrl", "id\"");
    }

    @Test
    void agent_list_에_다른_인자가_오면_인자_오류다() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        ObjectNode arguments = json.createObjectNode();
        arguments.put("user_id", userB.id());
        arguments.set("_fos_ctx", McpCallSigner.context(sharedToken, "agent_list", root));

        JsonNode response = body(send(sharedToken, toolCall("agent_list", arguments)));

        assertThat(response.path("error").path("code").asInt()).as("모르는 인자: %s", response).isEqualTo(-32602);
    }

    @Test
    void agent_status_는_같은_나무의_위임_실행을_상태별로_답한다() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        AgentExecution running = delegated(userA.id(), parent, ExecutionStatus.RUNNING, null, null);
        AgentExecution succeeded = delegated(userA.id(), parent, ExecutionStatus.SUCCEEDED, "조사한 결과다", null);
        AgentExecution failed = delegated(userA.id(), parent, ExecutionStatus.FAILED, null, "HERMES_RUN_FAILED");
        AgentExecution cancelled = delegated(userA.id(), parent, ExecutionStatus.CANCELLED, null, null);
        AgentExecution cancelledWithOutput = delegated(userA.id(), parent, ExecutionStatus.CANCELLED, "멈춘 자리까지", null);

        assertStatus(agentStatus(sharedToken, root, running.id()), "{\"execution_id\":" + running.id() + ",\"status\":\"RUNNING\"}");
        assertStatus(agentStatus(sharedToken, root, succeeded.id()),
                "{\"execution_id\":" + succeeded.id() + ",\"status\":\"SUCCEEDED\",\"output\":\"조사한 결과다\"}");
        assertStatus(agentStatus(sharedToken, root, failed.id()),
                "{\"execution_id\":" + failed.id() + ",\"status\":\"FAILED\",\"error_code\":\"HERMES_RUN_FAILED\"}");
        assertStatus(agentStatus(sharedToken, root, cancelled.id()), "{\"execution_id\":" + cancelled.id() + ",\"status\":\"CANCELLED\"}");
        assertStatus(agentStatus(sharedToken, root, cancelledWithOutput.id()),
                "{\"execution_id\":" + cancelledWithOutput.id() + ",\"status\":\"CANCELLED\",\"output\":\"멈춘 자리까지\"}");
    }

    @Test
    void agent_status_응답에는_run_번호와_profile_과_토큰_수가_없다() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        AgentExecution succeeded = delegated(userA.id(), parent, ExecutionStatus.SUCCEEDED, "답", null);
        jdbc.update("UPDATE agent_execution SET hermes_run_id = ?, input_tokens = ?, output_tokens = ?, estimated_cost_micros = ? WHERE id = ?",
                "run-secret-1", 987_654_321L, 876_543_219L, 765_432_198L, succeeded.id());

        String text = resultText(agentStatus(sharedToken, root, succeeded.id()));

        JsonNode status = json.readTree(text);
        assertThat(status.propertyNames()).containsExactly("execution_id", "status", "output");
        assertThat(status.path("execution_id").asLong()).isEqualTo(succeeded.id());
        assertThat(status.path("output").asString()).isEqualTo("답");
        assertThat(text).doesNotContain("run-secret-1", TARGET, SHARED, "987654321", "876543219", "765432198");
    }

    @Test
    void 남의_실행과_다른_나무와_위임이_아닌_실행과_없는_번호는_모두_같은_응답이다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        AgentExecution parentA = McpCallSigner.running(executions, userA.id(), null, SHARED, rootA);
        String rootB = McpCallSigner.newRoot();
        AgentExecution parentB = McpCallSigner.running(executions, userB.id(), null, SHARED, rootB);
        AgentExecution otherTreeRoot = McpCallSigner.save(executions, userA.id(), null, SHARED, McpCallSigner.newRoot(), ExecutionStatus.SUCCEEDED);
        // 나무와 위임 여부는 맞고 사용자만 다르다.
        AgentExecution otherUserInTree = delegated(userB.id(), parentA, ExecutionStatus.SUCCEEDED, "나의 답", null);
        AgentExecution otherUsersOwn = delegated(userB.id(), parentB, ExecutionStatus.SUCCEEDED, "나의 답", null);
        AgentExecution otherTree = delegated(userA.id(), otherTreeRoot, ExecutionStatus.SUCCEEDED, "다른 나무의 답", null);
        AgentExecution proposal = child(userA.id(), parentA, ExecutionStatus.SUCCEEDED);

        JsonNode missing = body(agentStatus(sharedToken, rootA, 999_999_999L)).path("result");
        Map<String, JsonNode> hidden = new LinkedHashMap<>();
        hidden.put("같은 나무의 다른 사용자 실행", body(agentStatus(sharedToken, rootA, otherUserInTree.id())).path("result"));
        hidden.put("다른 사용자 나무의 실행", body(agentStatus(sharedToken, rootA, otherUsersOwn.id())).path("result"));
        hidden.put("다른 나무의 실행", body(agentStatus(sharedToken, rootA, otherTree.id())).path("result"));
        hidden.put("위임이 아닌 자식", body(agentStatus(sharedToken, rootA, proposal.id())).path("result"));
        hidden.put("대화 turn", body(agentStatus(sharedToken, rootA, parentA.id())).path("result"));

        assertThat(missing.path("isError").asBoolean()).isTrue();
        JsonNode failure = json.readTree(missing.path("content").get(0).path("text").asString());
        assertThat(failure.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(failure.path("message").asString()).isEqualTo("실행을 찾을 수 없습니다.");
        hidden.forEach((reason, result) -> assertThat(result).as(reason).isEqualTo(missing));
        assertStatus(agentStatus(sharedToken, rootB, otherUsersOwn.id()),
                "{\"execution_id\":" + otherUsersOwn.id() + ",\"status\":\"SUCCEEDED\",\"output\":\"나의 답\"}");
    }

    @Test
    void 부모가_끝난_뒤_하위_에이전트가_물어도_origin_실행의_나무로_판정한다() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(SHARED, root, root, subagent);
        AgentExecution succeeded = delegated(userA.id(), parent, ExecutionStatus.SUCCEEDED, "조사한 결과다", null);
        setStatus(parent, ExecutionStatus.SUCCEEDED);

        assertStatus(agentStatus(sharedToken, root, subagent, succeeded.id()),
                "{\"execution_id\":" + succeeded.id() + ",\"status\":\"SUCCEEDED\",\"output\":\"조사한 결과다\"}");
        assertThat(codes(json.readTree(resultText(send(sharedToken, toolCall("agent_list",
                withContext(json.createObjectNode(), McpCallSigner.context(sharedToken, "agent_list", root, subagent, "call_" + UUID.randomUUID()))))))))
                .contains(OWN_A_CODE).doesNotContain(OWN_B_CODE);
    }

    @Test
    void 뿌리가_중지된_하위_에이전트의_물음은_호출_맥락_오류다() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(SHARED, root, root, subagent);
        AgentExecution succeeded = delegated(userA.id(), parent, ExecutionStatus.SUCCEEDED, "조사한 결과다", null);
        setStatus(parent, ExecutionStatus.CANCELLED);

        HttpResponse<String> response = agentStatus(sharedToken, root, subagent, succeeded.id());

        assertInvalidContext(response);
        assertThat(response.body()).doesNotContain("조사한 결과다");
    }

    @Test
    void 요청자를_정하지_못한_agent_도구는_이유와_무관하게_같은_거절이다() throws Exception {
        String rootA = McpCallSigner.newRoot();
        AgentExecution parentA = McpCallSigner.running(executions, userA.id(), null, SHARED, rootA);
        AgentExecution succeeded = delegated(userA.id(), parentA, ExecutionStatus.SUCCEEDED, "조사한 결과다", null);
        String privateRoot = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, PRIVATE_A, privateRoot);

        ObjectNode wrongSig = McpCallSigner.context(sharedToken, "agent_status", rootA);
        String sig = wrongSig.path("sig").asString();
        wrongSig.put("sig", sig.substring(0, 63) + (sig.endsWith("0") ? "1" : "0"));
        // 모델이 서명을 흉내 내려면 key 가 필요하다. 다른 토큰의 해시로 서명한 것은 통과하지 못한다.
        ObjectNode forged = McpCallSigner.context(privateAToken, "agent_status", rootA);
        ObjectNode otherTool = McpCallSigner.context(sharedToken, "agent_list", rootA);

        List<HttpResponse<String>> rejections = new ArrayList<>();
        rejections.add(send(sharedToken, toolCall("agent_list", json.createObjectNode())));
        rejections.add(send(sharedToken, toolCall("agent_list", withContext(json.createObjectNode(),
                McpCallSigner.context(privateAToken, "agent_list", rootA)))));
        rejections.add(send(sharedToken, statusRequest(succeeded.id(), null)));
        rejections.add(send(sharedToken, statusRequest(succeeded.id(), wrongSig)));
        rejections.add(send(sharedToken, statusRequest(succeeded.id(), forged)));
        rejections.add(send(sharedToken, statusRequest(succeeded.id(), otherTool)));
        // 다른 profile 의 토큰은 그 profile 의 실행 뿌리로 서명해도 이 profile 의 실행을 origin 으로 쓰지 못한다.
        rejections.add(agentStatus(privateAToken, rootA, succeeded.id()));
        rejections.add(agentListCall(sharedToken, privateRoot));

        String first = rejections.getFirst().body();
        for (HttpResponse<String> rejection : rejections) {
            assertInvalidContext(rejection);
            assertThat(rejection.body()).isEqualTo(first);
        }
    }

    @Test
    void agent_status_의_인자가_정수_번호_하나가_아니면_인자_오류다() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, SHARED, root);

        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(json.createObjectNode());
        invalid.add(json.createObjectNode().put("execution_id", "12"));
        invalid.add(json.createObjectNode().put("execution_id", 1.5));
        invalid.add(json.createObjectNode().put("execution_id", 12).put("user_id", userB.id()));
        for (ObjectNode arguments : invalid) {
            arguments.set("_fos_ctx", McpCallSigner.context(sharedToken, "agent_status", root));
            JsonNode response = body(send(sharedToken, toolCall("agent_status", arguments)));
            assertThat(response.path("error").path("code").asInt()).as("인자 %s: %s", arguments, response).isEqualTo(-32602);
        }
    }

    private static Agent agent(String code, String name, AgentVisibility visibility, Long ownerUserId) {
        return Agent.of(code, name, profileOf(code), "http://agent-runtime.test/p/" + code,
                CostMode.API, CredentialScope.DEDICATED, visibility, ownerUserId);
    }

    private static String profileOf(String code) {
        return "profile-of-" + code;
    }

    /** {@code agent_delegate} 로 만든 것처럼 {@code delegation_key} 가 있는 자식 실행을 만든다. */
    private AgentExecution delegated(Long userId, AgentExecution parent, ExecutionStatus status, String output, String errorCode) {
        AgentExecution execution = executions.save(AgentExecution.builder()
                .userId(userId)
                .parentExecutionId(parent.id())
                .rootExecutionId(parent.treeRootId())
                .profileName(TARGET)
                .hermesSessionId(McpCallSigner.newRoot())
                .delegationKey((UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""))
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .errorCode(errorCode)
                .startedAt(STARTED)
                .build());
        if (output != null) {
            jdbc.update("UPDATE agent_execution SET output_text = ? WHERE id = ?", output, execution.id());
        }
        return execution;
    }

    /** Memory 제안처럼 위임이 아닌 자식 실행이다. */
    private AgentExecution child(Long userId, AgentExecution parent, ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
                .userId(userId)
                .parentExecutionId(parent.id())
                .rootExecutionId(parent.treeRootId())
                .profileName(TARGET)
                .costMode(CostMode.SUBSCRIPTION)
                .status(status)
                .startedAt(STARTED)
                .build());
    }

    private void setStatus(AgentExecution execution, ExecutionStatus status) {
        jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", status.name(), execution.id());
    }

    private JsonNode agentList(String token, String root) throws Exception {
        return json.readTree(resultText(agentListCall(token, root)));
    }

    private HttpResponse<String> agentListCall(String token, String root) throws Exception {
        return send(token, toolCall("agent_list", withContext(json.createObjectNode(), McpCallSigner.context(token, "agent_list", root))));
    }

    private HttpResponse<String> agentStatus(String token, String root, Long executionId) throws Exception {
        return send(token, statusRequest(executionId, McpCallSigner.context(token, "agent_status", root)));
    }

    /** 뿌리 {@code root} 아래 하위 에이전트 session {@code session} 에서 부른 것처럼 서명해 묻는다. */
    private HttpResponse<String> agentStatus(String token, String root, String session, Long executionId) throws Exception {
        return send(token, statusRequest(executionId,
                McpCallSigner.context(token, "agent_status", root, session, "call_" + UUID.randomUUID())));
    }

    private String statusRequest(Long executionId, ObjectNode fosCtx) {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("execution_id", executionId);
        return toolCall("agent_status", withContext(arguments, fosCtx));
    }

    private static ObjectNode withContext(ObjectNode arguments, ObjectNode fosCtx) {
        if (fosCtx != null) arguments.set("_fos_ctx", fosCtx);
        return arguments;
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
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> codes(JsonNode listed) {
        List<String> codes = new ArrayList<>();
        listed.forEach(item -> codes.add(item.path("code").asString()));
        return codes;
    }

    private static JsonNode find(JsonNode listed, String code) {
        for (JsonNode item : listed) {
            if (code.equals(item.path("code").asString())) return item;
        }
        throw new AssertionError("목록에 " + code + " 가 없다: " + listed);
    }

    private String resultText(HttpResponse<String> response) {
        JsonNode result = body(response).path("result");
        assertThat(result.path("isError").asBoolean()).as("도구 결과: %s", result).isFalse();
        return result.path("content").get(0).path("text").asString();
    }

    private void assertStatus(HttpResponse<String> response, String expected) {
        assertThat(resultText(response)).isEqualTo(expected);
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
}
