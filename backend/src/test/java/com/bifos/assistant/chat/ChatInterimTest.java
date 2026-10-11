package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.ToolDetailScope;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.HermesInterimWire;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

@BackendIntegrationTest
class ChatInterimTest {
    @Autowired
    ChatService chat;
    @Autowired
    ConversationAccess access;
    @Autowired
    AppUserRepository users;
    @Autowired
    AgentRepository agents;
    @Autowired
    ChatMessageRepository messages;
    @Autowired
    AgentExecutionRepository executions;
    @Autowired
    ExecutionEventRepository executionEvents;
    @Autowired
    HermesRunsClient hermes;
    @Autowired
    HermesRunEventStream eventStream;

    private CurrentUser user;
    private String agentCode;


    @BeforeEach
    void setUp() {
        ((StubHermesRunsClient)hermes).reset();
        agentCode = "interim-" + UUID.randomUUID().toString().substring(0, 8);
        var saved = users.save(AppUser.of(agentCode + "@example.com", "안내 검사", 1L, UserRole.MEMBER, Instant.now()));
        user = new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
        agents.save(Agent.of(agentCode, agentCode, agentCode, "http://agent-runtime.test",
            CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE,
            user.id(), Instant.now()));
    }


    private List<ChatEvent> stream(String wire, String status, String output) {
        doAnswer(invocation -> {
            Consumer<RunEvent> consumer = invocation.getArgument(3);
            HermesInterimWire.read(wire, invocation.getArgument(5), consumer);
            return null;
        }).when(eventStream).open(any(), any(), any(), any(), any(), any(ToolDetailScope.class));
        ((StubHermesRunsClient)hermes).willReturn(HermesRunResult.of(
            "fixture-run", "fixture-session", status, output, "model", "provider", TokenUsage.empty()));
        List<ChatEvent> events = new ArrayList<>();
        chat.stream(user, null, "안내와 답을 나눠 주세요", agentCode, events::add);
        return events;
    }


