package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.memory.MemorySupersedeRows;
import com.bifos.assistant.memory.application.MemoryCaptureService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.dto.CapturedMemory;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.application.dto.MemorySearchCursor;
import com.bifos.assistant.memory.application.dto.MemorySearchItem;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.ExecutionQuestions;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 {@code memory_remember} 의 합치기({@code replaces})를 확인한다. 요구사항은 {@code docs/features/memory/spec.md} 의 REQ6
 * 이다.
 *
 * <p>합친 옛 기억은 조립, {@code memory_read}, 검색에서 빠지고 목록에는 남는다. 새 기억을 되돌리면 옛 기억이 다시 쓰인다.
 */
@BackendIntegrationTest
class McpMemoryReplacesToolTest {
    private static final String PROFILE = "mcp-memory-replaces-tool";
    private static final String TOOL = "memory_remember";
    private static final String QUESTION = "내 기억들 정리해 줘";
    private static final MemoryAccess CORE = MemoryAccess.of(Set.of("core"));

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
    ExecutionQuestions questions;

    @Autowired
    MemoryRepository memories;

    @Autowired
    MemoryCaptureRepository captures;

    @Autowired
    MemoryCaptureService captureService;

    @Autowired
    MemoryService memoryService;

    @Autowired
    ContextAssembler assembler;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private AppUser mom;
    private String dadToken;
    private String dadRoot;
    private Conversation conversation;
    private AgentExecution dadRun;


