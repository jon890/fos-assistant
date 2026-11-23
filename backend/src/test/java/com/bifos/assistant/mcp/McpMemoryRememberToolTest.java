package com.bifos.assistant.mcp;

import java.util.Objects;
import java.util.Set;
import com.bifos.assistant.memory.application.dto.MemoryRememberRequest;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.context.application.ContextAssembler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.memory.application.MemoryCaptureService;
import com.bifos.assistant.memory.MemorySupersedeRows;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.dto.CapturedMemory;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.application.ExecutionQuestions;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
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
 * 실제 HTTP 경계에서 {@code memory_remember} 의 계약을 확인한다. 계약은 {@code docs/features/memory.md} 의 「에이전트가 기억을
 * 남기는 길」 이 갖는다(ADR-20261007 / memory-remember).
 *
 * <p>바로 저장은 사람이 보낸 turn 의 루트 실행이고, 부정 표지가 질문과 본문에 함께 있거나 함께 없고, 그 실행이 바깥 도구를 부르지
 * 않았을 때만이다(ADR-20261008 / memory-remember-guard).
 */
@BackendIntegrationTest
class McpMemoryRememberToolTest {
    private static final String PROFILE = "mcp-memory-remember-tool";
    private static final String TOOL = "memory_remember";
    private static final String QUESTION = "우리 집   다른 사람은 홍길동이야. 기억해 줘";
    private static final String REMEMBERED = "기억했다. 사용자 화면에 「기억했어요」 와 되돌리기가 보인다. 답에서 무엇을 기억했는지 짧게 알린다.";
    private static final String PROPOSED = "제안으로 남겼다. 사용자가 받아들여야 기억한다. 답에서 아직 승인 전이라는 것을 알린다.";
    private static final String UPDATE_PROPOSED = "지금은 기존 기억을 바로 고칠 수 없어 고치기 제안으로 남겼다. 사용자가 받아들이면 새 기억이 옛 기억을 대신한다. "
        + "받아들인 새 기억에는 한 줄 요약이 없다. 같은 고치기를 다시 부르지 않고, 답에서 기억 화면이나 제안 카드에서 받아들여야 바뀐다고 알린다.";

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
    ExecutionEventRepository events;

    @Autowired
    MemoryRepository memories;

    @Autowired
    MemoryCaptureRepository captures;

    @Autowired
    MemoryCaptureService captureService;

    @Autowired
    MemoryService memoryService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContextAssembler assembler;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private AppUser dad;
    private String dadToken;
    private String dadRoot;
    private Conversation conversation;
    private AgentExecution dadRun;


