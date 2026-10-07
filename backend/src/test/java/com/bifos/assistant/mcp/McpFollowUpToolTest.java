package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 할 일 제안 도구 {@code follow_up_propose} 의 계약을 확인한다. 계약은 {@code docs/backend/follow-up.md} 의
 * 「제안 도구」 가 갖는다.
 *
 * <p>토큰은 이 검사의 profile 에 묶이고, 도구 호출은 그 profile 로 아빠의 대화에서 도는 실행 루트로 서명한다(ADR-032).
 */
@BackendIntegrationTest
class McpFollowUpToolTest {
    private static final String PROFILE = "mcp-follow-up-tool";
    private static final String TOOL = "follow_up_propose";
    private static final String TITLE = "할 일 검사 7391";
    private static final String CREATED = "할 일로 제안했다. 사용자가 지금 화면에서 받아들이면 챙긴다.";

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
    FollowUpRepository followUps;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    McpToolService toolService;

    @Autowired
    ProactiveCheckRepository checks;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> createdUsers = new ArrayList<>();
    private AppUser dad;
    private String dadToken;
    private String dadRoot;
    private Conversation conversation;
    private AgentExecution dadRun;

    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        String email = "mcp-follow-up-" + UUID.randomUUID() + "@example.com";
        dad = users.save(AppUser.of(email, "아빠", 1L, UserRole.ADMIN, Instant.now()));
        createdUsers.add(dad.id());
        dadToken = tokens.issue(PROFILE, "follow-up").rawToken();
        Agent agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "할 일 검사 대화", agent.id(), Instant.now()));
        dadRoot = McpCallSigner.newRoot();
        dadRun = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, dadRoot);
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
        }
        createdUsers.clear();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
    }

    @Test
    @DisplayName("도구 목록의 일곱째가 follow_up_propose 이고 title 만 필수이며 모르는 키를 받지 않는다")
    void listsFollowUpProposeSeventh() throws Exception {
        JsonNode listed = body(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));

        JsonNode tools = listed.path("result").path("tools");
        assertThat(tools).hasSize(8);
        JsonNode tool = tools.get(6);
        assertThat(tool.path("name").asString()).isEqualTo(TOOL);
        JsonNode schema = tool.path("inputSchema");
        assertThat(schema.path("required").toString()).isEqualTo("[\"title\"]");
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("title").path("maxLength").asInt())
                .isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} 은 {1} 로 저장한다")
    @DisplayName("기한 글을 모양마다 시각으로 읽는다. 날짜만이면 그날 23시 59분, 시간대가 없으면 서울이다")
    @CsvSource({
        "2026-10-05, 2026-10-05T14:59:00Z",
        "2026-10-05T18:00, 2026-10-05T09:00:00Z",
        "2026-10-05T18:00:00, 2026-10-05T09:00:00Z",
        "2026-10-05T18:00+09:00, 2026-10-05T09:00:00Z",
        "2026-10-05T09:00:00Z, 2026-10-05T09:00:00Z"
    })
    void storesDueAtReadFromEachForm(String dueAt, String stored) throws Exception {
        JsonNode result = propose(arguments().put("due_at", dueAt));

        assertThat(text(result)).as("결과: %s", result).isEqualTo(CREATED);
        assertThat(result.path("isError").asBoolean()).isFalse();
        FollowUp row = onlyRow();
        assertThat(row.dueAt()).isEqualTo(Instant.parse(stored));
        assertThat(row.status()).isEqualTo(FollowUpStatus.PROPOSED);
        assertThat(row.conversationId()).isEqualTo(conversation.id());
        assertThat(row.userId()).isEqualTo(dad.id());
    }

    @Test
    @DisplayName("선택 인자의 null 은 없는 것으로 보아 기한 없이 기다리는 중이 아닌 할 일로 제안한다")
    void treatsNullOptionalArgumentsAsAbsent() throws Exception {
        JsonNode result = propose(arguments().putNull("due_at").putNull("waiting"));

        assertThat(text(result)).as("결과: %s", result).isEqualTo(CREATED);
        FollowUp row = onlyRow();
        assertThat(row.dueAt()).isNull();
        assertThat(row.waiting()).isFalse();
    }

    @Test
    @DisplayName("같은 제목을 다시 제안하면 오류가 아닌 같은 할 일이 있다는 글이다")
    void answersDuplicateWithoutError() throws Exception {
        propose(arguments().put("waiting", true));

        JsonNode again = propose(arguments());

        assertThat(again.path("isError").asBoolean()).isFalse();
        assertThat(text(again)).isEqualTo("같은 할 일이 이미 있다. 새로 만들지 않았다.");
        assertThat(onlyRow().waiting()).isTrue();
    }

    @Test
    @DisplayName("fos ctx 없이 부르면 요청자를 정하지 못한 결과이고 줄이 생기지 않는다")
    void rejectsCallWithoutCallContext() throws Exception {
        ObjectNode request = toolCall(arguments());

        JsonNode rejected = body(send(json.writeValueAsString(request)));

        assertThat(rejected.path("result")).isEqualTo(json.valueToTree(toolService.invalidContext()));
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("모르는 키나 title 이 없거나 문자열이 아닌 인자는 JSON-RPC 인자 오류이고 줄이 생기지 않는다")
    void rejectsMalformedArgumentsAsInvalidParams() throws Exception {
        List<ObjectNode> malformed = List.of(
                arguments().put("user_id", 1),
                json.createObjectNode(),
                json.createObjectNode().putNull("title"),
                json.createObjectNode().put("title", 7391),
                arguments().put("due_at", 20261005),
                arguments().put("waiting", "true"));

        for (ObjectNode arguments : malformed) {
            JsonNode response = body(send(signed(arguments)));
            assertThat(response.path("error").path("code").asInt())
                    .as("인자 %s 의 응답: %s", arguments, response)
                    .isEqualTo(-32602);
        }
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("공백뿐이거나 200자를 넘는 제목은 도구 오류 글이고 줄이 생기지 않는다")
    void rejectsBlankOrTooLongTitleAsToolError() throws Exception {
        JsonNode blank = propose(json.createObjectNode().put("title", "   "));
        JsonNode tooLong = propose(json.createObjectNode().put("title", "가".repeat(201)));

        for (JsonNode result : List.of(blank, tooLong)) {
            assertThat(result.path("isError").asBoolean()).as("결과: %s", result).isTrue();
            assertThat(text(result)).isEqualTo("title 은 1자부터 200자까지다.");
        }
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("200자 제목은 받는다")
    void acceptsTitleOfMaxLength() throws Exception {
        JsonNode result = propose(json.createObjectNode().put("title", "가".repeat(200)));

        assertThat(text(result)).as("결과: %s", result).isEqualTo(CREATED);
        assertThat(onlyRow().title()).hasSize(200);
    }

    @Test
    @DisplayName("읽지 못하는 기한은 도구 오류 글이고 줄이 생기지 않는다")
    void rejectsUnreadableDueAtAsToolError() throws Exception {
        JsonNode result = propose(arguments().put("due_at", "다음 주"));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).startsWith("due_at 은");
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("대화가 없는 origin 실행에서 부르면 대화 밖의 실행이라는 도구 오류 글이다")
    void rejectsExecutionWithoutConversation() throws Exception {
        String otherRoot = McpCallSigner.newRoot();
        McpCallSigner.running(executions, agents, dad.id(), null, PROFILE, otherRoot);

        JsonNode result = body(send(json.writeValueAsString(
                        toolCall(arguments().set("_fos_ctx", McpCallSigner.context(dadToken, TOOL, otherRoot))))))
                .path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).isEqualTo("대화 밖의 실행에서는 할 일을 제안할 수 없다.");
        assertThat(rows()).isEmpty();
    }

    @ParameterizedTest(name = "쓰기 도구 허용 {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("먼저 살펴보기 트리는 쓰기 도구 허용과 관계없이 사람이 받아들일 할 일을 제안한다")
    void allowsProposalInCheckTree(boolean writesAllowed) throws Exception {
        ProactiveCheck check = ProactiveCheck.started(
                dad.id(), 1L, conversation.id(), CheckTrigger.MANUAL, writesAllowed, Instant.now());
        check.attachRoot(dadRun.id(), dadRoot);
        ProactiveCheck saved = checks.save(check);
        try {
            JsonNode result = propose(arguments());

            assertThat(result.path("isError").asBoolean()).isFalse();
            assertThat(rows()).hasSize(1);
            assertThat(rows().getFirst().status()).isEqualTo(FollowUpStatus.PROPOSED);
        } finally {
            checks.delete(saved);
        }
    }

    private ObjectNode arguments() {
        return json.createObjectNode().put("title", TITLE);
    }

    /** 아빠의 대화에서 도는 실행 루트로 서명해 부르고 도구 결과를 돌려준다. */
    private JsonNode propose(ObjectNode arguments) throws Exception {
        return body(send(signed(arguments))).path("result");
    }

    private String signed(ObjectNode arguments) {
        return McpCallSigner.withContext(json.writeValueAsString(toolCall(arguments)), dadToken, dadRoot);
    }

    private ObjectNode toolCall(JsonNode arguments) {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", TOOL);
        params.set("arguments", arguments);
        return request;
    }

    private HttpResponse<String> send(String request) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + dadToken)
                        .POST(HttpRequest.BodyPublishers.ofString(request))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private List<FollowUp> rows() {
        return followUps.findByUserIdAndStatusInOrderByIdAsc(dad.id(), List.of(FollowUpStatus.values()));
    }

    private FollowUp onlyRow() {
        List<FollowUp> rows = rows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("본문: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }
}