    @BeforeEach
    void setUp() {
        clear();
        dad = users.save(AppUser.of("mcp-replaces-" + UUID.randomUUID() + "@example.com", "아빠", 1L, UserRole.ADMIN));
        mom = users.save(AppUser.of("mcp-replaces-" + UUID.randomUUID() + "@example.com", "엄마", 1L, UserRole.MEMBER));
        dadToken = tokens.issue(PROFILE, "memory-replaces").rawToken();
        Agent agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "합치기 검사 대화", agent.id(), Instant.now()));
        dadRoot = McpCallSigner.newRoot();
        dadRun = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, dadRoot);
    }


    @AfterEach
    void tearDown() {
        for (AppUser user : List.of(dad, mom)) {
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM memory_capture WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM memory_revision WHERE owner_user_id = ?", user.id());
            jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", user.id());
        }
        jdbc.update("DELETE FROM memory_revision WHERE memory_id IN (SELECT id FROM memory WHERE scope = 'GROUP' AND title = ?)", "합치기 그룹 기억");
        jdbc.update("DELETE FROM memory WHERE scope = 'GROUP' AND title = ?", "합치기 그룹 기억");
        jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversation.id());
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", dad.id());
        clear();
    }


    @Test
    @DisplayName("바로 저장으로 합치면 옛 기억이 조립, memory_read, 검색에서 빠지고 목록과 기록 줄의 옛 제목에는 남는다")
    void mergesDirectly() throws Exception {
        Memory first = fact("첫째날 산책 메모", "아침마다 공원을 걷는다");
        Memory second = fact("둘째날 산책 메모", "저녁에도 가끔 걷는다");
        assertThat(instructions()).contains("첫째날 산책 메모", "둘째날 산책 메모");
        askedInThisTurn();

        JsonNode result = call(merge("산책 습관", "아침마다 공원을 걷고 저녁에도 가끔 걷는다", first.id(), second.id()));

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).contains("옛 기억(번호 " + first.id() + ", " + second.id() + ")을 대신했다.")
            .doesNotContain("대신하지 못함");
        Long fresh = newest().id();
        assertThat(MemorySupersedeRows.supersededBy(jdbc, first.id())).isEqualTo(fresh);
        assertThat(MemorySupersedeRows.supersededBy(jdbc, second.id())).isEqualTo(fresh);
        assertThat(instructions()).doesNotContain("첫째날 산책 메모", "둘째날 산책 메모").contains("산책 습관");
        assertThat(memoryService.readableByTool(reread(first.id()), CORE)).isFalse();
        assertThat(search("산책 메모")).doesNotContain(first.id(), second.id());
        assertThat(memoryService.readableBy(currentDad())).extracting(Memory::id).contains(first.id(), second.id(), fresh);
        MemoryCapture capture = onlyCapture();
        assertThat(capture.kind()).isEqualTo(MemoryCaptureKind.CREATED);
        assertThat(capture.replacedMemoryIds()).containsExactly(first.id(), second.id());
        assertThat(captureService.capturesOf(currentDad(), conversation.id()))
            .singleElement()
            .extracting(CapturedMemory::replacedTitles)
            .isEqualTo(List.of("첫째날 산책 메모", "둘째날 산책 메모"));
    }


    @Test
    @DisplayName("대신할 수 없는 번호가 섞이면 새 기억은 저장하고 그 번호만 건너뛰며 까닭 없이 대신하지 못함만 알린다")
    void skipsIrreplaceableIds() throws Exception {
        Memory mine = fact("내 운동 메모", "수영을 배운다");
        Memory others = memoryService.create(currentMom(), MemoryScope.USER, "엄마 운동 메모", "요가를 한다", false);
        Memory group = memoryService.create(currentDad(), MemoryScope.GROUP, "합치기 그룹 기억", "가족 산책은 일요일이다", false);
        Memory health = fact("건강 운동 메모", "무릎이 아프다");
        jdbc.update("UPDATE memory SET collection = 'health' WHERE id = ?", health.id());
        Memory proposed = memoryService.proposeUser(currentDad(), "제안 운동 메모", "달리기를 한다", 1L);
        Memory superseded = fact("대체된 운동 메모", "자전거를 탔다");
        MemorySupersedeRows.supersede(jdbc, superseded.id(), mine.id());
        long missing = 987_654_321L;
        askedInThisTurn();

        JsonNode result = call(merge("운동", "수영을 배운다", mine.id(), others.id(), group.id(), health.id(), proposed.id(),
            superseded.id(), missing));

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).contains("옛 기억(번호 " + mine.id() + ")을 대신했다.")
            .contains("대신하지 못함: " + others.id() + ", " + group.id() + ", " + health.id() + ", " + proposed.id() + ", "
                + superseded.id() + ", " + missing + ".");
        Long fresh = newest().id();
        assertThat(MemorySupersedeRows.supersededBy(jdbc, mine.id())).isEqualTo(fresh);
        for (Memory untouched : List.of(others, group, health, proposed)) {
            assertThat(MemorySupersedeRows.supersededBy(jdbc, untouched.id())).isNull();
        }
        assertThat(MemorySupersedeRows.supersededBy(jdbc, superseded.id())).as("이미 대체된 기억은 덮지 않는다").isEqualTo(mine.id());
        assertThat(onlyCapture().replacedMemoryIds()).containsExactly(mine.id());
    }


    @Test
    @DisplayName("제안으로 내려간 합치기는 받아들이기 전에는 옛 기억을 그대로 두고, 받아들일 때 남은 번호만 대신하며 기록을 고친다")
    void mergesOnAcceptance() throws Exception {
        Memory first = fact("첫째 여행 메모", "제주에 가고 싶다");
        Memory second = fact("둘째 여행 메모", "부산도 가고 싶다");

        JsonNode result = call(merge("여행 계획", "제주와 부산에 가고 싶다", first.id(), second.id()));

        assertThat(text(result)).contains("제안으로 남겼다").contains("받아들이면 대신한다");
        Memory proposal = newest();
        assertThat(proposal.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(MemorySupersedeRows.supersededBy(jdbc, first.id())).isNull();
        assertThat(instructions()).contains("첫째 여행 메모", "둘째 여행 메모");
        assertThat(captureService.capturesOf(currentDad(), conversation.id()))
            .singleElement()
            .extracting(CapturedMemory::replacedTitles)
            .isEqualTo(List.of("첫째 여행 메모", "둘째 여행 메모"));

        memoryService.delete(currentDad(), second.id());
        memoryService.accept(currentDad(), proposal.id());

        assertThat(MemorySupersedeRows.supersededBy(jdbc, first.id())).isEqualTo(proposal.id());
        assertThat(instructions()).doesNotContain("첫째 여행 메모");
        assertThat(onlyCapture().replacedMemoryIds()).as("지운 옛 기억은 기록에서 빠진다").containsExactly(first.id());
        assertThat(captureService.capturesOf(currentDad(), conversation.id()))
            .singleElement()
            .extracting(CapturedMemory::replacedTitles)
            .isEqualTo(List.of("첫째 여행 메모"));
    }


    @Test
    @DisplayName("같은 제안을 바로 저장 조건에서 다시 받으면 그 제안의 기록 번호로 대신하고 이번 호출의 replaces 는 쓰지 않는다")
    void acceptsExistingProposalWithRecordedIds() throws Exception {
        Memory recorded = fact("기록된 취미 메모", "그림을 그린다");
        Memory other = fact("다른 취미 메모", "노래를 부른다");
        call(merge("취미", "그림을 그린다", recorded.id()));
        Memory proposal = newest();
        askedInThisTurn();

        JsonNode result = call(merge("취미", "그림을 그린다", other.id()));

        assertThat(text(result)).startsWith("기억했다.")
            .contains("옛 기억(번호 " + recorded.id() + ")을 대신했다.")
            .contains("대신하지 못함: " + other.id() + ".");
        assertThat(reread(proposal.id()).status()).isEqualTo(MemoryStatus.ACCEPTED);
        assertThat(MemorySupersedeRows.supersededBy(jdbc, recorded.id())).isEqualTo(proposal.id());
        assertThat(MemorySupersedeRows.supersededBy(jdbc, other.id())).isNull();
    }


    @Test
    @DisplayName("합친 글이 이미 기억한 글과 같아 새로 남기지 않으면 replaces 를 대신하지 않고 대신하지 못함으로 알린다")
    void reportsReplacesWhenAlreadyKnown() throws Exception {
        Memory other = fact("다른 음식 메모", "국수를 좋아한다");
        askedInThisTurn();
        call(remember("이미 아는 음식 메모", "매운 음식을 좋아한다"));
        Memory known = newest();

        JsonNode result = call(merge("이미 아는 음식 메모", "매운 음식을 좋아한다", other.id()));

        assertThat(text(result)).startsWith("이미 같은 내용을 기억하고 있다.").contains("대신하지 못함: " + other.id() + ".");
        assertThat(MemorySupersedeRows.supersededBy(jdbc, other.id())).isNull();
        assertThat(MemorySupersedeRows.supersededBy(jdbc, known.id())).isNull();
    }


    @Test
    @DisplayName("합치기 제안을 거절하면 옛 기억은 대신되지 않고 그대로 쓰인다")
    void rejectedProposalKeepsOld() throws Exception {
        Memory old = fact("거절 전 운동 메모", "주말에 등산한다");
        call(merge("운동 정리", "주말에 등산하고 평일에 걷는다", old.id()));
        Memory proposal = newest();

        memoryService.reject(currentDad(), proposal.id());

        assertThat(MemorySupersedeRows.supersededBy(jdbc, old.id())).isNull();
        assertThat(instructions()).contains("거절 전 운동 메모");
    }


    @Test
    @DisplayName("제안을 받아들이기 전에 옛 기억이 다른 합치기로 대체되면 제안 카드 목록에서 빠지고 받아들여도 덮지 않는다")
    void proposalSkipsOldSupersededMeanwhile() throws Exception {
        Memory old = fact("먼저 합쳐질 메모", "아침에 커피를 마신다");
        call(merge("커피 정리 제안", "아침에 커피를 마시고 오후엔 차를 마신다", old.id()));
        Memory proposal = newest();
        askedInThisTurn();
        call(merge("커피 정리", "아침에 커피를 마신다, 디카페인으로", old.id()));
        Long direct = newest().id();

        assertThat(captureService.capturesOf(currentDad(), conversation.id()))
            .filteredOn(captured -> captured.memory().id().equals(proposal.id()))
            .singleElement()
            .extracting(CapturedMemory::replacedTitles)
            .isEqualTo(List.of());
        memoryService.accept(currentDad(), proposal.id());

        assertThat(MemorySupersedeRows.supersededBy(jdbc, old.id())).isEqualTo(direct);
        assertThat(captures.findFirstByMemoryIdAndKindOrderByIdDesc(proposal.id(), MemoryCaptureKind.PROPOSED).orElseThrow()
            .replacedMemoryIds()).isEmpty();
    }


    @Test
    @DisplayName("합친 새 기억을 되돌리면 옛 기억이 다음 조립에 다시 든다")
    void undoRestoresReplaced() throws Exception {
        Memory old = fact("예전 직장 메모", "판교로 출근한다");
        askedInThisTurn();
        call(merge("직장", "판교로 출근하고 재택도 한다", old.id()));
        assertThat(instructions()).doesNotContain("예전 직장 메모");

        captureService.undo(currentDad(), onlyCapture().id());

        assertThat(MemorySupersedeRows.supersededBy(jdbc, old.id())).isNull();
        assertThat(instructions()).contains("예전 직장 메모");
        assertThat(memoryService.readableByTool(reread(old.id()), CORE)).isTrue();
    }


    @Test
    @DisplayName("replaces 의 모양이 어긋나거나 memory_id 와 함께 오면 인자 오류이고 저장하지 않는다")
    void rejectsMalformedReplaces() throws Exception {
        Memory old = fact("모양 검사 메모", "고양이를 키운다");
        ObjectNode withMemoryId = merge("고양이", "고양이 둘을 키운다", old.id()).put("memory_id", old.id());
        ObjectNode empty = merge("고양이", "고양이 둘을 키운다");
        ObjectNode duplicated = merge("고양이", "고양이 둘을 키운다", old.id(), old.id());
        ObjectNode tooMany = merge("고양이", "고양이 둘을 키운다", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        ObjectNode notInteger = remember("고양이", "고양이 둘을 키운다");
        notInteger.putArray("replaces").add("하나");

        for (ObjectNode arguments : List.of(withMemoryId, empty, duplicated, tooMany, notInteger)) {
            JsonNode response = body(send(signed(arguments)));
            assertThat(response.path("error").path("code").asInt()).as(arguments.toString()).isEqualTo(-32602);
        }
        assertThat(memoriesOfDad()).extracting(Memory::id).containsExactly(old.id());
    }


    @Test
    @DisplayName("도구 정의가 replaces 를 선택 인자로 알린다")
    void listsReplacesInSchema() throws Exception {
        JsonNode tools = body(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).path("result").path("tools");
        JsonNode tool = null;
        for (JsonNode candidate : tools) {
            if (TOOL.equals(candidate.path("name").asString())) {
                tool = candidate;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.path("inputSchema").path("properties").path("replaces").path("type").asString()).isEqualTo("array");
        assertThat(tool.path("description").asString()).contains("replaces");
    }


    private Memory fact(String title, String content) {
        return memoryService.create(currentDad(), MemoryScope.USER, title, content, false);
    }


    private String instructions() {
        return Objects.toString(assembler.assemble(currentDad(), dadRun.agentId()).instructions(), "");
    }


    private List<Long> search(String query) {
        return memoryService.searchFor(currentDad(), CORE, query, 20, MemorySearchCursor.first(query), Optional.empty()).items().stream()
            .map(MemorySearchItem::id)
            .toList();
    }


    private void askedInThisTurn() {
        ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), QUESTION, Instant.now()));
        questions.link(dadRun.id(), question.id());
    }


    private void clear() {
        jdbc.update(
            "DELETE FROM execution_event WHERE execution_id IN (SELECT id FROM agent_execution WHERE profile_name = ?)",
            PROFILE);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
    }


    private CurrentUser currentDad() {
        return new CurrentUser(dad.id(), dad.email(), "아빠", 1L, UserRole.ADMIN);
    }


    private CurrentUser currentMom() {
        return new CurrentUser(mom.id(), mom.email(), "엄마", 1L, UserRole.MEMBER);
    }


    private ObjectNode remember(String title, String content) {
        return json.createObjectNode().put("title", title).put("content", content);
    }


    private ObjectNode merge(String title, String content, long... replaces) {
        ObjectNode arguments = remember(title, content);
        ArrayNode ids = arguments.putArray("replaces");
        for (long id : replaces) {
            ids.add(id);
        }
        return arguments;
    }


    private Memory reread(Long id) {
        return memories.findById(id).orElseThrow();
    }


    private List<Memory> memoriesOfDad() {
        return memories.findAll().stream().filter(memory -> dad.id().equals(memory.ownerUserId())).toList();
    }


    private Memory newest() {
        return memoriesOfDad().stream().max((left, right) -> Long.compare(left.id(), right.id())).orElseThrow();
    }


    private MemoryCapture onlyCapture() {
        List<MemoryCapture> rows = captures.findAll().stream().filter(capture -> dad.id().equals(capture.userId())).toList();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }


    private JsonNode call(ObjectNode arguments) throws Exception {
        return body(send(signed(arguments))).path("result");
    }


    private String signed(ObjectNode arguments) {
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", TOOL);
        params.set("arguments", arguments);
        return McpCallSigner.withContext(json.writeValueAsString(request), dadToken, dadRoot);
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


    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("본문: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }


    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }
}
