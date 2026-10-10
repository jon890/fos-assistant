package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.context.ContextBodyMode;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.application.McpToolService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.SubagentSessionRegistrar;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.ContextSourceRef;
import com.bifos.assistant.usage.application.ExecutionContextSourceWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** 서명한 MCP 경계와 실제 색인 예산 누락을 거쳐 제목 검색부터 본문 사용 기록까지 확인한다. */
@BackendIntegrationTest
class McpMemorySearchToolTest {
    private static final String PROFILE = "mcp-memory-search-test";
    private static final String QUERY = "합성검색표식";
    private static final String TITLE = QUERY + " 민감한 제목 표식";
    private static final String BODY = "합성본문표식 " + "가".repeat(240);
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
    MemoryRepository repository;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentMemoryCollectionRepository grants;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionContextSourceRepository sources;

    @Autowired
    ExecutionContextSourceWriter sourceWriter;

    @Autowired
    SubagentSessionRegistrar registrar;

    @Autowired
    McpToolService tools;

    @Autowired
    JdbcTemplate jdbc;

    private MockMvc mvc;
    private CurrentUser user;
    private String token;
    private String root;
    private AgentExecution run;

    @BeforeEach
    void setUp() {
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        repository.deleteAll();
        AppUser owner = users.save(AppUser.of(
                "search-" + UUID.randomUUID() + "@example.test", "검색 검사", 10L, UserRole.ADMIN, Instant.now()));
        user = new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
        token = tokens.issue(PROFILE, "합성 검사").rawToken();
        root = McpCallSigner.newRoot();
        run = McpCallSigner.running(executions, agents, user.id(), 1L, PROFILE, root);
        grants.save(AgentMemoryCollection.of(run.agentId(), "core", false, Instant.now()));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("작은 조립 예산으로 빠진 항목을 검색한 뒤 읽어야 MEMORY_READ 한 줄을 남긴다")
    void discoversActuallyOmittedItemAndRecordsOnlySuccessfulRead() throws Exception {
        Memory memory = memories.create(user, MemoryScope.USER, TITLE, BODY, false);
        ContextAssembler small = new ContextAssembler(
                memories, new ContextProperties(32, 4, null, null, Map.of(), 0, 200), Clock.systemUTC());
        AssembledContext assembled = small.assemble(user, run.agentId());
        assertThat(assembled.omittedMemoryIds()).containsExactly(memory.id());
        assertThat(assembled.bundle().items())
                .filteredOn(item -> item.ref().equals("memory:" + memory.id()))
                .extracting(item -> item.bodyMode())
                .containsExactly(ContextBodyMode.OMITTED);
        assertThat(assembled.instructions()).isNull();
        sourceWriter.write(
                run.id(),
                assembled.bundle().items().stream()
                        .map(item -> new ContextSourceRef(
                                item.source().name(),
                                item.ref(),
                                item.bodyMode().name(),
                                item.freshness().name()))
                        .toList());

        Logger logger = (Logger) LoggerFactory.getLogger(McpToolService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            JsonNode response = call("memory_search", arguments().put("query", QUERY));
            JsonNode data = searchData(response);
            assertThat(data.path("items")).hasSize(1);
            JsonNode item = data.path("items").get(0);
            assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "title", "revision", "updatedAt");
            assertThat(item.path("id").asLong()).isEqualTo(memory.id());
            assertThat(item.path("title").asString()).isEqualTo(TITLE);
            assertThat(data.path("nextAfterId").isNull()).isTrue();
            assertThat(response.toString()).doesNotContain(BODY, "ownerUserId", "contentKeyId");
            assertThat(sources.findByIdExecutionIdOrderByIdPositionAsc(run.id()))
                    .extracting(ExecutionContextSource::source)
                    .doesNotContain("MEMORY_READ");
            JsonNode read =
                    call("memory_read", arguments().put("id", item.path("id").asLong()));
            assertThat(text(read)).isEqualTo(BODY);
            assertThat(sources.findByIdExecutionIdOrderByIdPositionAsc(run.id()))
                    .filteredOn(source -> "MEMORY_READ".equals(source.source()))
                    .extracting(ExecutionContextSource::sourceRef)
                    .containsExactly("memory:" + memory.id());
            assertThat(logs.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain(QUERY, TITLE, BODY));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    @DisplayName("검색 뒤에도 권한 철회 거절 삭제를 검사하고 판이 바뀌면 최신 본문을 읽는다")
    void rechecksReadPermissionsAndReturnsCurrentRevision() throws Exception {
        Memory memory = memories.create(user, MemoryScope.USER, TITLE, BODY, false);
        assertThat(searchData(call("memory_search", arguments().put("query", QUERY)))
                        .path("items"))
                .hasSize(1);
        memories.update(user, memory.id(), "바뀐 본문", false);
        JsonNode latest = searchData(call("memory_search", arguments().put("query", QUERY)));
        assertThat(latest.path("items").get(0).path("revision").asInt()).isEqualTo(2);
        assertThat(text(call("memory_read", arguments().put("id", memory.id()))))
                .isEqualTo("바뀐 본문");
        jdbc.update("DELETE FROM agent_memory_collection WHERE agent_id = ?", run.agentId());
        assertUnavailable(memory.id());
        assertThat(searchData(call("memory_search", arguments().put("query", QUERY)))
                        .path("items"))
                .isEmpty();
        grants.save(AgentMemoryCollection.of(run.agentId(), "core", false, Instant.now()));
        jdbc.update("UPDATE memory SET status = 'REJECTED' WHERE id = ?", memory.id());
        assertUnavailable(memory.id());
        jdbc.update("UPDATE memory SET status = 'ACCEPTED' WHERE id = ?", memory.id());
        repository.deleteById(memory.id());
        assertUnavailable(memory.id());
        assertThat(sources.findByIdExecutionIdOrderByIdPositionAsc(run.id()))
                .filteredOn(source -> "MEMORY_READ".equals(source.source()))
                .hasSize(1);
    }

    @Test
    @DisplayName("등록한 native 자식은 origin의 권한을 쓰며 서명 오류 다른 profile 취소된 루트는 제목도 받지 못한다")
    void validatesSignatureProfileOriginAndRegisteredChild() throws Exception {
        memories.create(user, MemoryScope.USER, TITLE, BODY, false);
        String child = "native-" + UUID.randomUUID();
        registrar.register(PROFILE, root, root, child);
        ObjectNode value = arguments().put("query", QUERY);
        value.set("_fos_ctx", McpCallSigner.context(token, "memory_search", root, child, "call_" + UUID.randomUUID()));
        assertThat(searchData(send("memory_search", value, token)).path("items"))
                .hasSize(1);
        ObjectNode invalid = arguments().put("query", QUERY);
        invalid.set("_fos_ctx", McpCallSigner.context(token, "memory_read", root));
        assertInvalid(send("memory_search", invalid, token));
        assertInvalid(send("memory_search", arguments().put("query", QUERY), token));
        String other = tokens.issue("other-search-profile", "합성 검사").rawToken();
        ObjectNode mismatched = arguments().put("query", QUERY);
        mismatched.set("_fos_ctx", McpCallSigner.context(other, "memory_search", root));
        assertInvalid(send("memory_search", mismatched, other));
        ObjectNode unregistered = arguments().put("query", QUERY);
        unregistered.set(
                "_fos_ctx", McpCallSigner.context(token, "memory_search", root, "missing-child", "missing-call"));
        assertInvalid(send("memory_search", unregistered, token));
        jdbc.update("UPDATE agent_execution SET status = 'CANCELLED' WHERE id = ?", run.id());
        assertInvalid(send("memory_search", value, token));
        assertInvalid(call("memory_search", arguments().put("query", QUERY)));
    }

    @Test
    @DisplayName("형식 길이 범위와 모르는 키는 MCP의 인자 오류이며 페이지 끝 번호는 null이다")
    void rejectsInvalidArgumentsAndSerializesLastPage() throws Exception {
        List<ObjectNode> invalid = List.of(
                arguments(),
                arguments().putNull("query"),
                arguments().put("query", true),
                arguments().put("query", "  "),
                arguments().put("query", "가".repeat(201)),
                arguments().put("query", QUERY).put("limit", 0),
                arguments().put("query", QUERY).put("limit", 51),
                arguments().put("query", QUERY).put("limit", 1.5),
                arguments().put("query", QUERY).putNull("limit"),
                arguments().put("query", QUERY).put("limit", "10"),
                arguments().put("query", QUERY).put("after_id", 0),
                arguments().put("query", QUERY).put("after_id", -1),
                arguments().put("query", QUERY).put("after_id", 1.5),
                arguments().put("query", QUERY).putNull("after_id"),
                arguments().put("query", QUERY).put("user_id", user.id()),
                arguments().put("query", QUERY).put("profile", PROFILE),
                arguments().put("query", QUERY).put("collection", "career"));
        for (ObjectNode value : invalid) {
            assertThat(call("memory_search", value).path("error").path("code").asInt())
                    .isEqualTo(-32602);
        }
        ObjectNode oversized = arguments().put("query", QUERY);
        oversized.set("after_id", json.readTree("9223372036854775808"));
        assertThat(call("memory_search", oversized).path("error").path("code").asInt())
                .isEqualTo(-32602);
        Memory first = memories.create(user, MemoryScope.USER, TITLE, BODY, false);
        Memory second = memories.create(user, MemoryScope.USER, TITLE + " 둘", BODY, false);
        JsonNode page =
                searchData(call("memory_search", arguments().put("query", QUERY).put("limit", 1)));
        assertThat(page.path("nextAfterId").asLong()).isEqualTo(first.id());
        JsonNode last = searchData(call(
                "memory_search", arguments().put("query", QUERY).put("limit", 1).put("after_id", first.id())));
        assertThat(last.path("items").get(0).path("id").asLong()).isEqualTo(second.id());
        assertThat(last.path("nextAfterId").isNull()).isTrue();
        JsonNode empty = searchData(call("memory_search", arguments().put("query", "없는 제목")));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("nextAfterId").isNull()).isTrue();
    }

    private void assertUnavailable(Long id) throws Exception {
        JsonNode response = call("memory_read", arguments().put("id", id));
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(response)).isEqualTo("Memory 항목을 읽을 수 없습니다.");
    }

