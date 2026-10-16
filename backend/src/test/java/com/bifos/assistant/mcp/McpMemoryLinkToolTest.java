package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.memory.application.MemoryLinkService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.memory.domain.type.MemoryLinkType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 서명한 MCP 경계에서 {@code memory_link} 의 계약을 확인한다. 계약은 {@code docs/features/memory.md} 의 「기억 사이의 연결」 이
 * 갖는다(ADR-20261015 / memory-link).
 */
@BackendIntegrationTest
class McpMemoryLinkToolTest {
    private static final String PROFILE = "mcp-memory-link-test";
    private static final String TOOL = "memory_link";
    private static final String UNLINKABLE = "연결할 수 없는 기억입니다.";
    private static final String OLD_TITLE = "합성연결표식 옛 주소";
    private static final String OLD_BODY = "합성본문표식 서울 마포구";
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AppUserRepository users;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryLinkService linkService;

    @Autowired
    MemoryRepository repository;

    @Autowired
    MemoryLinkRepository links;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    McpToolService tools;

    @Autowired
    JdbcTemplate jdbc;

    private MockMvc mvc;
    private CurrentUser user;
    private CurrentUser other;
    private String token;
    private String root;
    private AgentExecution run;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        links.deleteAll();
        repository.deleteAll();
        AppUser owner = users.save(AppUser.of(
            "link-" + UUID.randomUUID() + "@example.test", "연결 검사", 10L, UserRole.ADMIN));
        AppUser stranger = users.save(AppUser.of(
            "link-other-" + UUID.randomUUID() + "@example.test", "다른 사람", 10L, UserRole.MEMBER));
        user = new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
        other = new CurrentUser(
            stranger.id(), stranger.email(), stranger.displayName(), stranger.groupId(), stranger.role());
        token = tokens.issue(PROFILE, "연결 검사").rawToken();
        Agent agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(user.id(), "연결 검사 대화", agent.id(), Instant.now()));
        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, user.id(), conversation.id(), PROFILE, root);
        grants.save(AgentMemoryCollection.of(run.agentId(), "core", false));
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }


    @AfterEach
    void tearDown() {
        links.deleteAll();
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", user.id());
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
    }


    @Test
    @DisplayName("도구 목록에 memory_link 가 있고 두 번호와 종류가 필수이며 모르는 키를 받지 않는다")
    void listsSchema() throws Exception {
        JsonNode list = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        JsonNode tool = StreamSupport.stream(list.path("result").path("tools").spliterator(), false)
            .filter(value -> TOOL.equals(value.path("name").asString()))
            .findFirst()
            .orElseThrow();

        assertThat(tool.path("inputSchema").path("required").toString()).isEqualTo("[\"from_id\",\"to_id\",\"type\"]");
        assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(tool.path("inputSchema").path("properties").path("type").path("enum").toString())
            .isEqualTo("[\"SUPERSEDES\",\"RELATED\"]");
        assertThat(tool.path("inputSchema").path("properties").path("label").path("maxLength").asInt()).isEqualTo(200);
    }


    @Test
    @DisplayName("대체는 제안으로 남아 주입이 바뀌지 않고 관련은 바로 효력이 있으며 label 이 남는다")
    void proposesSupersedeAndRelatesImmediately() throws Exception {
        Memory old = memories.create(user, MemoryScope.USER, OLD_TITLE, OLD_BODY, false);
        Memory fresh = memories.create(user, MemoryScope.USER, "새 주소", "부산 해운대구", false);
        Memory trip = memories.create(user, MemoryScope.USER, "여름 여행", "제주에 간다", false);

        JsonNode supersede = call(link(fresh.id(), old.id(), "SUPERSEDES"));

        assertThat(supersede.path("isError").asBoolean()).isFalse();
        assertThat(text(supersede)).startsWith("대체 제안으로 남겼습니다. 사용자가 받아들여야 바뀝니다.");
        MemoryLink proposed = links.findByFromMemoryIdAndToMemoryIdAndType(fresh.id(), old.id(), MemoryLinkType.SUPERSEDES)
            .orElseThrow();
        assertThat(proposed.status()).isEqualTo(MemoryLinkStatus.PROPOSED);
        assertThat(proposed.origin()).isEqualTo(MemoryLinkOrigin.AGENT);
        assertThat(proposed.createdByExecutionId()).isEqualTo(run.id());
        assertThat(memories.indexedFor(user, memories.accessOf(run.agentId())))
            .as("제안은 주입을 바꾸지 않는다")
            .extracting(Memory::id)
            .contains(old.id());

        JsonNode related = call(link(trip.id(), fresh.id(), "RELATED").put("label", "이사 뒤 첫 여행"));

        assertThat(text(related)).isEqualTo("연결했습니다.");
        MemoryLink relatedLink = links.findByFromMemoryIdAndToMemoryIdAndType(
            Math.min(trip.id(), fresh.id()), Math.max(trip.id(), fresh.id()), MemoryLinkType.RELATED)
            .orElseThrow();
        assertThat(relatedLink.status()).isEqualTo(MemoryLinkStatus.ACTIVE);
        assertThat(relatedLink.label()).isEqualTo("이사 뒤 첫 여행");
    }


    @Test
    @DisplayName("볼 수 없는 항목과 다른 범위와 승인 전 항목과 받지 않는 collection 과 보관한 항목은 같은 오류 글이고 결과와 로그에 제목과 본문이 없다")
    void refusesUnlinkablePairsWithSameText() throws Exception {
        Memory mine = memories.create(user, MemoryScope.USER, OLD_TITLE, OLD_BODY, false);
        Memory group = memories.create(user, MemoryScope.GROUP, "그룹 항목", "그룹 본문", false);
        Memory foreign = memories.create(other, MemoryScope.USER, "남의 항목", "남의 본문", false);
        Memory proposal = memories.proposeUser(user, "제안 항목", "제안 본문", null);
        Memory career = memories.create(
            user, MemoryScope.USER, "경력", "경력 본문", "career", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        Memory archived = memories.create(
            user, MemoryScope.USER, "보관한 것", "보관 본문", "core", MemoryRetrieval.ARCHIVE, MemorySensitivity.NORMAL);
        Logger logger = (Logger)LoggerFactory.getLogger("com.bifos.assistant");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (Long target : List.of(group.id(), foreign.id(), proposal.id(), career.id(), archived.id(), 999_999_999L)) {
                JsonNode result = call(link(mine.id(), target, "RELATED"));
                assertThat(result.path("isError").asBoolean()).as("대상 %s", target).isTrue();
                assertThat(text(result)).isEqualTo(UNLINKABLE);
                assertThat(result.toString()).doesNotContain(OLD_TITLE, OLD_BODY);
            }
            JsonNode self = call(link(mine.id(), mine.id(), "SUPERSEDES"));
            assertThat(text(self)).isEqualTo(UNLINKABLE);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
        assertThat(links.count()).isZero();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .noneMatch(line -> line.contains(OLD_TITLE) || line.contains(OLD_BODY));
    }


    @Test
    @DisplayName("대체된 항목은 다시 잇지 못한다")
    void refusesSupersededEndpoint() throws Exception {
        Memory old = memories.create(user, MemoryScope.USER, "옛", "옛 본문", false);
        Memory fresh = memories.create(user, MemoryScope.USER, "새", "새 본문", false);
        Memory third = memories.create(user, MemoryScope.USER, "셋째", "셋째 본문", false);
        MemoryLink link = linkService.proposeSupersede(user, fresh.id(), old.id(), MemoryLinkOrigin.AGENT, null);
        linkService.accept(user, link.id());

        assertThat(text(call(link(third.id(), old.id(), "RELATED")))).isEqualTo(UNLINKABLE);
    }


    @Test
    @DisplayName("인자 모양이 틀리거나 SUPERSEDES 에 label 을 주면 MCP 인자 오류이고 아무것도 남기지 않는다")
    void rejectsMalformedArguments() throws Exception {
        Memory a = memories.create(user, MemoryScope.USER, "가", "가 본문", false);
        Memory b = memories.create(user, MemoryScope.USER, "나", "나 본문", false);
        List<ObjectNode> malformed = List.of(
            link(a.id(), b.id(), "PART_OF"),
            link(a.id(), b.id(), "SUPERSEDES").put("label", "옛 주소"),
            link(a.id(), b.id(), "RELATED").put("label", "가".repeat(201)),
            link(a.id(), b.id(), "RELATED").put("user_id", 1),
            json.createObjectNode().put("from_id", "1").put("to_id", b.id()).put("type", "RELATED"),
            json.createObjectNode().put("from_id", a.id()).put("type", "RELATED"),
            link(-1L, b.id(), "RELATED"));

        for (ObjectNode arguments : malformed) {
            JsonNode response = rawCall(arguments);
            assertThat(response.path("error").path("code").asInt()).as("인자 %s", arguments).isEqualTo(-32602);
        }
        assertThat(links.count()).isZero();
    }


    @Test
    @DisplayName("한 실행이 맺는 연결 수는 상한에서 멈춘다")
    void stopsAtPerExecutionLimit() throws Exception {
        Memory hub = memories.create(user, MemoryScope.USER, "중심", "중심 본문", false);
        for (int i = 0; i < 10; i++) {
            Memory each = memories.create(user, MemoryScope.USER, "가지 " + i, "본문 " + i, false);
            assertThat(text(call(link(hub.id(), each.id(), "RELATED")))).isEqualTo("연결했습니다.");
        }
        Memory last = memories.create(user, MemoryScope.USER, "넘친 가지", "본문", false);

        JsonNode result = call(link(hub.id(), last.id(), "RELATED"));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(links.count()).isEqualTo(10);
    }


    @Test
    @DisplayName("먼저 살펴보기 트리에서는 memory_link 를 받지 않는다")
    void refusesInCheckTree() throws Exception {
        Memory a = memories.create(user, MemoryScope.USER, "가", "가 본문", false);
        Memory b = memories.create(user, MemoryScope.USER, "나", "나 본문", false);
        ProactiveCheck check = ProactiveCheck.started(user.id(), 1L, conversation.id(), CheckTrigger.MANUAL, true, Instant.now());
        check.attachRoot(run.id(), root);
        ProactiveCheck saved = checks.save(check);
        try {
            JsonNode result = call(link(a.id(), b.id(), "RELATED"));

            assertThat(result).isEqualTo(json.valueToTree(tools.notAllowedInCheck()));
            assertThat(links.count()).isZero();
        } finally {
            checks.delete(saved);
        }
    }


    private ObjectNode link(Long fromId, Long toId, String type) {
        return json.createObjectNode().put("from_id", fromId).put("to_id", toId).put("type", type);
    }


    private JsonNode call(ObjectNode arguments) throws Exception {
        return rawCall(arguments).path("result");
    }


    private JsonNode rawCall(ObjectNode arguments) throws Exception {
        arguments.set("_fos_ctx", McpCallSigner.context(token, TOOL, root));
        return send(json.writeValueAsString(Map.of(
            "jsonrpc",
            "2.0",
            "id",
            1,
            "method",
            "tools/call",
            "params",
            Map.of("name", TOOL, "arguments", arguments))));
    }


    private JsonNode send(String request) throws Exception {
        return json.readTree(mvc.perform(post("/mcp")
            .servletPath("/mcp")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(request))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
    }


    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }
}