    @Test
    @DisplayName("실제 wire를 채팅으로 전달하지만 중간 안내는 답과 실행 사건과 사용량에 저장하지 않는다")
    void forwardsWireInterimAndStoresOnlyFinalAnswer() throws IOException {
        var events = stream(HermesInterimWire.fixture(), "completed", "확인한 최종 답이에요.");
        assertThat(events).filteredOn(event -> "interim".equals(event.type()))
            .extracting(ChatEvent::text).containsExactly("자료를 먼저 확인할게요.", "자료를 먼저 확인할게요.",
                "두 번째 안내", "일부만 흘린 안내도 전부 보여요.");
        var done = events.getLast();
        assertThat(done.type()).isEqualTo("done");
        assertStoredAnswer(done, "확인한 최종 답이에요.");
        var storedEvents = executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(done.executionId()));
        assertThat(storedEvents).extracting(event -> event.eventType()).containsExactly(
            ExecutionEventType.RUN_STARTED, ExecutionEventType.TOOL_STARTED,
            ExecutionEventType.TOOL_COMPLETED, ExecutionEventType.RUN_COMPLETED);
        assertThat(storedEvents).extracting(event -> event.sequence()).containsExactly(1, 2, 3, 4);
        assertThat(storedEvents.toString()).doesNotContain("먼저 확인", "두 번째 안내", "흘린 안내");
        assertThat(executions.findById(done.executionId()).orElseThrow().outputTokens()).isNull();
    }


    @Test
    @DisplayName("중지 결과가 비면 안내로 확인한 전체와 일부 delta만 빼고 남은 답 조각을 저장한다")
    void excludesConfirmedInterimFromStoppedFallback() throws IOException {
        var events = stream(HermesInterimWire.fixture(), "cancelled", "");
        assertThat(events.getLast().type()).isEqualTo("stopped");
        assertStoredAnswer(events.getLast(), "확인한 최종 답이에요.");
    }


    @Test
    @DisplayName("안내만 있는 실행을 중지하면 일반 assistant 메시지를 만들지 않는다")
    void createsNoAssistantForInterimOnlyStop() {
        var events = stream("""
                data: {"event":"message.delta","delta":"지금 확인할게요."}

                data: {"event":"message.interim","text":"지금 확인할게요. 기다려 주세요.","already_streamed":true}

                """, "cancelled", "");
        assertThat(events.getLast().messageId()).isNull();
        assertThat(messages.findByConversationIdOrderByIdAsc(access.requireOwnId(user, events.getLast().conversationId())))
            .noneMatch(message -> message.role() == MessageRole.ASSISTANT);
    }


    @Test
    @DisplayName("안내와 같은 문장이 최종 답에도 있어도 Hermes의 최종 output을 바꾸지 않는다")
    void preservesAuthoritativeOutputWithoutGlobalReplacement() throws IOException {
        var events = stream(HermesInterimWire.fixture(), "completed", "자료를 먼저 확인할게요. 이 문장이 최종 답에도 있어요.");
        assertStoredAnswer(events.getLast(), "자료를 먼저 확인할게요. 이 문장이 최종 답에도 있어요.");
    }


    @Test
    @DisplayName("안내 재수신 뒤 중지해도 같은 접두사의 최종 delta를 부분 답으로 남긴다")
    void keepsStoppedFinalDeltaAfterInterimReplay() {
        var events = stream("""
                data: {"event":"message.delta","delta":"확인 중"}

                data: {"event":"message.interim","text":"확인 중","already_streamed":true,"timestamp":1791630000.1}

                data: {"event":"message.delta","delta":"확인 중"}

                data: {"event":"message.interim","text":"확인 중","already_streamed":true,"timestamp":1791630000.1}

                """, "cancelled", "");
        assertStoredAnswer(events.getLast(), "확인 중");
    }


    @Test
    @DisplayName("안내 뒤 실패한 실행은 안내를 저장하지 않고 다음 시도의 재분류 상태를 새로 시작한다")
    void excludesFailedInterimAndStartsFreshRetry() throws IOException {
        String wire = HermesInterimWire.fixture();
        assertThatThrownBy(() -> stream(wire, "failed", "")).isInstanceOf(ApiException.class);
        var failed = executions.findByUserIdOrderByIdDesc(user.id(), PageRequest.of(0, 1)).getFirst();
        assertThat(messages.findByConversationIdOrderByIdAsc(failed.conversationId()))
            .noneMatch(message -> message.role() == MessageRole.ASSISTANT);
        assertThat(executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(failed.id())))
            .extracting(event -> event.eventType()).containsExactly(ExecutionEventType.RUN_STARTED,
                ExecutionEventType.TOOL_STARTED, ExecutionEventType.TOOL_COMPLETED, ExecutionEventType.RUN_FAILED);
        var retry = stream(wire, "completed", "재시도 답");
        assertThat(retry).filteredOn(event -> "interim".equals(event.type()))
            .extracting(ChatEvent::streamedPrefixLength).containsExactly(0, 0, "두 번째  안내".length(), "일부만 ".length());
        assertStoredAnswer(retry.getLast(), "재시도 답");
    }


    @Test
    @DisplayName("같은 timestamp의 다른 안내를 각각 재분류하고 가린 본문이 같아도 번호와 부분 답은 보존한다")
    void reclassifiesDifferentInterimsAtSameTimestamp() {
        var events = stream("""
                data: {"event":"message.delta","delta":"token=secret-one"}

                data: {"event":"message.interim","timestamp":1.1,"text":"token=secret-one","already_streamed":true}

                data: {"event":"message.delta","delta":"token=secret-two"}

                data: {"event":"message.interim","timestamp":1.1,"text":"token=secret-two","already_streamed":true}

                data: {"event":"message.interim","timestamp":1.1,"text":"token=secret-one","already_streamed":true}

                data: {"event":"message.delta","delta":"안전한 부분 답"}

                """, "cancelled", "");
        assertThat(events).filteredOn(event -> "interim".equals(event.type()))
            .extracting(ChatEvent::interimId).containsExactly("1", "2", "1");
        assertThat(events).filteredOn(event -> "interim".equals(event.type()))
            .extracting(ChatEvent::text).containsOnly("token=[가림]");
        assertStoredAnswer(events.getLast(), "안전한 부분 답");
    }


    @Test
    @DisplayName("64KiB보다 긴 확인된 안내는 중지 fallback에서 빼고 남은 답 조각만 저장한다")
    void excludesLargeConfirmedInterimFromStoppedFallback() {
        String text = "긴 안내 ".repeat(15_000);
        for (String partial : List.of("", "안전한 부분 답")) {
            var events = stream("data: {\"event\":\"message.delta\",\"delta\":\"" + text + "\"}\n\n"
                + "data: {\"event\":\"message.interim\",\"text\":\"" + text + "\",\"already_streamed\":true}\n\n"
                + "data: {\"event\":\"message.delta\",\"delta\":\"" + partial + "\"}\n\n", "cancelled", "");
            var stopped = events.getLast();
            if (partial.isEmpty()) {
                assertThat(stopped.messageId()).isNull();
                assertThat(messages.findByConversationIdOrderByIdAsc(access.requireOwnId(user, stopped.conversationId())))
                    .noneMatch(message -> message.role() == MessageRole.ASSISTANT);
            } else {
                assertStoredAnswer(stopped, partial);
            }
        }
    }


    private void assertStoredAnswer(ChatEvent done, String answer) {
        assertThat(messages.findByConversationIdOrderByIdAsc(access.requireOwnId(user, done.conversationId())))
            .filteredOn(message -> message.role() == MessageRole.ASSISTANT)
            .singleElement().satisfies(message -> assertThat(message.content()).isEqualTo(answer));
    }
}
