package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.NewFollowUp;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpMemoryRemember;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.execution.application.ExecutionQuestions;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 사용자가 직접 시킨 할 일을 바로 만들고 끝내는 계약을 확인한다(스펙 service-concepts REQ11, REQ12, REQ17, REQ18).
 * 계약은 {@code docs/features/service-concepts/design.md} 의 「PR3 할 일과 기억 도구」 가 갖는다.
 */
@BackendIntegrationTest
class McpFollowUpDirectToolTest {
    private static final String PROFILE = "mcp-follow-up-direct";
    private static final String PROPOSE = "follow_up_propose";
    private static final String DONE = "follow_up_done";
    private static final String TITLE = "병원 예약 4471";
    private static final String OPENED = "할 일로 적었다. 바로 챙긴다. 답에 /now 링크를 붙인다.";

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository events;

    @Autowired
    ExecutionQuestions questions;

    @Autowired
    ExecutionDeliveryWriter deliveryWriter;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    FollowUpRepository followUps;

    @Autowired
    FollowUpService followUpService;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> createdUsers = new ArrayList<>();
    private AppUser dad;
    private Agent agent;
    private String token;
    private String root;
    private Conversation conversation;
    private AgentExecution run;


    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        dad = user("아빠");
        token = tokens.issue(PROFILE, "follow-up-direct").rawToken();
        agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "할 일 대화", agent.id(), Instant.now()));
        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root);
    }


    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
        }
        createdUsers.clear();
        jdbc.update(
            "DELETE FROM execution_event WHERE execution_id IN (SELECT id FROM agent_execution WHERE profile_name = ?)", PROFILE);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
    }


    @Test
    @DisplayName("사람이 보낸 메시지가 시킨 할 일은 바로 챙기는 상태로 요청자의 것이 된다")
    void opensFollowUpRequestedByHumanTurn() throws Exception {
        askedByHuman();

        JsonNode result = call(PROPOSE, requested(TITLE));

        assertThat(text(result)).isEqualTo(OPENED);
        assertThat(isError(result)).isFalse();
        FollowUp row = onlyRow();
        assertThat(row.status()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(row.userId()).isEqualTo(dad.id());
        assertThat(row.conversationId()).isEqualTo(conversation.id());
        assertThat(followUpService.list(current(dad))).extracting(snapshot -> snapshot.title()).containsExactly(TITLE);
    }


    @Test
    @DisplayName("시켰다는 표시가 없으면 사람이 보낸 메시지에서도 제안으로 남는다")
    void proposesWhenNotRequested() throws Exception {
        askedByHuman();

        call(PROPOSE, json.createObjectNode().put("title", TITLE));

        assertThat(onlyRow().status()).isEqualTo(FollowUpStatus.PROPOSED);
    }


    @Test
    @DisplayName("같은 제목의 제안이 있으면 새로 만들지 않고 그 제안을 받아들인다")
    void acceptsExistingProposal() throws Exception {
        askedByHuman();
        call(PROPOSE, json.createObjectNode().put("title", TITLE));

        JsonNode result = call(PROPOSE, requested(TITLE));

        assertThat(text(result)).isEqualTo(OPENED);
        assertThat(onlyRow().status()).isEqualTo(FollowUpStatus.OPEN);
    }


    @Test
    @DisplayName("사람이 보낸 메시지의 대화가 바깥 글을 읽었으면 시킨 할 일도 제안으로 남는다")
    void proposesWhenConversationReadOutsideText() throws Exception {
        askedByHuman();
        toolStarted(run.id(), "web_search");

        JsonNode result = call(PROPOSE, requested(TITLE));

        assertThat(isError(result)).isFalse();
        assertThat(onlyRow().status()).isEqualTo(FollowUpStatus.PROPOSED);
    }


    @Test
    @DisplayName("예약 작업의 실행이 바깥 글을 읽고 만든 지시문의 할 일은 바로 챙기는 상태다")
    void opensFollowUpRequestedByScheduledRun() throws Exception {
        deliveryWriter.markUnattended(run.id());
        toolStarted(run.id(), "mcp__gmail__search");

        JsonNode result = call(PROPOSE, requested(TITLE));

        assertThat(text(result)).isEqualTo(OPENED);
        assertThat(onlyRow().status()).isEqualTo(FollowUpStatus.OPEN);
    }


    @Test
    @DisplayName("사람의 질문도 예약 작업도 아닌 실행과 맡긴 실행의 할 일은 제안으로 남는다")
    void proposesFromOtherRuns() throws Exception {
        call(PROPOSE, requested(TITLE));
        assertThat(onlyRow().status()).isEqualTo(FollowUpStatus.PROPOSED);

        String childRoot = McpCallSigner.newRoot();
        AgentExecution child = executions.save(AgentExecution.builder()
            .userId(dad.id())
            .agentId(agent.id())
            .conversationId(conversation.id())
            .parentExecutionId(run.id())
            .rootExecutionId(run.id())
            .profileName(PROFILE)
            .hermesSessionId(childRoot)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.now())
            .build());
        deliveryWriter.markUnattended(child.id());

        call(PROPOSE, requested("맡긴 실행의 할 일 4471"), childRoot);

        assertThat(rows()).extracting(FollowUp::status).containsOnly(FollowUpStatus.PROPOSED);
    }


    @Test
    @DisplayName("먼저 살펴보기 트리에서는 시켰다고 해도 제안으로 남고, 끝내기는 쓰기를 켠 살펴보기에서도 상태를 바꾸지 않는다")
    void checkTreeNeverChangesDirectly() throws Exception {
        followUpService.create(current(dad), new NewFollowUp("이미 챙기는 일 4471", null, false, null));
        deliveryWriter.markUnattended(run.id());
        for (boolean writesAllowed : List.of(false, true)) {
            ProactiveCheck check = ProactiveCheck.started(
                dad.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, writesAllowed, Instant.now());
            check.attachRoot(run.id(), root);
            ProactiveCheck saved = checks.save(check);
            try {
                call(PROPOSE, requested(TITLE + writesAllowed));
                JsonNode done = call(DONE, json.createObjectNode().put("follow_up", "이미 챙기는 일 4471"));

                assertThat(isError(done)).isTrue();
                assertThat(text(done)).contains(writesAllowed ? "not_directed" : "먼저 살펴보기에서는 쓸 수 없는 도구");
            } finally {
                checks.delete(saved);
            }
        }
        assertThat(rows()).extracting(FollowUp::status)
            .containsExactly(FollowUpStatus.OPEN, FollowUpStatus.PROPOSED, FollowUpStatus.PROPOSED);
    }


    @Test
    @DisplayName("사람이 끝냈다고 한 할 일은 끝난 상태가 되어 목록에서 빠진다")
    void finishesFollowUp() throws Exception {
        askedByHuman();
        call(PROPOSE, requested(TITLE));
        JsonNode listed = call("follow_up_list", json.createObjectNode());
        assertThat(text(listed)).contains(TITLE, onlyRow().publicId().toString());

        JsonNode result = call(DONE, json.createObjectNode().put("follow_up", "병원 예약"));

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(text(result)).contains("\"status\":\"done\"", "/now");
        assertThat(onlyRow().status()).isEqualTo(FollowUpStatus.DONE);
        assertThat(followUpService.list(current(dad))).isEmpty();
        assertThat(text(call("follow_up_list", json.createObjectNode()))).doesNotContain(TITLE);
    }


    @Test
    @DisplayName("맞는 할 일이 둘이면 후보를 돌려주고 바꾸지 않으며, 번호로 다시 부르면 그것만 끝낸다")
    void returnsCandidatesWhenAmbiguous() throws Exception {
        askedByHuman();
        call(PROPOSE, requested("병원 예약 4471 치과"));
        call(PROPOSE, requested("병원 예약 4471 안과"));

        JsonNode ambiguous = call(DONE, json.createObjectNode().put("follow_up", "병원 예약 4471"));

        assertThat(isError(ambiguous)).isTrue();
        assertThat(text(ambiguous)).contains("ambiguous", "치과", "안과");
        assertThat(rows()).extracting(FollowUp::status).containsOnly(FollowUpStatus.OPEN);

        UUID dental = rows().getFirst().publicId();
        JsonNode done = call(DONE, json.createObjectNode().put("follow_up", dental.toString()));

        assertThat(isError(done)).isFalse();
        assertThat(rows()).extracting(FollowUp::status).containsExactly(FollowUpStatus.DONE, FollowUpStatus.OPEN);
    }


    @Test
    @DisplayName("다른 사용자의 할 일 번호는 없는 할 일과 같은 결과이고 그 할 일은 그대로다")
    void treatsOthersFollowUpAsMissing() throws Exception {
        askedByHuman();
        AppUser mom = user("엄마");
        UUID moms = followUpService.create(current(mom), new NewFollowUp("엄마의 일 4471", null, false, null)).publicId();

        JsonNode others = call(DONE, json.createObjectNode().put("follow_up", moms.toString()));
        JsonNode missing = call(DONE, json.createObjectNode().put("follow_up", UUID.randomUUID().toString()));

        assertThat(isError(others)).isTrue();
        assertThat(text(others)).contains("not_found").isEqualTo(text(missing));
        assertThat(followUpService.list(current(mom))).hasSize(1);
        assertThat(text(call("follow_up_list", json.createObjectNode()))).doesNotContain("엄마의 일");
    }


    @Test
    @DisplayName("사람이 시키지 않은 실행은 끝내지 않고 사용자에게 물으라는 실패 결과를 주며, 스키마 밖 인자는 인자 오류다")
    void refusesFinishOutsideDirectedRun() throws Exception {
        followUpService.create(current(dad), new NewFollowUp(TITLE, null, false, null));

        JsonNode result = call(DONE, json.createObjectNode().put("follow_up", TITLE));

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).contains("not_directed", "끝냈다고 말하지 않고");
        assertThat(onlyRow().status()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(call(DONE, json.createObjectNode()).has("error")).isTrue();
        assertThat(call(DONE, json.createObjectNode().put("follow_up", TITLE).put("user_id", 1)).has("error")).isTrue();
        assertThat(call("follow_up_list", json.createObjectNode().put("status", "DONE")).has("error")).isTrue();
        assertThat(call(PROPOSE, requested(TITLE).put("requested", "yes")).has("error")).isTrue();
    }


    @Test
    @DisplayName("한 실행이 바로 적는 할 일은 상한까지이고, 넘으면 적지 않았다는 실패 결과다")
    void capsDirectFollowUpsPerRun() throws Exception {
        askedByHuman();
        for (int i = 0; i < 10; i++) {
            assertThat(isError(call(PROPOSE, requested(TITLE + " " + i)))).isFalse();
        }

        JsonNode over = call(PROPOSE, requested(TITLE + " 넘침"));

        assertThat(isError(over)).isTrue();
        assertThat(text(over)).contains("더 적지 않았다");
        assertThat(rows()).hasSize(10);
    }


    @Test
    @DisplayName("할 일 목록을 읽은 대화에서도 기억의 바로 저장 판정은 넓어지지 않고, 살펴보기는 목록을 읽는다")
    void listKeepsMemoryJudgmentAndWorksInCheck() throws Exception {
        assertThat(ReflectionTestUtils.getField(McpMemoryRemember.class, "INTERNAL_TOOLS"))
            .asInstanceOf(InstanceOfAssertFactories.collection(String.class))
            .doesNotContain("mcp__fos_assistant__follow_up_list", "mcp__fos_assistant__follow_up_done");
        ProactiveCheck check = ProactiveCheck.started(dad.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, false, Instant.now());
        check.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(check);
        try {
            assertThat(isError(call("follow_up_list", json.createObjectNode()))).isFalse();
        } finally {
            checks.delete(saved);
        }
    }


    private void askedByHuman() {
        ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "할 일로 적어 줘", Instant.now()));
        questions.link(run.id(), question.id());
    }


    private void toolStarted(Long executionId, String toolName) {
        events.save(ExecutionEvent.builder()
            .executionId(executionId)
            .sequence((int)events.count() + 1000)
            .eventType(ExecutionEventType.TOOL_STARTED)
            .toolName(toolName)
            .occurredAt(Instant.now())
            .build());
    }


    private AppUser user(String name) {
        AppUser saved = users.save(
            AppUser.of("mcp-follow-up-direct-" + UUID.randomUUID() + "@example.com", name, 1L, UserRole.MEMBER));
        createdUsers.add(saved.id());
        return saved;
    }


    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private ObjectNode requested(String title) {
        return json.createObjectNode().put("title", title).put("requested", true);
    }


    private JsonNode call(String tool, ObjectNode arguments) throws Exception {
        return call(tool, arguments, root);
    }


    private JsonNode call(String tool, ObjectNode arguments, String rootSession) throws Exception {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", tool).set("arguments", arguments);
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(McpCallSigner.withContext(request.toString(), token, rootSession)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        if (body.has("result")) {
            McpToolResultShape.assertCallToolResult(tool, body.path("result"));
        }
        return body;
    }


    private List<FollowUp> rows() {
        return followUps.findByUserIdAndStatusInOrderByIdAsc(dad.id(), List.of(FollowUpStatus.values()));
    }


    private FollowUp onlyRow() {
        List<FollowUp> rows = rows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }


    private static boolean isError(JsonNode result) {
        return result.path("result").path("isError").asBoolean();
    }


    private static String text(JsonNode result) {
        return result.path("result").path("content").get(0).path("text").asString();
    }
}
