package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.MemoryScope;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.DelegationKey;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code agent_list} 와 {@code agent_status} 가 origin 실행의 사용자와 그 대화 안에서만 답하는 것을 실제
 * {@code /mcp} 경계에서 고정한다(ADR-017, ADR-032, ADR-037).
 *
 * <p>같은 GROUP profile 을 사용자 A 와 B 가 함께 써도 각자의 목록과 각자의 위임 실행만 받는다.
 * {@code conversationId} 없이 만든 origin 은 대화가 없을 때의 대체 규칙인 실행 나무로 판정된다.
 * 대화 규칙은 origin 과 위임 실행에 대화 번호를 준 검사가 고정한다. {@code conversation_id} 에는 외래 키가 없어
 * 대화 줄을 만들지 않고 번호만 준다.
 *
 * <p>{@code agent_delegate} 는 대역 Hermes 로 자식을 끝까지 돌린다. 위임은 가상 스레드에서 돌므로 각 검사는 자기가 띄운
 * 실행이 끝날 때까지 기다린 뒤 끝난다. 제출 대기와 전체 한도와 답 자르기는 설정값을 바꾼 {@code AgentDelegationServiceTest}
 * 가 본다.
 *
 * <p>{@code agent_stop} 은 {@code agent_status} 와 같은 판정을 지나고, 멈춘 위임 실행이 origin 인 Hermes 하위 에이전트의
 * 호출은 거절된다(ADR-037). turn 중지와 이어지는 것은 {@code AgentDelegationServiceTest} 가 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(McpAgentToolsTest.StubRuntime.class)
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
    /** 검사가 실행 줄을 남기는 profile 이다. 위임 자식은 에이전트의 profile 로 돈다. */
    private static final List<String> PROFILES = List.of(SHARED, PRIVATE_A, TARGET,
            profileOf(GROUP_CODE), profileOf(OWN_A_CODE), profileOf(OWN_B_CODE), profileOf(OFF_CODE));
    private static final Instant STARTED = Instant.parse("2026-09-30T00:00:00Z");
    private static final Long CONVERSATION = 930_001L;
    private static final Long OTHER_CONVERSATION = 930_002L;

    @LocalServerPort int port;
    @Autowired AgentTokenService tokens;
    @Autowired AgentTokenRepository tokenRepository;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentExecutionRepository executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubagentSessionRegistrar registrar;
    @Autowired HermesRunsClient hermes;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired MemoryService memories;
    @Autowired ExecutionEventRepository executionEvents;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser userA;
    private AppUser userB;
    private String sharedToken;
    private String privateAToken;

    @BeforeEach
    void setUp() {
        stub().reset();
        McpCallSigner.clearRuns(jdbc, PROFILES);
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
    @DisplayName("도구 목록에 두 도구의 규격이 있다")
    void toolListHasSpecsOfBothTools() throws Exception {
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
    @DisplayName("agent list 는 요청자가 쓸 수 있는 켜진 에이전트만 code 와 이름으로 준다")
    void agentListReturnsOnlyEnabledAgentsRequesterCanUseWithCodeAndName() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, PRIVATE_A, root);

        JsonNode listed = agentList(privateAToken, root);

        assertThat(codes(listed)).contains(GROUP_CODE, OWN_A_CODE).doesNotContain(OWN_B_CODE, OFF_CODE);
        listed.forEach(item -> assertThat(item.propertyNames()).containsExactly("code", "name"));
        JsonNode group = find(listed, GROUP_CODE);
        assertThat(group.path("name").asString()).isEqualTo("함께 쓰는 조사원");
    }

    @Test
    @DisplayName("같은 공유 profile 로 도는 두 사용자는 각자의 목록을 받는다")
    void twoUsersOnSameSharedProfileEachGetOwnList() throws Exception {
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
    @DisplayName("agent list 응답에는 profile 과 주소와 공개 범위가 없다")
    void agentListResponseHasNoProfileUrlOrVisibility() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, SHARED, root);

        String text = resultText(agentListCall(sharedToken, root));

        for (String code : CODES) {
            assertThat(text).doesNotContain(profileOf(code), "agent-runtime.test");
        }
        assertThat(text).doesNotContain("GROUP", "PRIVATE", "visibility", "owner", "credential", "hermesProfile", "apiBaseUrl", "id\"");
    }

    @Test
    @DisplayName("agent list 에 다른 인자가 오면 인자 오류다")
    void agentListWithOtherArgumentsIsArgumentError() throws Exception {
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        ObjectNode arguments = json.createObjectNode();
        arguments.put("user_id", userB.id());
        arguments.set("_fos_ctx", McpCallSigner.context(sharedToken, "agent_list", root));

        JsonNode response = body(send(sharedToken, toolCall("agent_list", arguments)));

        assertThat(response.path("error").path("code").asInt()).as("모르는 인자: %s", response).isEqualTo(-32602);
    }

    @Test
    @DisplayName("대화 없는 origin 의 agent status 는 같은 나무의 위임 실행을 상태별로 답한다")
    void agentStatusWithoutConversationAnswersSameTreeRunsByState() throws Exception {
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
    @DisplayName("agent status 응답에는 run 번호와 profile 과 토큰 수가 없다")
    void agentStatusResponseHasNoRunIdProfileOrTokenCount() throws Exception {
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
    @DisplayName("대화 없는 origin 에서 남의 실행과 다른 나무와 위임이 아닌 실행과 없는 번호는 모두 같은 응답이다")
    void originWithoutConversationGivesSameResponseForOthersAndMissingRuns() throws Exception {
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
    @DisplayName("같은 대화의 앞 turn 에서 맡긴 실행을 다음 turn 에서 묻는다")
    void asksInNextTurnAboutRunDelegatedInEarlierTurnOfSameConversation() throws Exception {
        AgentExecution firstTurn = McpCallSigner.save(executions, userA.id(), CONVERSATION, SHARED, McpCallSigner.newRoot(),
                ExecutionStatus.SUCCEEDED);
        AgentExecution earlier = delegated(userA.id(), firstTurn, ExecutionStatus.SUCCEEDED, "앞 turn 에서 맡긴 답", null);
        String root = McpCallSigner.newRoot();
        AgentExecution nextTurn = McpCallSigner.running(executions, userA.id(), CONVERSATION, SHARED, root);

        assertThat(nextTurn.treeRootId()).as("다음 turn 은 뿌리가 다르다").isNotEqualTo(earlier.treeRootId());
        assertStatus(agentStatus(sharedToken, root, earlier.id()),
                "{\"execution_id\":" + earlier.id() + ",\"status\":\"SUCCEEDED\",\"output\":\"앞 turn 에서 맡긴 답\"}");
    }

    @Test
    @DisplayName("대화가 있는 origin 에서 다른 대화와 위임이 아닌 실행과 남의 실행은 없는 번호와 같은 응답이다")
    void originWithConversationGivesMissingIdResponseForOthersRuns() throws Exception {
        AgentExecution firstTurn = McpCallSigner.save(executions, userA.id(), CONVERSATION, SHARED, McpCallSigner.newRoot(),
                ExecutionStatus.SUCCEEDED);
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), CONVERSATION, SHARED, root);
        AgentExecution otherConversationTurn = McpCallSigner.save(executions, userA.id(), OTHER_CONVERSATION, SHARED,
                McpCallSigner.newRoot(), ExecutionStatus.SUCCEEDED);
        AgentExecution otherConversation = delegated(userA.id(), otherConversationTurn, ExecutionStatus.SUCCEEDED, "다른 대화의 답", null);
        AgentExecution proposal = child(userA.id(), firstTurn, ExecutionStatus.SUCCEEDED);
        // 대화 번호는 같고 사용자만 다르다.
        AgentExecution otherUserTurn = McpCallSigner.save(executions, userB.id(), CONVERSATION, SHARED, McpCallSigner.newRoot(),
                ExecutionStatus.SUCCEEDED);
        AgentExecution otherUsers = delegated(userB.id(), otherUserTurn, ExecutionStatus.SUCCEEDED, "나의 답", null);

        JsonNode missing = body(agentStatus(sharedToken, root, 999_999_999L)).path("result");
        Map<String, JsonNode> hidden = new LinkedHashMap<>();
        hidden.put("같은 사용자의 다른 대화의 위임 실행", body(agentStatus(sharedToken, root, otherConversation.id())).path("result"));
        hidden.put("같은 대화의 위임이 아닌 자식", body(agentStatus(sharedToken, root, proposal.id())).path("result"));
        hidden.put("같은 대화의 앞 turn", body(agentStatus(sharedToken, root, firstTurn.id())).path("result"));
        hidden.put("같은 대화 번호의 남의 위임 실행", body(agentStatus(sharedToken, root, otherUsers.id())).path("result"));

        JsonNode failure = json.readTree(missing.path("content").get(0).path("text").asString());
        assertThat(failure.path("code").asString()).isEqualTo("NOT_FOUND");
        hidden.forEach((reason, result) -> assertThat(result).as(reason).isEqualTo(missing));
    }

    @Test
    @DisplayName("부모가 끝난 뒤 하위 에이전트가 물어도 origin 실행의 나무로 판정한다")
    void judgesByOriginRunTreeWhenSubagentAsksAfterParentEnded() throws Exception {
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
    @DisplayName("뿌리가 중지된 하위 에이전트의 물음은 호출 맥락 오류다")
    void questionFromSubagentWhoseRootStoppedIsCallContextError() throws Exception {
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
    @DisplayName("요청자를 정하지 못한 agent 도구는 이유와 무관하게 같은 거절이다")
    void agentToolsWithoutRequesterGiveSameRefusalRegardlessOfReason() throws Exception {
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
    @DisplayName("agent status 의 인자가 정수 번호 하나가 아니면 인자 오류다")
    void agentStatusArgIsArgumentErrorUnlessSingleIntegerId() throws Exception {
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

    @Test
    @DisplayName("도구 목록에 agent delegate 의 규격이 있다")
    void toolListHasAgentDelegateSpec() throws Exception {
        JsonNode listed = body(send(sharedToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).path("result").path("tools");
        JsonNode delegate = null;
        for (JsonNode tool : listed) {
            if ("agent_delegate".equals(tool.path("name").asString())) delegate = tool;
        }

        assertThat(delegate).as("도구 목록: %s", listed).isNotNull();
        JsonNode schema = delegate.path("inputSchema");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("agent_code").path("type").asString()).isEqualTo("string");
        assertThat(schema.path("properties").path("task").path("type").asString()).isEqualTo("string");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(item -> required.add(item.asString()));
        assertThat(required).containsExactlyInAnyOrder("agent_code", "task");
        assertThat(delegate.path("description").asString()).contains("agent_status", "agent_list");
    }

    @Test
    @DisplayName("agent delegate 는 번호와 RUNNING 을 바로 주고 끝난 답은 agent status 로 읽는다")
    void agentDelegateReturnsIdAndRunningAndFinishedAnswerIsReadByStatus() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);
        String toolCallId = "call_" + UUID.randomUUID();
        long messagesBefore = messages.count();

        JsonNode started = started(delegateCall(sharedToken, root, root, toolCallId, delegateArguments(GROUP_CODE, "자료를 찾아 줘")));

        assertThat(started.propertyNames()).containsExactly("execution_id", "status");
        assertThat(started.path("status").asString()).isEqualTo("RUNNING");
        AgentExecution child = awaitFinished(started.path("execution_id").asLong());
        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
        assertThat(child.rootExecutionId()).isEqualTo(parent.id());
        assertThat(child.userId()).isEqualTo(userA.id());
        assertThat(child.conversationId()).isEqualTo(parent.conversationId());
        assertThat(child.profileName()).isEqualTo(profileOf(GROUP_CODE));
        assertThat(child.hermesSessionId()).startsWith("fos-").isNotEqualTo(parent.hermesSessionId());
        assertThat(child.delegationKey()).isEqualTo(DelegationKey.of(SHARED, root, root, toolCallId).value());
        assertThat(messages.count()).as("자식의 답은 대화 이력에 들어가지 않는다").isEqualTo(messagesBefore);
        assertStatus(agentStatus(sharedToken, root, child.id()),
                "{\"execution_id\":" + child.id() + ",\"status\":\"SUCCEEDED\",\"output\":\"맡은 일의 답: 자료를 찾아 줘\"}");

        // 같은 대화의 다음 turn 은 뿌리가 다르지만 앞 turn 에서 맡긴 실행을 묻는다.
        setStatus(parent, ExecutionStatus.SUCCEEDED);
        String nextRoot = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), parent.conversationId(), SHARED, nextRoot);
        assertStatus(agentStatus(sharedToken, nextRoot, child.id()),
                "{\"execution_id\":" + child.id() + ",\"status\":\"SUCCEEDED\",\"output\":\"맡은 일의 답: 자료를 찾아 줘\"}");
    }

    @Test
    @DisplayName("agent delegate 의 자식은 에이전트의 profile 로 가고 원래 사용자로 다시 조립한 Memory 를 받는다")
    void delegateChildGoesToAgentProfileWithMemoryForOriginalUser() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        CurrentUser a = current(userA);
        memories.create(a, MemoryScope.USER, "가의 취향", "가는 국물을 맵지 않게 먹는다", true);
        memories.create(current(userB), MemoryScope.USER, "나의 취향", "나는 고수를 먹지 않는다", true);
        String root = McpCallSigner.newRoot();
        turn(userA, root);

        JsonNode started = started(delegate(sharedToken, root, OWN_A_CODE, "저녁 메뉴를 골라 줘"));
        awaitFinished(started.path("execution_id").asLong());

        assertThat(stub().received()).hasSize(1);
        HermesRunCommand command = stub().received().getFirst();
        assertThat(command.profileName()).isEqualTo(profileOf(OWN_A_CODE));
        assertThat(command.apiBaseUrl()).isEqualTo("http://agent-runtime.test/p/" + OWN_A_CODE);
        assertThat(command.input()).isEqualTo("저녁 메뉴를 골라 줘");
        assertThat(command.instructions()).contains("가는 국물을 맵지 않게 먹는다").doesNotContain("나는 고수를 먹지 않는다");
    }

    @Test
    @DisplayName("하위 에이전트가 부른 agent delegate 의 부모는 끝난 origin 실행이고 하위 에이전트 몫의 줄은 없다")
    void delegateFromSubagentHasEndedOriginRunAsParentAndNoSubagentRow() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(SHARED, root, root, subagent);
        setStatus(parent, ExecutionStatus.SUCCEEDED);

        JsonNode started = started(delegateCall(sharedToken, root, subagent, "call_" + UUID.randomUUID(),
                delegateArguments(GROUP_CODE, "하위 에이전트가 맡긴다")));
        AgentExecution child = awaitFinished(started.path("execution_id").asLong());

        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
        assertThat(child.rootExecutionId()).isEqualTo(parent.id());
        assertThat(executions.findByRootExecutionId(parent.id())).extracting(AgentExecution::id).containsExactly(child.id());
        assertThat(executions.existsByProfileNameAndHermesSessionId(SHARED, subagent)).isFalse();
    }

    @Test
    @DisplayName("agent delegate 에 profile 이나 사용자를 넣거나 task 가 비었거나 길면 인자 오류다")
    void agentDelegateIsArgumentErrorWithProfileOrUserOrEmptyOrLongTask() throws Exception {
        String root = McpCallSigner.newRoot();
        turn(userA, root);

        Map<String, ObjectNode> invalid = new LinkedHashMap<>();
        invalid.put("profile 을 넣었다", delegateArguments(GROUP_CODE, "일").put("profile", profileOf(OWN_B_CODE)));
        invalid.put("사용자를 넣었다", delegateArguments(GROUP_CODE, "일").put("user_id", userB.id()));
        invalid.put("부모를 넣었다", delegateArguments(GROUP_CODE, "일").put("parent_execution_id", 1));
        invalid.put("task 가 없다", json.createObjectNode().put("agent_code", GROUP_CODE));
        invalid.put("task 가 비었다", delegateArguments(GROUP_CODE, ""));
        invalid.put("task 가 공백뿐이다", delegateArguments(GROUP_CODE, "  \n "));
        invalid.put("task 가 8,000자를 넘는다", delegateArguments(GROUP_CODE, "가".repeat(8_001)));
        invalid.put("agent_code 가 문자열이 아니다", json.createObjectNode().put("agent_code", 3).put("task", "일"));
        for (Map.Entry<String, ObjectNode> entry : invalid.entrySet()) {
            JsonNode response = body(delegateCall(sharedToken, root, root, "call_" + UUID.randomUUID(), entry.getValue()));
            assertThat(response.path("error").path("code").asInt()).as("%s: %s", entry.getKey(), response).isEqualTo(-32602);
        }
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("task 가 8000자이면 맡긴다")
    void delegatesWhenTaskIs8000Chars() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        String root = McpCallSigner.newRoot();
        turn(userA, root);

        JsonNode started = started(delegate(sharedToken, root, GROUP_CODE, "가".repeat(8_000)));

        assertThat(started.path("status").asString()).as("응답: %s", started).isEqualTo("RUNNING");
        awaitFinished(started.path("execution_id").asLong());
    }

    @Test
    @DisplayName("없는 에이전트와 남의 비공개 에이전트는 같은 응답이고 꺼진 에이전트는 AGENT DISABLED 다")
    void missingAndOthersPrivateAgentGiveSameResponseAndDisabledIsDisabled() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);

        HttpResponse<String> missing = delegate(sharedToken, root, "tools-missing", "일");
        HttpResponse<String> othersPrivate = delegate(sharedToken, root, OWN_B_CODE, "일");
        HttpResponse<String> disabled = delegate(sharedToken, root, OFF_CODE, "일");

        assertFailure(missing, "AGENT_UNAVAILABLE");
        assertThat(body(othersPrivate)).isEqualTo(body(missing));
        assertFailure(disabled, "AGENT_DISABLED");
        assertThat(executions.findByRootExecutionId(parent.id())).isEmpty();
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("깊이 2 인 부모에서는 DEPTH EXCEEDED 이고 깊이 1 에서는 맡긴다")
    void depthExceededAtParentDepth2AndDelegatesAtDepth1() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        AgentExecution top = turn(userA, McpCallSigner.newRoot());
        AgentExecution depthOne = delegated(userA.id(), top, ExecutionStatus.SUCCEEDED, "답", null);
        String deepRoot = McpCallSigner.newRoot();
        running(depthOne, deepRoot);
        String shallowRoot = McpCallSigner.newRoot();
        AgentExecution shallow = running(top, shallowRoot);

        assertFailure(delegate(sharedToken, deepRoot, GROUP_CODE, "더 맡긴다"), "DEPTH_EXCEEDED");
        JsonNode started = started(delegate(sharedToken, shallowRoot, GROUP_CODE, "맡긴다"));

        AgentExecution child = awaitFinished(started.path("execution_id").asLong());
        assertThat(child.parentExecutionId()).isEqualTo(shallow.id());
        assertThat(child.rootExecutionId()).isEqualTo(top.id());
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("같은 뿌리에서 위임 넷이 돌면 다섯째는 TOO MANY CHILDREN 이고 위임이 아닌 자식은 세지 않는다")
    void fifthDelegationUnderSameRootIsTooManyChildren() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);
        for (int i = 0; i < 3; i++) {
            delegated(userA.id(), parent, ExecutionStatus.RUNNING, null, null);
        }
        delegated(userA.id(), parent, ExecutionStatus.SUCCEEDED, "끝난 위임", null);
        child(userA.id(), parent, ExecutionStatus.RUNNING);

        JsonNode fourth = started(delegate(sharedToken, root, GROUP_CODE, "넷째"));
        assertThat(fourth.path("status").asString()).as("넷째: %s", fourth).isEqualTo("RUNNING");
        awaitFinished(fourth.path("execution_id").asLong());
        delegated(userA.id(), parent, ExecutionStatus.RUNNING, null, null);

        assertFailure(delegate(sharedToken, root, GROUP_CODE, "다섯째"), "TOO_MANY_CHILDREN");
        assertThat(stub().received()).hasSize(1);
    }

    @Test
    @DisplayName("같은 tool call id 로 두 번 부르면 실행이 하나이고 다른 session 의 같은 id 는 따로 만든다")
    void sameToolCallIdMakesOneRunAndOtherSessionsSameIdIsSeparate() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(SHARED, root, root, subagent);
        String toolCallId = "call_" + UUID.randomUUID();

        JsonNode first = started(delegateCall(sharedToken, root, root, toolCallId, delegateArguments(GROUP_CODE, "한 번")));
        awaitFinished(first.path("execution_id").asLong());
        JsonNode again = started(delegateCall(sharedToken, root, root, toolCallId, delegateArguments(GROUP_CODE, "한 번")));
        JsonNode otherSession = started(delegateCall(sharedToken, root, subagent, toolCallId, delegateArguments(GROUP_CODE, "한 번")));
        awaitFinished(otherSession.path("execution_id").asLong());

        assertThat(again.path("execution_id").asLong()).isEqualTo(first.path("execution_id").asLong());
        assertThat(otherSession.path("execution_id").asLong()).isNotEqualTo(first.path("execution_id").asLong());
        assertThat(executions.findByRootExecutionId(parent.id())).hasSize(2);
        assertThat(stub().received()).hasSize(2);
    }

    @Test
    @DisplayName("제출이 실패하면 실행 줄이 FAILED 로 남고 SUBMIT FAILED 다")
    void failedSubmitLeavesRunRowFailedAndSubmitFailed() throws Exception {
        stub().willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);
        String toolCallId = "call_" + UUID.randomUUID();

        HttpResponse<String> response = delegateCall(sharedToken, root, root, toolCallId, delegateArguments(GROUP_CODE, "일"));

        assertFailure(response, "SUBMIT_FAILED");
        assertThat(response.body()).doesNotContain("down", "HERMES_UNAVAILABLE");
        AgentExecution child = executions.findByDelegationKey(DelegationKey.of(SHARED, root, root, toolCallId).value()).orElseThrow();
        assertThat(awaitFinished(child.id()).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(child.parentExecutionId()).isEqualTo(parent.id());
    }

    @Test
    @DisplayName("부모 실행에 대화가 없으면 실행 줄을 만들지 않고 SUBMIT FAILED 다")
    void parentRunWithoutConversationCreatesNoRunRowAndSubmitFailed() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);

        assertFailure(delegate(sharedToken, root, GROUP_CODE, "일"), "SUBMIT_FAILED");

        assertThat(executions.findByRootExecutionId(parent.id())).isEmpty();
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("서명 없는 agent delegate 와 다른 profile 의 토큰으로 서명한 호출은 거절한다")
    void rejectsUnsignedAgentDelegateAndCallSignedWithOtherProfileToken() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);

        assertInvalidContext(send(sharedToken, toolCall("agent_delegate", delegateArguments(GROUP_CODE, "일"))));
        assertInvalidContext(send(sharedToken, toolCall("agent_delegate",
                withContext(delegateArguments(GROUP_CODE, "일"), McpCallSigner.context(privateAToken, "agent_delegate", root)))));
        assertInvalidContext(delegate(privateAToken, root, GROUP_CODE, "일"));

        assertThat(executions.findByRootExecutionId(parent.id())).isEmpty();
        assertThat(stub().received()).isEmpty();
    }

    @Test
    @DisplayName("도구 목록에 agent stop 의 규격이 있다")
    void toolListHasAgentStopSpec() throws Exception {
        JsonNode listed = body(send(sharedToken, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).path("result").path("tools");
        JsonNode stop = null;
        for (JsonNode tool : listed) {
            if ("agent_stop".equals(tool.path("name").asString())) stop = tool;
        }

        assertThat(stop).as("도구 목록: %s", listed).isNotNull();
        JsonNode schema = stop.path("inputSchema");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("execution_id").path("type").asString()).isEqualTo("integer");
        assertThat(schema.path("required").get(0).asString()).isEqualTo("execution_id");
        assertThat(stop.path("description").asString()).contains("agent_delegate", "agent_status");
    }

    @Test
    @DisplayName("agent stop 은 도는 위임 실행을 멈추고 CANCELLED 를 준다")
    void agentStopStopsRunningDelegatedRunAndReturnsCancelled() throws Exception {
        stub().willAnswer(command -> answered(command, "멈춘 자리까지"));
        holdUntilStopped();
        String root = McpCallSigner.newRoot();
        turn(userA, root);
        long executionId = started(delegate(sharedToken, root, GROUP_CODE, "멈출 일")).path("execution_id").asLong();
        String runId = executions.findById(executionId).orElseThrow().hermesRunId();

        assertStatus(agentStop(sharedToken, root, executionId),
                "{\"execution_id\":" + executionId + ",\"status\":\"CANCELLED\",\"output\":\"멈춘 자리까지\"}");
        assertThat(stub().stopped()).containsExactly(runId);
        assertThat(awaitFinished(executionId).status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertStatus(agentStatus(sharedToken, root, executionId),
                "{\"execution_id\":" + executionId + ",\"status\":\"CANCELLED\",\"output\":\"멈춘 자리까지\"}");
    }

    @Test
    @DisplayName("끝난 실행의 agent stop 은 agent status 와 같은 결과이고 Hermes 에 보내지 않는다")
    void agentStopOfEndedRunEqualsAgentStatusAndDoesNotSendToHermes() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        AgentExecution succeeded = delegated(userA.id(), parent, ExecutionStatus.SUCCEEDED, "끝난 답", null);
        AgentExecution failed = delegated(userA.id(), parent, ExecutionStatus.FAILED, null, "HERMES_RUN_FAILED");

        assertThat(resultText(agentStop(sharedToken, root, succeeded.id()))).isEqualTo(resultText(agentStatus(sharedToken, root, succeeded.id())));
        assertThat(resultText(agentStop(sharedToken, root, failed.id()))).isEqualTo(resultText(agentStatus(sharedToken, root, failed.id())));
        assertThat(stub().stopped()).isEmpty();
    }

    @Test
    @DisplayName("이 프로세스가 돌리지 않는 RUNNING 실행은 run 번호로 중지만 보내고 stop requested 를 준다")
    void runningRunOfOtherProcessOnlySendsStopByRunIdAndReturnsRequested() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        AgentExecution orphan = delegated(userA.id(), parent, ExecutionStatus.RUNNING, null, null);
        Long agentId = agents.findByCode(GROUP_CODE).orElseThrow().id();
        jdbc.update("UPDATE agent_execution SET hermes_run_id = ?, agent_id = ? WHERE id = ?", "run-orphan-1", agentId, orphan.id());

        assertStatus(agentStop(sharedToken, root, orphan.id()),
                "{\"execution_id\":" + orphan.id() + ",\"status\":\"RUNNING\",\"stop_requested\":true}");
        assertThat(stub().stopped()).containsExactly("run-orphan-1");
    }

    @Test
    @DisplayName("이 프로세스가 돌리지 않는 RUNNING 실행에 run 번호가 없으면 아무것도 보내지 않고 stop requested 를 주지 않는다")
    void runningRunOfOtherProcessWithoutRunIdSendsNothing() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        AgentExecution orphan = delegated(userA.id(), parent, ExecutionStatus.RUNNING, null, null);

        assertStatus(agentStop(sharedToken, root, orphan.id()),
                "{\"execution_id\":" + orphan.id() + ",\"status\":\"RUNNING\"}");
        assertThat(stub().stopped()).isEmpty();
    }

    @Test
    @DisplayName("남의 실행과 다른 대화의 실행과 위임이 아닌 실행의 agent stop 은 없는 번호와 같은 응답이다")
    void agentStopOfOthersOrNonDelegatedRunGivesMissingIdResponse() throws Exception {
        AgentExecution firstTurn = McpCallSigner.save(executions, userA.id(), CONVERSATION, SHARED, McpCallSigner.newRoot(),
                ExecutionStatus.SUCCEEDED);
        String root = McpCallSigner.newRoot();
        McpCallSigner.running(executions, userA.id(), CONVERSATION, SHARED, root);
        AgentExecution otherConversationTurn = McpCallSigner.save(executions, userA.id(), OTHER_CONVERSATION, SHARED,
                McpCallSigner.newRoot(), ExecutionStatus.SUCCEEDED);
        AgentExecution otherConversation = delegated(userA.id(), otherConversationTurn, ExecutionStatus.RUNNING, null, null);
        AgentExecution proposal = child(userA.id(), firstTurn, ExecutionStatus.RUNNING);
        AgentExecution otherUserTurn = McpCallSigner.save(executions, userB.id(), CONVERSATION, SHARED, McpCallSigner.newRoot(),
                ExecutionStatus.SUCCEEDED);
        AgentExecution otherUsers = delegated(userB.id(), otherUserTurn, ExecutionStatus.RUNNING, null, null);
        for (AgentExecution target : List.of(otherConversation, proposal, otherUsers)) {
            jdbc.update("UPDATE agent_execution SET hermes_run_id = ? WHERE id = ?", "run-hidden-" + target.id(), target.id());
        }

        JsonNode missing = body(agentStop(sharedToken, root, 999_999_999L)).path("result");
        Map<String, JsonNode> hidden = new LinkedHashMap<>();
        hidden.put("같은 사용자의 다른 대화의 위임 실행", body(agentStop(sharedToken, root, otherConversation.id())).path("result"));
        hidden.put("같은 대화의 위임이 아닌 자식", body(agentStop(sharedToken, root, proposal.id())).path("result"));
        hidden.put("같은 대화의 앞 turn", body(agentStop(sharedToken, root, firstTurn.id())).path("result"));
        hidden.put("같은 대화 번호의 남의 위임 실행", body(agentStop(sharedToken, root, otherUsers.id())).path("result"));

        JsonNode failure = json.readTree(missing.path("content").get(0).path("text").asString());
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(failure.path("code").asString()).isEqualTo("NOT_FOUND");
        hidden.forEach((reason, result) -> assertThat(result).as(reason).isEqualTo(missing));
        assertThat(stub().stopped()).isEmpty();
    }

    @Test
    @DisplayName("agent stop 의 인자가 정수 번호 하나가 아니면 인자 오류이고 서명이 틀리면 호출 맥락 오류다")
    void agentStopArgIsArgumentErrorAndBadSignatureIsCallContextError() throws Exception {
        String root = McpCallSigner.newRoot();
        AgentExecution parent = McpCallSigner.running(executions, userA.id(), null, SHARED, root);
        AgentExecution running = delegated(userA.id(), parent, ExecutionStatus.RUNNING, null, null);

        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(json.createObjectNode());
        invalid.add(json.createObjectNode().put("execution_id", "12"));
        invalid.add(json.createObjectNode().put("execution_id", 1.5));
        invalid.add(json.createObjectNode().put("execution_id", running.id()).put("user_id", userB.id()));
        for (ObjectNode arguments : invalid) {
            arguments.set("_fos_ctx", McpCallSigner.context(sharedToken, "agent_stop", root));
            JsonNode response = body(send(sharedToken, toolCall("agent_stop", arguments)));
            assertThat(response.path("error").path("code").asInt()).as("인자 %s: %s", arguments, response).isEqualTo(-32602);
        }
        ObjectNode wrongTool = json.createObjectNode().put("execution_id", running.id());
        wrongTool.set("_fos_ctx", McpCallSigner.context(sharedToken, "agent_status", root));
        assertInvalidContext(send(sharedToken, toolCall("agent_stop", wrongTool)));
        assertInvalidContext(agentStop(privateAToken, root, running.id()));
        assertThat(stub().stopped()).isEmpty();
    }

    @Test
    @DisplayName("멈춘 위임 실행이 origin 인 하위 에이전트의 호출은 호출 맥락 오류다")
    void callFromSubagentWhoseOriginIsStoppedDelegatedRunIsCallContextError() throws Exception {
        stub().willAnswer(McpAgentToolsTest::completed);
        holdUntilStopped();
        String groupToken = tokens.issue(profileOf(GROUP_CODE), "group").rawToken();
        String root = McpCallSigner.newRoot();
        AgentExecution parent = turn(userA, root);
        long executionId = started(delegate(sharedToken, root, GROUP_CODE, "하위 에이전트를 띄울 일")).path("execution_id").asLong();
        String childSession = executions.findById(executionId).orElseThrow().hermesSessionId();
        String subagent = "하위-" + UUID.randomUUID();
        registrar.register(profileOf(GROUP_CODE), childSession, childSession, subagent);
        HttpResponse<String> before = send(groupToken, toolCall("agent_list", withContext(json.createObjectNode(),
                McpCallSigner.context(groupToken, "agent_list", childSession, subagent, "call_" + UUID.randomUUID()))));
        assertThat(codes(json.readTree(resultText(before)))).as("멈추기 전에는 origin 사용자의 목록을 받는다").contains(OWN_A_CODE);

        assertThat(json.readTree(resultText(agentStop(sharedToken, root, executionId))).path("status").asString()).isEqualTo("CANCELLED");
        awaitFinished(executionId);

        assertInvalidContext(send(groupToken, toolCall("agent_list", withContext(json.createObjectNode(),
                McpCallSigner.context(groupToken, "agent_list", childSession, subagent, "call_" + UUID.randomUUID())))));
        assertThat(executions.findById(parent.id()).orElseThrow().status()).as("멈춘 것은 위임 실행 하나다").isEqualTo(ExecutionStatus.RUNNING);
    }

    /**
     * 완료를 기다리는 자리에서 중지가 올 때까지 멈춰 둔다. 실제 Hermes 에서 도는 run 과 같다.
     *
     * <p>중지가 오지 않아도 10초 뒤에는 이어져 실행 스레드가 검사보다 오래 살지 않는다.
     */
    private void holdUntilStopped() {
        CountDownLatch stopReceived = new CountDownLatch(1);
        stub().onStop(runId -> stopReceived.countDown());
        stub().beforeAwait(() -> {
            try {
                stopReceived.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static HermesRunResult answered(HermesRunCommand command, String output) {
        return HermesRunResult.of("run-" + UUID.randomUUID(), command.sessionId(), "completed", output,
                "example-model", "example-provider", new TokenUsage(3L, 0L, 2L, 5L));
    }

    private HttpResponse<String> agentStop(String token, String root, Long executionId) throws Exception {
        ObjectNode arguments = json.createObjectNode();
        arguments.put("execution_id", executionId);
        return send(token, toolCall("agent_stop", withContext(arguments, McpCallSigner.context(token, "agent_stop", root))));
    }

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    /** 받은 지시를 답에 담아 끝낸다. run 번호가 명령마다 달라 나란히 돌아도 섞이지 않는다. */
    private static HermesRunResult completed(HermesRunCommand command) {
        return HermesRunResult.of("run-" + UUID.randomUUID(), command.sessionId(), "completed", "맡은 일의 답: " + command.input(),
                "example-model", "example-provider", new TokenUsage(3L, 0L, 2L, 5L));
    }

    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    /** 대화 줄 하나와 그 대화에서 뿌리 session {@code root} 로 도는 turn 실행을 만든다. */
    private AgentExecution turn(AppUser user, String root) {
        Long agentId = agents.findByCode(GROUP_CODE).orElseThrow().id();
        Conversation conversation = conversations.save(Conversation.startedBy(user.id(), "맡기기", agentId));
        return McpCallSigner.running(executions, user.id(), conversation.id(), SHARED, root);
    }

    /** {@code parent} 아래에서 뿌리 session {@code session} 으로 도는 실행이다. 깊이를 만들 때 쓴다. */
    private AgentExecution running(AgentExecution parent, String session) {
        return executions.save(AgentExecution.builder()
                .userId(parent.userId())
                .conversationId(parent.conversationId())
                .parentExecutionId(parent.id())
                .rootExecutionId(parent.treeRootId())
                .profileName(SHARED)
                .hermesSessionId(session)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(STARTED)
                .build());
    }

    private ObjectNode delegateArguments(String agentCode, String task) {
        return json.createObjectNode().put("agent_code", agentCode).put("task", task);
    }

    /** 뿌리 session 에서 새 {@code tool_call_id} 로 부른다. */
    private HttpResponse<String> delegate(String token, String root, String agentCode, String task) throws Exception {
        return delegateCall(token, root, root, "call_" + UUID.randomUUID(), delegateArguments(agentCode, task));
    }

    private HttpResponse<String> delegateCall(String token, String root, String session, String toolCallId, ObjectNode arguments)
            throws Exception {
        return send(token, toolCall("agent_delegate",
                withContext(arguments, McpCallSigner.context(token, "agent_delegate", root, session, toolCallId))));
    }

    /** 성공한 도구 결과의 글을 JSON 으로 읽는다. */
    private JsonNode started(HttpResponse<String> response) {
        return json.readTree(resultText(response));
    }

    private void assertFailure(HttpResponse<String> response, String code) {
        JsonNode result = body(response).path("result");
        assertThat(result.path("isError").asBoolean()).as("실패 결과: %s", result).isTrue();
        JsonNode failure = json.readTree(result.path("content").get(0).path("text").asString());
        assertThat(failure.propertyNames()).containsExactly("code", "message");
        assertThat(failure.path("code").asString()).as("실패 결과: %s", failure).isEqualTo(code);
    }

    /**
     * 위임 실행이 끝나고 끝난 사건까지 적힐 때까지 기다린다.
     *
     * <p>위임은 가상 스레드에서 돈다. 다음 검사의 {@code @BeforeEach} 가 사용자와 실행 줄을 지우기 전에 끝나야 한다.
     */
    private AgentExecution awaitFinished(Long executionId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            AgentExecution execution = executions.findById(executionId).orElseThrow();
            boolean ended = executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(executionId)).stream()
                    .anyMatch(event -> event.eventType() != ExecutionEventType.RUN_STARTED);
            if (execution.status() != ExecutionStatus.RUNNING && ended) return execution;
            if (System.nanoTime() > deadline) {
                throw new AssertionError("실행 " + executionId + " 이 10초 안에 끝나지 않았다. 상태: " + execution.status());
            }
            Thread.sleep(10);
        }
    }

    private static Agent agent(String code, String name, AgentVisibility visibility, Long ownerUserId) {
        return Agent.of(code, name, profileOf(code), "http://agent-runtime.test/p/" + code,
                CostMode.API, CredentialScope.DEDICATED, visibility, ownerUserId);
    }

    private static String profileOf(String code) {
        return "profile-of-" + code;
    }

    /** {@code agent_delegate} 로 만든 것처럼 {@code delegation_key} 가 있는 자식 실행을 만든다. 대화는 부모의 것을 잇는다. */
    private AgentExecution delegated(Long userId, AgentExecution parent, ExecutionStatus status, String output, String errorCode) {
        AgentExecution execution = executions.save(AgentExecution.builder()
                .userId(userId)
                .conversationId(parent.conversationId())
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

    /** Memory 제안처럼 위임이 아닌 자식 실행이다. 대화는 부모의 것을 잇는다. */
    private AgentExecution child(Long userId, AgentExecution parent, ExecutionStatus status) {
        return executions.save(AgentExecution.builder()
                .userId(userId)
                .conversationId(parent.conversationId())
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
