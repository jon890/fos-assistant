package com.bifos.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ExecutionQuestion;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ExecutionQuestionRepository;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.memory.application.MemoryCaptureService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.model.CapturedMemory;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCapture;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
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
 * 실제 HTTP 경계에서 {@code memory_remember} 의 계약을 확인한다. 계약은 {@code docs/backend/memory.md} 의 「에이전트가 기억을
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
    ExecutionQuestionRepository questions;

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
        dad = users.save(AppUser.of(email, "아빠", 1L, UserRole.ADMIN, Instant.now()));
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
    @DisplayName("도구 목록의 마지막이 memory_remember 이고 title 과 content 가 필수다")
    void listsMemoryRememberLast() throws Exception {
        JsonNode tools = body(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .path("result")
                .path("tools");

        JsonNode tool = tools.get(tools.size() - 1);
        assertThat(tool.path("name").asString()).isEqualTo(TOOL);
        assertThat(tool.path("inputSchema").path("required").toString()).isEqualTo("[\"title\",\"content\"]");
        assertThat(tool.path("inputSchema").path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(tool.path("description").asString()).contains("작업 기록").contains("부정은 content 에 그대로 살린다");
    }

    @Test
    @DisplayName("사람이 보낸 turn 에서 부정이 뒤집히지 않은 본문은 바로 ACCEPTED 로 저장하고 기록을 남긴다")
    void remembersDirectlyFromHumanTurn() throws Exception {
        askedInThisTurn();

        JsonNode result = call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"));

        assertThat(text(result)).isEqualTo(REMEMBERED);
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

        assertThat(text(call(remember("취미", "등산을 좋아한다", null)))).isEqualTo(REMEMBERED);
        assertThat(text(call(remember("직업", "교사다", "나는 교사야")))).isEqualTo(REMEMBERED);

        assertThat(memoriesOfDad())
                .extracting(Memory::status)
                .containsExactly(MemoryStatus.ACCEPTED, MemoryStatus.ACCEPTED);
        assertThat(capturesOfDad())
                .extracting(MemoryCapture::kind)
                .containsExactly(MemoryCaptureKind.CREATED, MemoryCaptureKind.CREATED);
        assertThat(feedbackEvents()).as("바로 저장은 판단 피드백을 남기지 않는다").isEmpty();
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

        assertThat(text(call(remember("오이", "오이를 먹지 않는다", null)))).isEqualTo(REMEMBERED);

        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.ACCEPTED);
    }

    @Test
    @DisplayName("모델이 sensitive 를 주지 않아도 본문이 민감해 보이면 일반 민감도의 제안으로 내린다")
    void proposesSensitiveLookingContent() throws Exception {
        askedInThisTurn("아빠 계좌는 국민은행이야.");

        assertThat(text(call(remember("아빠 계좌", "아빠 계좌는 국민은행이야", null)))).isEqualTo(PROPOSED);

        Memory memory = onlyMemory();
        assertThat(memory.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(memory.sensitivity()).isEqualTo(MemorySensitivity.NORMAL);
    }

    @Test
    @DisplayName("바로 저장한 항목을 민감해 보이는 본문으로 고치려 하면 고치지 않고 확인하라고 답한다")
    void refusesUpdateToSensitiveLookingContent() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", null));
        Long memoryId = onlyMemory().id();
        ObjectNode arguments = remember("다른 사람", "다른 사람의 계좌는 국민은행이다", null);
        arguments.put("memory_id", memoryId);

        JsonNode result = call(arguments);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).startsWith("지금은 기존 기억을 고칠 수 없다");
        assertThat(memories.findById(memoryId).orElseThrow().content()).isEqualTo("다른 사람은 홍길동이다");
    }

    @Test
    @DisplayName("질문에만 민감한 낱말이 있으면 제목과 본문만 보므로 바로 저장한다")
    void remembersWhenOnlyQuestionLooksSensitive() throws Exception {
        askedInThisTurn("어제 병원에 다녀왔어. 다른 사람은 홍길동이야.");

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이야", null)))).isEqualTo(REMEMBERED);

        assertThat(onlyMemory().status()).isEqualTo(MemoryStatus.ACCEPTED);
    }

    /** 판단 피드백을 {@code 종류|주체} 로 남긴 순서대로 읽는다. */
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
                .isEqualTo(REMEMBERED);

        toolStarted("web_search");
        assertThat(text(call(remember("사는 곳", "서울에 산다", "홍길동이야")))).isEqualTo(PROPOSED);
    }

    @Test
    @DisplayName("붙은 커넥터의 READ 도구를 시작한 대화의 기억은 근거가 질문에 있어도 제안이다")
    void proposesAfterConnectorReadTool() throws Exception {
        // docs/read-data-flow.md 의 RF-13 이다. 메일 본문의 숨은 지시가 사용자의 말을 근거로 내세워도 바로 저장되지 않는다.
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
                .isEqualTo(REMEMBERED);

        toolStarted("tool_call");
        assertThat(text(call(remember("사는 곳", "서울에 산다", "홍길동이야")))).isEqualTo(PROPOSED);
    }

    @Test
    @DisplayName("같은 대화의 앞 실행이 바깥 도구를 썼으면 지금 실행이 쓰지 않았어도 제안이다")
    void proposesWhenEarlierRunReadOutsideText() throws Exception {
        AgentExecution earlier = otherRootRun("run-earlier");
        questions.save(ExecutionQuestion.of(
                earlier.id(),
                messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "메일 읽어 줘", Instant.now()))
                        .id(),
                Instant.now()));
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

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", null), root))).isEqualTo(REMEMBERED);
    }

    @Test
    @DisplayName("질문 줄이 다른 사람의 메시지를 가리키면 없는 줄로 보고 제안이다")
    void proposesWhenQuestionIsOthers() throws Exception {
        ChatMessage foreign =
                messages.save(ChatMessage.fromUser(conversation.id(), dad.id() + 100_000, QUESTION, Instant.now()));
        questions.save(ExecutionQuestion.of(dadRun.id(), foreign.id(), Instant.now()));

        assertThat(text(call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"))))
                .isEqualTo(PROPOSED);
    }

    @Test
    @DisplayName("바로 저장한 항목을 사람이 고친 뒤에는 되돌리지 않는다")
    void refusesUndoAfterHumanEdit() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 홍길동이다", "다른 사람은 홍길동이야"));
        MemoryCapture capture = onlyCapture();
        memoryService.update(currentDad(), capture.memoryId(), "다른 사람은 홍길동이고 열 살이다", false);

        assertThatThrownBy(() -> captureService.undo(currentDad(), capture.id()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_REVISION_CONFLICT));
        assertThat(memories.findById(capture.memoryId())).isPresent();
    }

    @Test
    @DisplayName("민감한 내용은 바로 저장 조건이어도 제안이고 본문은 암호문이며 중복 키를 남기지 않는다")
    void proposesSensitiveFacts() throws Exception {
        askedInThisTurn();
        ObjectNode arguments = remember("건강", "다른 사람은 홍길동이고 당뇨가 있다", "다른 사람은 홍길동이야");
        arguments.put("sensitive", true);

        assertThat(text(call(arguments))).isEqualTo(PROPOSED);

        Memory memory = onlyMemory();
        assertThat(memory.status()).isEqualTo(MemoryStatus.PROPOSED);
        assertThat(memory.sensitivity()).isEqualTo(MemorySensitivity.SENSITIVE);
        assertThat(memory.sealed()).isTrue();
        assertThat(memory.proposalDedupKey()).isNull();
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
    @DisplayName("바로 저장 조건이 아니면 기존 항목을 고치지 않고 사용자에게 확인하라고 답한다")
    void refusesUpdateWithoutDirectConditions() throws Exception {
        askedInThisTurn();
        call(remember("다른 사람", "다른 사람은 김철수다", "다른 사람은 홍길동이야"));
        Long memoryId = onlyMemory().id();
        ObjectNode arguments = remember("다른 사람", "다른 사람은 홍길동이다", null);
        arguments.put("memory_id", memoryId);
        toolStarted("web_search");

        JsonNode result = call(arguments);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).isEqualTo("지금은 기존 기억을 고칠 수 없다. 사용자에게 바꿀지 묻는다.");
        assertThat(memories.findById(memoryId).orElseThrow().content()).isEqualTo("다른 사람은 김철수다");
    }

    @Test
    @DisplayName("한 실행에서 셋을 남기면 넷째는 저장하지 않는다")
    void limitsCapturesPerExecution() throws Exception {
        for (int i = 0; i < 3; i++) {
            call(remember("사실 " + i, "본문 " + i, null));
        }

        JsonNode fourth = call(remember("사실 3", "본문 3", null));

        assertThat(fourth.path("isError").asBoolean()).isTrue();
        assertThat(text(fourth)).isEqualTo("이번 답에서 이미 3개를 남겼다. 더 남기지 않는다.");
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
        CapturedMemory proposal =
                captureService.capturesOf(currentDad(), conversation.id()).getFirst();
        assertThatThrownBy(() ->
                        captureService.undo(currentDad(), proposal.capture().id()))
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
        questions.save(ExecutionQuestion.of(dadRun.id(), question.id(), Instant.now()));
    }

    /** 새 root 의 실행을 만들고 사람의 질문을 잇는다. 그 root 를 돌려준다. */
    private String askedInNewRootRun() {
        String root = McpCallSigner.newRoot();
        AgentExecution run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, root);
        ChatMessage question =
                messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), QUESTION, Instant.now()));
        questions.save(ExecutionQuestion.of(run.id(), question.id(), Instant.now()));
        return root;
    }

    private void toolStarted(String toolName) {
        toolStarted(dadRun.id(), ExecutionEventType.TOOL_STARTED, toolName);
    }

    private void toolStarted(Long executionId, ExecutionEventType type, String toolName) {
        events.save(ExecutionEvent.builder()
                .executionId(executionId)
                .sequence((int) events.count() + 1000)
                .eventType(type)
                .toolName(toolName)
                .occurredAt(Instant.now())
                .build());
    }

    /** 같은 대화에서 Hermes 로 보낸 다른 루트 실행이다. 질문 줄은 잇지 않는다. */
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
                "DELETE FROM execution_question WHERE execution_id IN (SELECT id FROM agent_execution WHERE profile_name = ?)",
                PROFILE);
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
