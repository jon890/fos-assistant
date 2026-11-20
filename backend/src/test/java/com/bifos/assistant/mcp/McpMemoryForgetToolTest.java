package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 기억 지우기 승인 카드의 계약을 확인한다(스펙 service-concepts REQ13, REQ17, REQ18). 계약은
 * {@code docs/features/service-concepts/design.md} 의 「PR3 할 일과 기억 도구」 가 갖는다.
 */
@BackendIntegrationTest
class McpMemoryForgetToolTest {
    private static final String PROFILE = "mcp-memory-forget";
    private static final String TOOL = "memory_forget";

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository memoryRepository;

    @Autowired
    ConnectorActionService actionService;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private AppUser admin;
    private Agent agent;
    private String token;
    private String root;
    private Conversation conversation;
    private AgentExecution run;


    @BeforeEach
    void setUp() {
        clean();
        long groupId = 4471L;
        dad = users.save(AppUser.of("forget-dad-" + UUID.randomUUID() + "@example.com", "아빠", groupId, UserRole.MEMBER));
        admin = users.save(AppUser.of("forget-admin-" + UUID.randomUUID() + "@example.com", "관리자", groupId, UserRole.ADMIN));
        token = tokens.issue(PROFILE, "memory-forget").rawToken();
        agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "기억 대화", agent.id(), Instant.now()));
        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root);
    }


    @AfterEach
    void tearDown() {
        clean();
    }


    @Test
    @DisplayName("지우기 도구는 지우지 않고 내용이 보이는 승인 카드를 띄우며, 승인하면 기억이 사라져 다음 실행에 들지 않는다")
    void forgetsAfterApproval() throws Exception {
        Memory memory = memories.create(current(dad), MemoryScope.USER, "회사 이야기", "아빠는 4471 회사에 다닌다", true);

        JsonNode result = call(memory.id());

        assertThat(isError(result)).as(text(result)).isFalse();
        assertThat(text(result)).contains("approval_requested", "아직 지우지 않았다").doesNotContain("회사");
        assertThat(memoryRepository.findById(memory.id())).isPresent();
        ConnectorAction card = actions.findAll().getFirst();
        assertThat(ControlPlaneActions.owns(card)).isTrue();
        assertThat(card.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(card.conversationId()).isEqualTo(conversation.id());
        var view = actionService.listForConversation(current(dad), conversation.id()).getFirst();
        assertThat(view.title()).isEqualTo("기억 지우기");
        assertThat(view.argsJson()).contains("회사 이야기", "아빠는 4471 회사에 다닌다");
        assertThat(view.grantAllowed()).isFalse();

        var approved = actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(memoryRepository.findById(memory.id())).isEmpty();
        assertThat(memories.readableBy(current(dad))).isEmpty();
        assertThat(memories.alwaysInjectedFor(current(dad), memories.accessOf(agent.id()))).isEmpty();
        assertThat(actionService.undeliveredResults(conversation.id())).isEmpty();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
            .filteredOn(message -> message.role() == MessageRole.SYSTEM)
            .extracting(message -> message.content())
            .containsExactly("「회사 이야기」 기억을 지웠어요");
    }


    @Test
    @DisplayName("거절하면 기억은 그대로이고, 검토할 기억도 승인 카드로 지운다")
    void keepsMemoryWhenRejectedAndForgetsProposal() throws Exception {
        Memory kept = memories.create(current(dad), MemoryScope.USER, "남길 기억", "본문", false);
        Memory proposal = memories.proposeUser(current(dad), "검토할 기억", "제안 본문", run.id());

        call(kept.id());
        actionService.reject(current(dad), actions.findAll().getFirst().publicId());
        assertThat(memoryRepository.findById(kept.id())).isPresent();

        JsonNode result = call(proposal.id());
        assertThat(isError(result)).as(text(result)).isFalse();
        ConnectorAction card = actions.findAll().stream()
            .filter(action -> action.status() == ActionStatus.PENDING).findFirst().orElseThrow();
        actionService.approve(current(dad), card.publicId(), null, false);

        assertThat(memoryRepository.findById(proposal.id())).isEmpty();
        assertThat(memoryRepository.findById(kept.id())).isPresent();
    }


    @Test
    @DisplayName("MEMBER 역할 사용자가 그룹 기억을 지워 달라고 하면 카드 없이 까닭을 돌려준다")
    void refusesGroupMemoryForMember() throws Exception {
        Memory group = memories.create(current(admin), MemoryScope.GROUP, "그룹 규칙", "그룹 본문", false);

        JsonNode result = call(group.id());

        assertThat(isError(result)).isTrue();
        assertThat(text(result)).contains("forbidden", "관리자만").doesNotContain("그룹 규칙");
        assertThat(actions.findAll()).isEmpty();
        assertThat(memoryRepository.findById(group.id())).isPresent();
    }


    @Test
    @DisplayName("다른 사용자의 기억과 문서는 없는 기억과 같은 결과이고 카드가 없으며, 스키마 밖 인자는 인자 오류다")
    void treatsOthersMemoryAndDocumentAsMissing() throws Exception {
        Memory others = memories.create(current(admin), MemoryScope.USER, "관리자의 기억", "남의 본문", false);
        Memory document = memories.createDocument(current(dad), "core", "note", "문서", "문서 본문");

        JsonNode missing = call(999_999_999L);

        assertThat(isError(missing)).isTrue();
        assertThat(text(missing)).contains("not_found");
        assertThat(text(call(others.id()))).isEqualTo(text(missing));
        assertThat(text(call(document.id()))).isEqualTo(text(missing));
        assertThat(actions.findAll()).isEmpty();
        assertThat(send(json.createObjectNode()).has("error")).isTrue();
        assertThat(send(json.createObjectNode().put("memory_id", "7")).has("error")).isTrue();
        assertThat(send(json.createObjectNode().put("memory_id", 7).put("title", "회사")).has("error")).isTrue();
    }


    @Test
    @DisplayName("쓰기를 켜지 않은 먼저 살펴보기에서도 지우기 카드는 띄우고 기억은 그대로다")
    void checkTreeRaisesCardWithoutWrites() throws Exception {
        Memory memory = memories.create(current(dad), MemoryScope.USER, "살펴보기 기억", "본문", false);
        ProactiveCheck check = ProactiveCheck.started(dad.id(), agent.id(), conversation.id(), CheckTrigger.MANUAL, false, Instant.now());
        check.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(check);
        try {
            JsonNode result = call(memory.id());

            assertThat(isError(result)).as(text(result)).isFalse();
            assertThat(actions.findAll()).singleElement()
                .satisfies(card -> assertThat(card.status()).isEqualTo(ActionStatus.PENDING));
            assertThat(memoryRepository.findById(memory.id())).isPresent();
        } finally {
            checks.delete(saved);
        }
    }


    private JsonNode call(Long memoryId) throws Exception {
        return send(json.createObjectNode().put("memory_id", memoryId));
    }


    private JsonNode send(ObjectNode arguments) throws Exception {
        ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        request.putObject("params").put("name", TOOL).set("arguments", arguments);
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(McpCallSigner.withContext(request.toString(), token, root)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        if (body.has("result")) {
            McpToolResultShape.assertCallToolResult(TOOL, body.path("result"));
        }
        return body;
    }


    private void clean() {
        actions.deleteAll();
        memoryRepository.deleteAll();
        jdbc.update("DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE title = '기억 대화')");
        jdbc.update("DELETE FROM conversation WHERE title = '기억 대화'");
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
    }


    private static CurrentUser current(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private static boolean isError(JsonNode result) {
        return result.path("result").path("isError").asBoolean();
    }


    private static String text(JsonNode result) {
        return result.path("result").path("content").get(0).path("text").asString();
    }
}