    @BeforeEach
    void setUp() {
        clear();
        String email = "mcp-memory-remember-" + UUID.randomUUID() + "@example.com";
        dad = users.save(AppUser.of(email, "아빠", 1L, UserRole.ADMIN));
        dadToken = tokens.issue(PROFILE, "memory-remember").rawToken();
        Agent agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "기억 검사 대화", agent.id(), Instant.now()));
        dadRoot = McpCallSigner.newRoot();
        dadRun = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, dadRoot);
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", dad.id());
        jdbc.update("DELETE FROM memory_capture WHERE user_id = ?", dad.id());
        jdbc.update("DELETE FROM memory_revision WHERE owner_user_id = ?", dad.id());
        jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", dad.id());
        jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversation.id());
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", dad.id());
        clear();
    }


    @Test
    @DisplayName("도구 목록에 memory_remember가 하나 있고 title과 content가 필수다")
    void listsMemoryRememberSchema() throws Exception {
        JsonNode tools = body(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .path("result")
            .path("tools");

        List<JsonNode> matches = StreamSupport.stream(tools.spliterator(), false)
            .filter(value -> TOOL.equals(value.path("name").asString()))
            .toList();
        assertThat(matches).hasSize(1);
        JsonNode tool = matches.getFirst();
        assertThat(tool.path("name").asString()).isEqualTo(TOOL);
        assertThat(tool.path("inputSchema").path("required").toString()).isEqualTo("[\"title\",\"content\"]");
        assertThat(tool.path("inputSchema").has("additionalProperties")).isFalse();
        assertThat(tool.path("description").asString()).contains("작업 기록").contains("부정은 content 에 그대로 살린다");
    }


    @Test
    @DisplayName("사람이 보낸 turn 에서 부정이 뒤집히지 않은 본문은 바로 ACCEPTED 로 저장하고 기록을 남긴다")
    void remembersDirectlyFromHumanTurn() throws Exception {
        askedInThisTurn();

        JsonNode result = call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"));

        assertThat(text(result)).startsWith(REMEMBERED);
        assertThat(result.path("isError").asBoolean()).isFalse();
        Memory memory = onlyMemory();
        assertThat(memory.status()).isEqualTo(MemoryStatus.ACCEPTED);
        assertThat(memory.scope()).isEqualTo(MemoryScope.USER);
        assertThat(memory.retrieval()).isEqualTo(MemoryRetrieval.SEARCH);
        assertThat(memory.acceptedByUserId()).isEqualTo(dad.id());
        assertThat(memory.proposedByExecutionId()).isEqualTo(dadRun.id());
        MemoryCapture capture = onlyCapture();
        assertThat(capture.kind()).isEqualTo(MemoryCaptureKind.CREATED);
        assertThat(capture.conversationId()).isEqualTo(conversation.id());
        assertThat(feedbackEvents()).as("바로 저장은 제안이 아니라 판단 피드백을 남기지 않는다").isEmpty();
    }


    @Test
    @DisplayName("근거가 없거나 질문에 없어도 다듬은 본문은 바로 저장한다")
    void remembersRefinedContentRegardlessOfEvidence() throws Exception {
        askedInThisTurn();

        assertThat(text(call(remember("취미", "등산을 좋아한다", null)))).startsWith(REMEMBERED);
        assertThat(text(call(remember("직업", "교사다", "나는 교사야")))).startsWith(REMEMBERED);

        assertThat(memoriesOfDad())
            .extracting(Memory::status)
            .containsExactly(MemoryStatus.ACCEPTED, MemoryStatus.ACCEPTED);
        assertThat(capturesOfDad())
            .extracting(MemoryCapture::kind)
            .containsExactly(MemoryCaptureKind.CREATED, MemoryCaptureKind.CREATED);
        assertThat(feedbackEvents()).as("바로 저장은 판단 피드백을 남기지 않는다").isEmpty();
    }


    @Test
    @DisplayName("archivable 을 주지 않은 새 기억은 자동 보관 허락이 거짓이고 true 로 준 새 기억만 참이다")
    void archivableDefaultsToFalse() throws Exception {
        askedInThisTurn();

        assertThat(text(call(remember("생일", "생일은 3월 2일이다", null)))).startsWith(REMEMBERED);
        assertThat(text(call(remember("출장", "이번 주 부산 출장 중이다", null).put("archivable", true))))
            .startsWith(REMEMBERED);

        assertThat(memoriesOfDad())
            .extracting(Memory::title, Memory::archivable)
            .containsExactly(tuple("생일", false), tuple("출장", true));
    }


    @Test
    @DisplayName("기존 기억을 고칠 때 준 archivable 은 그 항목의 자동 보관 허락을 바꾸지 않는다")
    void archivableIsIgnoredOnUpdate() throws Exception {
        askedInThisTurn();
        assertThat(text(call(remember("직업", "교사다", null)))).startsWith(REMEMBERED);
        Memory created = onlyMemory();

        call(remember("직업", "교감이다", null).put("memory_id", created.id()).put("archivable", true));

        assertThat(memories.findById(created.id()).orElseThrow().archivable()).isFalse();
    }


    @Test
    @DisplayName("archivable 이 참거짓이 아니면 인자 오류다")
    void rejectsNonBooleanArchivable() throws Exception {
        askedInThisTurn();

        JsonNode response = body(send(signed(remember("취미", "등산을 좋아한다", null).put("archivable", "yes"))));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(memoriesOfDad()).isEmpty();
    }


    @Test
    @DisplayName("질문의 부정이 본문에서 사라지면 제안으로 내린다")
    void proposesWhenContentDropsNegation() throws Exception {
        askedInThisTurn("매운 음식을 못 먹는다.");

        assertThat(text(call(remember("매운 음식", "매운 음식을 좋아한다", "매운 음식")))).isEqualTo(PROPOSED);

        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.PROPOSED);
    }


    @Test
    @DisplayName("질문에 없던 부정이 본문에 생기면 제안으로 내린다")
    void proposesWhenContentAddsNegation() throws Exception {
        askedInThisTurn("매운 음식 좋아해.");

        assertThat(text(call(remember("매운 음식", "매운 음식을 못 먹는다", null)))).isEqualTo(PROPOSED);

        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.PROPOSED);
    }


    @Test
    @DisplayName("질문의 부정이 다른 표지로라도 본문에 살아 있으면 바로 저장한다")
    void remembersWhenNegationKept() throws Exception {
        askedInThisTurn("나는 오이를 안 먹어.");

        assertThat(text(call(remember("오이", "오이를 먹지 않는다", null)))).startsWith(REMEMBERED);

        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.ACCEPTED);
    }


    @Test
    @DisplayName("모델이 sensitive 를 주지 않아도 본문이 민감해 보이면 제안으로 내린다")
    void proposesSensitiveLookingContent() throws Exception {
        askedInThisTurn("아빠 계좌는 국민은행이야.");

        assertThat(text(call(remember("아빠 계좌", "아빠 계좌는 국민은행이야", null)))).isEqualTo(PROPOSED);

        Memory memory = onlyMemory();
        assertThat(memory.status()).isEqualTo(MemoryStatus.PROPOSED);
    }


    @Test
    @DisplayName("바로 저장한 항목을 민감해 보이는 본문으로 고치려 하면 고치지 않고 고치기 제안으로 남긴다")
    void proposesUpdateToSensitiveLookingContent() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", null));
        Long memoryId = onlyMemory().id();
        ObjectNode arguments = remember("다른 사람", "다른 사람의 계좌는 국민은행이다", null);
        arguments.put("memory_id", memoryId);

        JsonNode result = call(arguments);

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).startsWith(UPDATE_PROPOSED);
        assertThat(memories.findById(memoryId).orElseThrow().content()).isEqualTo("다른 사람은 홍길동이다");
        assertThat(memoriesOfDad())
            .filteredOn(memory -> !memory.id().equals(memoryId))
            .extracting(Memory::status)
            .containsExactly(MemoryStatus.PROPOSED);
    }


    @Test
    @DisplayName("질문에만 민감한 낱말이 있으면 제목과 본문만 보므로 바로 저장한다")
    void remembersWhenOnlyQuestionLooksSensitive() throws Exception {
        askedInThisTurn("어제 병원에 다녀왔어. 다른 사람은 홍길동이야.");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이야", null)))).startsWith(REMEMBERED);

        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.ACCEPTED);
    }


    /** 판단 피드백을 {@code 종류|주체} 로 남긴 순서대로 읽는다. */
    @Test
    @DisplayName("사람이 보낸 turn 에서 요약과 함께 남기면 바로 저장되고 다음 조립의 요약 구역에 요약이 들어간다")
    void remembersSummaryIntoSummarySection() throws Exception {
        askedInThisTurn();

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null).put("summary", "다른 사람 홍길동"))))
            .startsWith(REMEMBERED);

        Memory saved = onlyMemory();
        assertThat(saved.summary()).isEqualTo("다른 사람 홍길동");
        assertThat(assembler.assemble(currentDad(), dadRun.agentId()).instructions())
            .contains("- [" + saved.id() + "] 다른 사람: 다른 사람 홍길동");
    }


    @Test
    @DisplayName("바깥 글을 읽은 대화는 제안이고 제안에는 요약을 두지 않아 받아들이기 전에도 뒤에도 요약 구역에 없다")
    void proposesSummaryAfterExternalRead() throws Exception {
        askedInThisTurn();
        toolStarted("web_search");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null).put("summary", "제안요약-표식-6610"))))
            .isEqualTo(PROPOSED);

        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(onlyMemory().summary()).isEmpty();
        assertThat(Objects.toString(assembler.assemble(currentDad(), dadRun.agentId()).instructions(), ""))
            .doesNotContain("제안요약-표식-6610");
        memoryService.accept(currentDad(), onlyMemory().id());
        assertThat(Objects.toString(assembler.assemble(currentDad(), dadRun.agentId()).instructions(), ""))
            .doesNotContain("제안요약-표식-6610");
    }


    @Test
    @DisplayName("요약에만 민감해 보이는 글이 있거나 요약에만 부정이 뒤집혔으면 제안이고, 201자 요약은 거절한다")
    void guardsSummaryLikeContent() throws Exception {
        askedInThisTurn();

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null).put("summary", "홍길동 계좌 있음"))))
            .isEqualTo(PROPOSED);
        assertThat(text(call(remember("다른 사람 둘", "다른 사람은 홍길동이다", null).put("summary", "홍길동은 안 온다"))))
            .isEqualTo(PROPOSED);
        JsonNode tooLong = call(remember("다른 사람 셋", "다른 사람은 홍길동이다", null).put("summary", "가".repeat(201)));
        assertThat(tooLong.path("isError").asBoolean()).isTrue();
        assertThat(text(tooLong)).isEqualTo("summary 는 200자까지다.\n다음 방법: 길이에 맞게 고쳐 다시 부른다");
    }


    @Test
    @DisplayName("memory_id 로 고칠 때 요약을 주지 않으면 지금 요약을 두고, 같은 본문에 다른 요약이면 요약만 바꾼 revision 을 남기며, 빈 글이면 지운다")
    void keepsChangesOrClearsSummaryOnUpdate() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", null).put("summary", "첫 요약"));
        Memory created = onlyMemory();

        call(remember("다른 사람", "다른 사람은 홍길동이고 서울에 산다", null).put("memory_id", created.id()));
        assertThat(onlyMemory().summary()).isEqualTo("첫 요약");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이고 서울에 산다", null)
            .put("memory_id", created.id()).put("summary", "둘째 요약")))).startsWith("기존 기억을 고쳤다.");
        assertThat(onlyMemory().summary()).isEqualTo("둘째 요약");
        assertThat(memoryService.revisionsOf(currentDad(), created.id()))
            .extracting(MemoryRevision::summary)
            .containsExactly("첫 요약", "첫 요약");

        // 한 실행이 남기는 기록에는 상한(3)이 있어 새 turn 에서 고친다
        String nextRoot = askedInNewRootRun();
        call(remember("다른 사람", "다른 사람은 홍길동이고 서울에 산다", null).put("memory_id", created.id()).put("summary", ""), nextRoot);
        assertThat(onlyMemory().summary()).isEmpty();
    }


    @Test
    @DisplayName("같은 제목과 본문의 기존 제안은 요약이 같을 때만 바로 저장 호출이 받아들인다")
    void acceptsExistingProposalOnlyWithSameSummary() throws Exception {
        captureService.remember(currentDad(), MemoryAccess.of(Set.of("core")), new MemoryRememberRequest(
            "다른 사람", "다른 사람은 홍길동이다", "홍길동 통장 있음", null, false, null, 990_101L, null, false, false, false));
        askedInThisTurn();

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null).put("summary", "깨끗한 요약"))))
            .startsWith("같은 제안이 이미 있다");
        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null)))).startsWith(REMEMBERED);
        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.ACCEPTED);
        assertThat(onlyMemory().summary()).isEmpty();
    }


    @Test
    @DisplayName("되돌리기는 이전 revision 의 요약도 되돌린다")
    void undoRestoresSummary() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", null).put("summary", "첫 요약"));
        Memory created = onlyMemory();
        call(remember("다른 사람", "다른 사람은 홍길동이고 서울에 산다", null)
            .put("memory_id", created.id()).put("summary", "둘째 요약"));
        MemoryCapture updated = capturesOfDad().stream()
            .filter(capture -> capture.kind() == MemoryCaptureKind.UPDATED)
            .findFirst()
            .orElseThrow();

        captureService.undo(currentDad(), updated.id());

        assertThat(onlyMemory().summary()).isEqualTo("첫 요약");
        assertThat(onlyMemory().content()).isEqualTo("다른 사람은 홍길동이다");
    }


    private List<String> feedbackEvents() {
        return jdbc
            .queryForList(
                "SELECT event_type, actor FROM decision_feedback_event WHERE user_id = ? ORDER BY id", dad.id())
            .stream()
            .map(row -> row.get("EVENT_TYPE") + "|" + row.get("ACTOR"))
            .toList();
    }


    @Test
    @DisplayName("사람이 보낸 질문이 이어지지 않은 실행은 본문이 맞아도 제안이다")
    void proposesFromRunWithoutQuestion() throws Exception {
        messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), QUESTION, Instant.now()));

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("바깥 도구를 시작한 실행은 제안이고 Control Plane 읽기 도구만 부른 실행은 바로 저장한다")
    void proposesAfterOutsideTool() throws Exception {
        askedInThisTurn();
        toolStarted("mcp__fos_assistant__memory_read");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .startsWith(REMEMBERED);

        toolStarted("web_search");
        assertThat(text(call(remember("사는 곳", "서울에 산다", "홍길동이야")))).isEqualTo(PROPOSED);
        assertThat(capturesOfDad())
            .as("바깥 글을 읽은 뒤의 제안 기록만 externalRead 가 참이다")
            .extracting(MemoryCapture::kind, MemoryCapture::externalRead)
            .containsExactlyInAnyOrder(
                tuple(MemoryCaptureKind.CREATED, false),
                tuple(MemoryCaptureKind.PROPOSED, true));
    }


    @Test
    @DisplayName("기억 찾기만 부른 대화는 바깥 글을 읽지 않은 것이라 바로 저장하고 요약이 요약 구역에 들어간다")
    void remembersAfterMemorySearchOnly() throws Exception {
        // 운영 확인에서 기억 찾기만 세 번 부른 대화의 기억이 「바깥 글」 표시를 단 제안이 되었고, 받아들인 뒤에도 요약이 없어 memory_read 를 불렀다
        askedInThisTurn();
        toolStarted("mcp__fos_assistant__memory_search");
        toolStarted("mcp__fos_assistant__memory_search");
        toolStarted("mcp__fos_assistant__memory_search");

        assertThat(text(call(remember("자전거 이름", "자전거 이름은 파랑돌이다", null).put("summary", "자전거 이름 파랑돌"))))
            .startsWith(REMEMBERED);

        Memory saved = onlyMemory();
        assertThat(saved.status()).isEqualTo(MemoryStatus.ACCEPTED);
        assertThat(capturesOfDad())
            .extracting(MemoryCapture::externalRead)
            .containsExactly(false);
        assertThat(assembler.assemble(currentDad(), dadRun.agentId()).instructions())
            .contains("- [" + saved.id() + "] 자전거 이름: 자전거 이름 파랑돌");
    }


    @Test
    @DisplayName("붙은 커넥터의 READ 도구를 시작한 대화의 기억은 근거가 질문에 있어도 제안이다")
    void proposesAfterConnectorReadTool() throws Exception {
        // docs/features/connector-policy.md 의 RF-13 이다. 메일 본문의 숨은 지시가 사용자의 말을 근거로 내세워도 바로 저장되지 않는다.
        askedInThisTurn();
        toolStarted("mcp__gmail__get_message");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("Hermes 도구 정의 검색과 설명을 읽어도 바로 저장하고 중계 실행은 제안이다")
    void remembersAfterToolDiscoveryButProposesAfterRelay() throws Exception {
        askedInThisTurn();
        toolStarted("tool_search");
        toolStarted("tool_describe");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .startsWith(REMEMBERED);

        toolStarted("tool_call");
        assertThat(text(call(remember("사는 곳", "서울에 산다", "홍길동이야")))).isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("같은 대화의 앞 실행이 바깥 도구를 썼으면 지금 실행이 쓰지 않았어도 제안이다")
    void proposesWhenEarlierRunReadOutsideText() throws Exception {
        AgentExecution earlier = otherRootRun("run-earlier");
        ChatMessage earlierQuestion = messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "메일 읽어 줘", Instant.now()));
        questions.link(earlier.id(), earlierQuestion.id());
        toolStarted(earlier.id(), ExecutionEventType.TOOL_STARTED, "mcp__gmail__read_message");
        askedInThisTurn();

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("하위 에이전트를 시작한 실행은 제안이다")
    void proposesAfterSubagent() throws Exception {
        askedInThisTurn();
        toolStarted(dadRun.id(), ExecutionEventType.SUBAGENT_STARTED, null);

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("이름이 없는 도구 시작 사건도 바깥 도구로 본다")
    void proposesAfterNamelessTool() throws Exception {
        askedInThisTurn();
        toolStarted(dadRun.id(), ExecutionEventType.TOOL_STARTED, null);

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("같은 대화에 사람의 질문 없이 보낸 루트 실행(맡긴 일의 결과 turn)이 있으면 제안이다")
    void proposesAfterRunWithoutQuestion() throws Exception {
        askedInThisTurn();
        otherRootRun("run-delivery");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("기능 도입 전의 질문 없는 루트 실행이 대화에 있으면 그 뒤의 질문 turn 도 제안이다")
    void proposesAfterLegacyRunWithoutQuestion() throws Exception {
        otherRootRun("run-legacy");
        String root = askedInNewRootRun();

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null), root))).isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("질문 없는 루트 실행이 없으면 새 root 의 질문 turn 은 바로 저장한다")
    void remembersInNewRootRunWithoutLegacyRun() throws Exception {
        String root = askedInNewRootRun();

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null), root))).startsWith(REMEMBERED);
    }


    @Test
    @DisplayName("질문 메시지 번호가 다른 사람의 메시지를 가리키면 없는 것으로 보고 제안이다")
    void proposesWhenQuestionIsOthers() throws Exception {
        ChatMessage foreign = messages.save(ChatMessage.fromUser(conversation.id(), dad.id() + 100_000, QUESTION, Instant.now()));
        questions.link(dadRun.id(), foreign.id());

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
            .isEqualTo(PROPOSED);
    }


    @Test
    @DisplayName("바로 저장한 항목을 사람이 고친 뒤에는 되돌리지 않는다")
    void refusesUndoAfterHumanEdit() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"));
        MemoryCapture capture = onlyCapture();
        memoryService.update(currentDad(), capture.memoryId(), "다른 사람은 홍길동이고 열 살이다", false, null);

        assertThatThrownBy(() -> captureService.undo(currentDad(), capture.id()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_REVISION_CONFLICT));
        assertThat(memories.findById(capture.memoryId())).isPresent();
    }


    @Test
    @DisplayName("민감한 내용은 바로 저장 조건이어도 제안이고 본문은 암호문이며 중복 키는 평문의 해시가 아니다")
    void proposesSensitiveFacts() throws Exception {
        askedInThisTurn();
        ObjectNode arguments = remember("건강", "다른 사람은 홍길동이고 당뇨가 있다", "다른 사람은 홍길동이야");
        arguments.put("sensitive", true);

        assertThat(text(call(arguments))).isEqualTo(PROPOSED);

        Memory memory = onlyMemory();
        assertThat(memory.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(memory.sealed()).isTrue();
        assertThat(memory.proposalDedupKey())
            .isNotNull()
            .isNotEqualTo(Sha256.hex(memory.ownerUserId() + "\u0000건강\u0000다른 사람은 홍길동이고 당뇨가 있다"));
    }


    @Test
    @DisplayName("그 에이전트가 받지 않는 collection 은 저장하지 않고 오류로 답한다")
    void rejectsCollectionOutsideAgentGrants() throws Exception {
        askedInThisTurn();
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야");
        arguments.put("collection", "health");

        JsonNode result = call(arguments);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(memoriesOfDad()).isEmpty();
    }


    @Test
    @DisplayName("기존 제안을 바로 저장한 기록을 되돌리면 항목을 보존하고 승인 전 상태로 돌아간다")
    void restoresProposalWhenUndoingAcceptance() throws Exception {
        call(remember("다른 사람", "다른 사람은 홍길동이다", null));
        Long memoryId = onlyMemory().id();
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"));
        MemoryCapture accepted = capturesOfDad().stream()
            .filter(capture -> capture.kind() == MemoryCaptureKind.CREATED)
            .findFirst()
            .orElseThrow();
        assertThat(accepted.previousStatus()).isEqualTo(MemoryStatus.PROPOSED);

        captureService.undo(currentDad(), accepted.id());
        captureService.undo(currentDad(), accepted.id());

        Memory restored = memories.findById(memoryId).orElseThrow();
        assertThat(restored.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(restored.content()).isEqualTo("다른 사람은 홍길동이다");
        assertThat(restored.acceptedByUserId()).isNull();
        assertThat(restored.acceptedAt()).isNull();
        assertThat(captures.findById(accepted.id()).orElseThrow().undone()).isTrue();
        assertThat(feedbackEvents())
            .as("받아들임을 무른 것을 사용자의 마지막 결정으로 한 번만 남긴다")
            .containsExactly("SURFACED|AGENT", "ACCEPTED|USER", "DISMISSED|USER");
    }


    @Test
    @DisplayName("대체한 새 기억이 되돌리기로 제안에 돌아가면 대신한 칸이 비워져 옛 기억이 다시 실린다")
    void clearsSupersedeWhenUndoingAcceptance() throws Exception {
        Memory old = memoryService.create(currentDad(), MemoryScope.USER, "사는 곳", "서울에 산다", false);
        call(remember("이사한 곳", "부산에 산다", null));
        Long fresh = memoriesOfDad().stream().filter(memory -> !memory.id().equals(old.id())).findFirst().orElseThrow().id();
        askedInThisTurn("나 부산에 살아");
        call(remember("이사한 곳", "부산에 산다", "나 부산에 살아"));
        MemorySupersedeRows.supersede(jdbc, old.id(), fresh);
        assertThat(MemorySupersedeRows.supersededBy(jdbc, old.id())).isEqualTo(fresh);
        MemoryCapture accepted = capturesOfDad().stream()
            .filter(capture -> capture.kind() == MemoryCaptureKind.CREATED)
            .findFirst()
            .orElseThrow();

        captureService.undo(currentDad(), accepted.id());

        assertThat(memories.findById(fresh).orElseThrow().status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(MemorySupersedeRows.supersededBy(jdbc, old.id())).as("되돌린 새 기억이 옛 기억을 가리지 않는다").isNull();
    }


    @Test
    @DisplayName("같은 제목과 본문은 한 줄만 남기고 이미 기억하고 있다고 답한다")
    void keepsOneRowForSameFact() throws Exception {
        askedInThisTurn();
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야");

        call(arguments.deepCopy());
        JsonNode second = call(arguments);

        assertThat(text(second)).isEqualTo("이미 같은 내용을 기억하고 있다. 새로 남기지 않았다.");
        assertThat(memoriesOfDad()).hasSize(1);
        assertThat(capturesOfDad()).hasSize(1);
    }


    @Test
    @DisplayName("다른 기억이 대체한 항목과 같은 내용이면 이미 안다고 답하지 않고 쓰이지 않는다고 답하며 memory_id 로도 고치지 않는다")
    void doesNotTreatSupersededFactAsKnown() throws Exception {
        askedInThisTurn();
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야");
        call(arguments.deepCopy());
        Long old = onlyMemory().id();
        Memory fresh = memoryService.create(currentDad(), MemoryScope.USER, "다른 사람 변경", "다른 사람은 김철수다", false);
        MemorySupersedeRows.supersede(jdbc, old, fresh.id());

        JsonNode again = call(arguments.deepCopy());
        ObjectNode update = remember("다른 사람", "다른 사람은 박영희다", "다른 사람은 홍길동이야");
        update.put("memory_id", old);
        JsonNode updated = call(update);

        assertThat(again.path("isError").asBoolean()).isTrue();
        assertThat(text(again)).startsWith("같은 내용의 기억이 있지만 다른 기억이 대신하고 있어 쓰이지 않는다.");
        assertThat(text(updated)).isEqualTo("고칠 기억을 찾지 못했다.\n다음 방법: memory_id 없이 새로 남기고, 고치지 못해 새로 남겼다고 답에 한 줄 알린다");
        assertThat(memories.findById(old).orElseThrow().content()).isEqualTo("다른 사람은 홍길동이다");
    }


    @Test
    @DisplayName("바로 저장 조건이면 memory_id 의 본문을 고치고 판을 올리며 되돌리면 이전 본문으로 돌아간다")
    void updatesAndRestoresExistingFact() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 김철수다", "다른 사람은 홍길동이야"));
        Long memoryId = onlyMemory().id();
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야");
        arguments.put("memory_id", memoryId);

        JsonNode result = call(arguments);

        assertThat(text(result)).startsWith("기존 기억을 고쳤다.");
        Memory updated = memories.findById(memoryId).orElseThrow();
        assertThat(updated.content()).isEqualTo("다른 사람은 홍길동이다");
        assertThat(updated.revision()).isEqualTo(2);

        MemoryCapture update = capturesOfDad().stream()
            .filter(capture -> capture.kind() == MemoryCaptureKind.UPDATED)
            .findFirst()
            .orElseThrow();
        captureService.undo(currentDad(), update.id());

        Memory restored = memories.findById(memoryId).orElseThrow();
        assertThat(restored.content()).isEqualTo("다른 사람은 김철수다");
        assertThat(restored.revision()).isEqualTo(3);
    }


    @Test
    @DisplayName("바깥 글을 읽은 대화의 고치기는 고치기 제안으로 남고 사용자가 받아들이면 새 기억이 옛 기억을 대신한다")
    void proposesUpdateWithoutDirectConditionsAndReplacesOnAccept() throws Exception {
        // 운영에서 웹 검색을 한 대화의 「기억 #54 를 바꿔 줘」 가 「사용자에게 바꿀지 묻는다」 로 실패하고, 사용자가 바꿔 달라고 해도 같은 실패가 되풀이됐다
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 김철수다", "다른 사람은 홍길동이야"));
        Memory old = onlyMemory();
        memoryService.update(currentDad(), old.id(), old.content(), true, null);
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", null);
        arguments.put("memory_id", old.id());
        toolStarted("web_search");

        JsonNode result = call(arguments.deepCopy());
        JsonNode again = call(arguments);

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(text(result)).isEqualTo(UPDATE_PROPOSED + " 옛 기억(번호 " + old.id() + ")을 받아들이면 대신한다.");
        assertThat(text(again)).as("같은 고치기를 다시 불러도 제안을 하나만 두고 실패로 읽히지 않게 대신할 번호를 알린다")
            .isEqualTo("같은 제안이 이미 있다. 사용자가 받아들이기를 기다린다. 옛 기억(번호 " + old.id() + ")을 받아들이면 대신한다.");
        assertThat(memories.findById(old.id()).orElseThrow().content()).isEqualTo("다른 사람은 김철수다");
        List<Memory> proposals = memoriesOfDad().stream()
            .filter(memory -> !memory.id().equals(old.id()))
            .toList();
        assertThat(proposals).hasSize(1);
        Memory proposal = proposals.getFirst();
        assertThat(proposal.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(proposal.retrieval()).as("사람이 켠 항상 싣기를 그대로 둔다").isEqualTo(MemoryRetrieval.ALWAYS);
        assertThat(proposal.collection()).isEqualTo(old.collection());
        assertThat(capturesOfDad())
            .filteredOn(capture -> capture.memoryId().equals(proposal.id()))
            .extracting(MemoryCapture::kind, MemoryCapture::externalRead, MemoryCapture::replacedMemoryIds)
            .containsExactly(tuple(MemoryCaptureKind.PROPOSED, true, List.of(old.id())));

        memoryService.accept(currentDad(), proposal.id());

        assertThat(MemorySupersedeRows.supersededBy(jdbc, old.id())).isEqualTo(proposal.id());
        assertThat(memories.findById(proposal.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACCEPTED);
    }


    @Test
    @DisplayName("같은 글의 일반 제안이 먼저 있어도 고치기 제안을 따로 남겨 받아들이면 옛 기억을 대신한다")
    void proposesUpdateEvenWhenSameGeneralProposalExists() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 김철수다", "다른 사람은 홍길동이야"));
        Long old = onlyMemory().id();
        toolStarted("web_search");
        call(remember("다른 사람", "다른 사람은 홍길동이다", null));
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", null);
        arguments.put("memory_id", old);

        JsonNode result = call(arguments);

        assertThat(text(result)).startsWith(UPDATE_PROPOSED);
        MemoryCapture fix = capturesOfDad().stream()
            .filter(capture -> capture.replacedMemoryIds().equals(List.of(old)))
            .findFirst()
            .orElseThrow();
        memoryService.accept(currentDad(), fix.memoryId());
        assertThat(MemorySupersedeRows.supersededBy(jdbc, old)).isEqualTo(fix.memoryId());
    }


    @Test
    @DisplayName("고치기 제안 뒤 사람이 원본을 고쳤으면 옛 제안을 받아들이지 못하고 원본은 대체되지 않는다")
    void refusesStaleUpdateProposal() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 김철수다", "다른 사람은 홍길동이야"));
        Long old = onlyMemory().id();
        toolStarted("web_search");
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", null);
        arguments.put("memory_id", old);
        call(arguments);
        Long proposal = memoriesOfDad().stream().map(Memory::id).filter(id -> !id.equals(old)).findFirst().orElseThrow();
        memoryService.update(currentDad(), old, "다른 사람은 박영희다", false, null);

        assertThatThrownBy(() -> memoryService.accept(currentDad(), proposal))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_REVISION_CONFLICT));

        assertThat(memories.findById(proposal).orElseThrow().status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(MemorySupersedeRows.supersededBy(jdbc, old)).isNull();
        assertThat(memories.findById(old).orElseThrow().content()).isEqualTo("다른 사람은 박영희다");
    }


    @Test
    @DisplayName("바로 고칠 수 없는 대화에서도 고칠 수 없는 번호는 제안 없이 찾지 못했다고 답한다")
    void refusesUpdateProposalForUnreachableTargets() throws Exception {
        AppUser mom = users.save(AppUser.of("mcp-memory-remember-" + UUID.randomUUID() + "@example.com", "엄마", 1L, UserRole.MEMBER));
        CurrentUser currentMom = new CurrentUser(mom.id(), mom.email(), "엄마", 1L, UserRole.MEMBER);
        Long others = memoryService.create(currentMom, MemoryScope.USER, "엄마 취미", "엄마는 등산을 좋아한다", false).id();
        Long group = memoryService.create(currentDad(), MemoryScope.GROUP, "가족 규칙", "저녁은 7시에 먹는다", false).id();
        Long deleted = memoryService.create(currentDad(), MemoryScope.USER, "옛 취미", "낚시를 한다", false).id();
        memoryService.delete(currentDad(), deleted);
        askedInThisTurn();
        toolStarted("web_search");
        call(remember("새 취미", "테니스를 친다", null));
        Long proposed = memoriesOfDad().stream()
            .filter(memory -> memory.status() == MemoryStatus.PROPOSED)
            .findFirst()
            .orElseThrow()
            .id();
        long before = memories.count();

        for (Long target : List.of(others, group, proposed, deleted)) {
            ObjectNode arguments = remember("바꾼 글", "바꾼 본문 " + target, null);
            arguments.put("memory_id", target);
            assertThat(text(call(arguments))).as("번호 %d", target).startsWith("고칠 기억을 찾지 못했다.");
        }

        assertThat(memories.count()).as("제안을 만들지 않는다").isEqualTo(before);
        assertThat(memories.findById(others).orElseThrow().content()).isEqualTo("엄마는 등산을 좋아한다");
        assertThat(memories.findById(group).orElseThrow().content()).isEqualTo("저녁은 7시에 먹는다");
    }


    @Test
    @DisplayName("고치기 제안을 거절하면 원본은 그대로이고 원본이 지워진 뒤 받아들이면 새 기억만 남는다")
    void keepsTargetOnRejectAndAcceptsAfterTargetDeleted() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 김철수다", "다른 사람은 홍길동이야"));
        Long old = onlyMemory().id();
        call(remember("사는 곳", "서울에 산다", "다른 사람은 홍길동이야"));
        Long home = memoriesOfDad().stream().map(Memory::id).filter(id -> !id.equals(old)).findFirst().orElseThrow();
        toolStarted("web_search");
        ObjectNode first = remember("다른 사람", "다른 사람은 홍길동이다", null);
        first.put("memory_id", old);
        call(first);
        ObjectNode second = remember("사는 곳", "부산에 산다", null);
        second.put("memory_id", home);
        // 한 실행의 상한(3)을 넘지 않게 다음 turn 에서 부른다
        call(second, askedInNewRootRun());
        Long rejected = proposalReplacing(old);
        Long accepted = proposalReplacing(home);

        memoryService.reject(currentDad(), rejected);
        memoryService.delete(currentDad(), home);
        memoryService.accept(currentDad(), accepted);

        assertThat(memories.findById(old).orElseThrow().content()).isEqualTo("다른 사람은 김철수다");
        assertThat(MemorySupersedeRows.supersededBy(jdbc, old)).isNull();
        assertThat(memories.findById(accepted).orElseThrow().status()).isEqualTo(MemoryStatus.ACCEPTED);
    }


    private Long proposalReplacing(Long target) {
        return capturesOfDad().stream()
            .filter(capture -> capture.replacedMemoryIds().equals(List.of(target)))
            .findFirst()
            .orElseThrow()
            .memoryId();
    }


    @Test
    @DisplayName("기억 이전을 맡아 파일을 읽은 대화에서 남긴 본문은 바로 저장하지 않고 제안으로 남는다")
    void proposesFileContentDuringMemoryMigration() throws Exception {
        // 커리어 에이전트가 맥락 문서를 기억으로 옮길 때다. 도구 설명이 남겨도 된다고 알려도 판정은 실행의 출처로만 정한다
        askedInThisTurn("작업 폴더의 맥락 문서를 기억으로 옮겨 줘");
        toolStarted("read_file");

        JsonNode result = call(remember("경력 요약", "백엔드 개발 8년, 결제와 정산 시스템을 맡았다", null)
            .put("summary", "백엔드 8년, 결제와 정산"));

        assertThat(text(result)).isEqualTo(PROPOSED);
        Memory saved = onlyMemory();
        assertThat(saved.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(onlyCapture().externalRead()).isTrue();
    }


    @Test
    @DisplayName("한 실행에서 셋을 남기면 넷째는 저장하지 않는다")
    void limitsCapturesPerExecution() throws Exception {
        for (int i = 0; i < 3; i++) {
            call(remember("사실 " + i, "본문 " + i, null));
        }

        JsonNode fourth = call(remember("사실 3", "본문 3", null));

        assertThat(fourth.path("isError").asBoolean()).isTrue();
        assertThat(text(fourth)).isEqualTo("이번 답에서 이미 3개를 남겼다. 더 남기지 않는다.\n사용자에게 바로 알린다.");
        assertThat(memoriesOfDad()).hasSize(3);
    }


    @Test
    @DisplayName("대화의 기록 목록은 되돌린 기록을 빼고, 새로 만든 항목을 되돌리면 그 항목을 지운다")
    void listsAndUndoesCreatedCapture() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"));
        call(remember("취미", "등산을 좋아하지 않는다", null));

        List<CapturedMemory> listed = captureService.capturesOf(currentDad(), conversation.id());
        assertThat(listed)
            .extracting(captured -> captured.capture().kind())
            .containsExactly(MemoryCaptureKind.CREATED, MemoryCaptureKind.PROPOSED);

        CapturedMemory created = listed.getFirst();
        captureService.undo(currentDad(), created.capture().id());

        assertThat(memories.findById(created.memory().id())).isEmpty();
        assertThat(captureService.capturesOf(currentDad(), conversation.id()))
            .extracting(captured -> captured.capture().kind())
            .containsExactly(MemoryCaptureKind.PROPOSED);
        CapturedMemory proposal = captureService.capturesOf(currentDad(), conversation.id()).getFirst();
        assertThatThrownBy(() -> captureService.undo(currentDad(), proposal.capture().id()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_REVISION_CONFLICT));
    }


    @Test
    @DisplayName("남의 기록은 되돌리지 못하고 없는 기록과 같은 응답이다")
    void hidesOthersCapture() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"));
        MemoryCapture capture = onlyCapture();
        CurrentUser other = new CurrentUser(dad.id() + 100_000, "other@example.com", "남", 1L, UserRole.MEMBER);

        assertThatThrownBy(() -> captureService.undo(other, capture.id()))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
    }


    @Test
    @DisplayName("모르는 키나 사용자를 바꾸려는 키는 인자 오류다")
    void rejectsUnknownArguments() throws Exception {
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", null);
        arguments.put("user_id", 1);

        JsonNode response = body(send(signed(arguments)));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(memoriesOfDad()).isEmpty();
    }


    private void askedInThisTurn() {
        askedInThisTurn(QUESTION);
    }


    private void askedInThisTurn(String text) {
        ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), text, Instant.now()));
        questions.link(dadRun.id(), question.id());
    }


    /** 새 root 의 실행을 만들고 사람의 질문을 잇는다. 그 root 를 돌려준다. */
    private String askedInNewRootRun() {
        String root = McpCallSigner.newRoot();
        AgentExecution run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root);
        ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), QUESTION, Instant.now()));
        questions.link(run.id(), question.id());
        return root;
    }


    private void toolStarted(String toolName) {
        toolStarted(dadRun.id(), ExecutionEventType.TOOL_STARTED, toolName);
    }


    private void toolStarted(Long executionId, ExecutionEventType type, String toolName) {
        events.save(ExecutionEvent.builder()
            .executionId(executionId)
            .sequence((int)events.count() + 1000)
            .eventType(type)
            .toolName(toolName)
            .occurredAt(Instant.now())
            .build());
    }


    /** 같은 대화에서 Hermes 로 보낸 다른 루트 실행이다. 질문 메시지는 잇지 않는다. */
    private AgentExecution otherRootRun(String runId) {
        return executions.save(AgentExecution.builder()
            .userId(dad.id())
            .agentId(dadRun.agentId())
            .conversationId(conversation.id())
            .profileName(PROFILE)
            .hermesSessionId(McpCallSigner.newRoot())
            .hermesRunId(runId)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(Instant.now())
            .build());
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


    private ObjectNode remember(String title, String content, String evidence) {
        ObjectNode arguments = json.createObjectNode().put("title", title).put("content", content);
        if (evidence != null) {
            arguments.put("evidence", evidence);
        }
        return arguments;
    }


    private List<Memory> memoriesOfDad() {
        return memories.findAll().stream()
            .filter(memory -> dad.id().equals(memory.ownerUserId()))
            .toList();
    }


    private List<MemoryCapture> capturesOfDad() {
        return captures.findAll().stream()
            .filter(capture -> dad.id().equals(capture.userId()))
            .toList();
    }


    private Memory onlyMemory() {
        List<Memory> rows = memoriesOfDad();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }


    private MemoryCapture onlyCapture() {
        List<MemoryCapture> rows = capturesOfDad();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }


    private JsonNode call(ObjectNode arguments) throws Exception {
        return call(arguments, dadRoot);
    }


    private JsonNode call(ObjectNode arguments, String root) throws Exception {
        return body(send(signed(arguments, root))).path("result");
    }


    private String signed(ObjectNode arguments) {
        return signed(arguments, dadRoot);
    }


    private String signed(ObjectNode arguments, String root) {
        return McpCallSigner.withContext(json.writeValueAsString(toolCall(arguments)), dadToken, root);
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


    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("본문: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }


    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }
}