    private void assertInvalid(JsonNode response) {
        assertThat(response.path("result")).isEqualTo(json.valueToTree(tools.invalidContext()));
        assertThat(response.toString()).doesNotContain(QUERY, TITLE, BODY);
    }

    private ObjectNode arguments() {
        return json.createObjectNode();
    }

    private JsonNode call(String name, ObjectNode arguments) throws Exception {
        arguments.set("_fos_ctx", McpCallSigner.context(token, name, root));
        return send(name, arguments, token);
    }

    private JsonNode send(String name, ObjectNode arguments, String rawToken) throws Exception {
        String request = json.writeValueAsString(Map.of(
                "jsonrpc",
                "2.0",
                "id",
                1,
                "method",
                "tools/call",
                "params",
                Map.of("name", name, "arguments", arguments)));
        return json.readTree(mvc.perform(post("/mcp")
                        .servletPath("/mcp")
                        .header("Authorization", "Bearer " + rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private static String text(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asString();
    }

    private JsonNode searchData(JsonNode response) {
        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        String text = text(response);
        assertThat(text).contains("<external-data>\n").endsWith("\n</external-data>");
        return json.readTree(text.substring(
                text.indexOf("<external-data>\n") + "<external-data>\n".length(),
                text.lastIndexOf("\n</external-data>")));
    }
}
