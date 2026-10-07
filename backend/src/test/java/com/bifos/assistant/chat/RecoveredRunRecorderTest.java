package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ConversationEventHub;
import com.bifos.assistant.chat.application.DelegationFinished;
import com.bifos.assistant.chat.application.RecoveredRunRecorder;
import com.bifos.assistant.chat.application.model.RecoveredRunKind;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ArtifactProperties;
import com.bifos.assistant.chat.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.orchestration.application.ResearchAndBuildFlow;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.testsupport.SamplePriceCatalog;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * 기동할 때 {@code RUNNING} 으로 남은 실행 줄에 Hermes 의 답을 적는 것을 본다.
 *
 * <p>실행 줄은 이전 프로세스가 남긴 것처럼 저장소로 직접 만든다. Hermes 에 묻는 것은 이 검사가 보지 않으므로
 * 결과를 그대로 넘긴다.
 */
@BackendIntegrationTest
@SamplePriceCatalog
@OverrideProperties("assistant.delegation.output-max-chars=" + RecoveredRunRecorderTest.OUTPUT_MAX_CHARS)
@RecordApplicationEvents
class RecoveredRunRecorderTest {

    static final int OUTPUT_MAX_CHARS = 20;

    private static final TokenUsage USAGE = new TokenUsage(1_000L, 0L, 500L, 1_500L);

    /** 표본 가격표가 아는 provider 와 모델이다. 이 짝으로 끝나야 금액이 적힌다. */
    private static final SessionRuntime PRICED_RUNTIME = new SessionRuntime("example-model-large", "anthropic");

    @Autowired
    HermesRunEventStream eventStream;

    @Autowired
    RecoveredRunRecorder recorder;

    @Autowired
    ConversationEventHub hub;

    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    ArtifactProperties artifactProperties;

    @Autowired
    ChatArtifactRepository artifactRows;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatAttachmentRepository attachmentRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    HermesRunsClient hermes;

    private final List<ChatEvent> received = new CopyOnWriteArrayList<>();
    private Runnable unsubscribe = () -> {};
    private AppUser dad;
    private Agent chief;
    private Agent worker;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        ((StubHermesRunsClient) hermes).reset();
        artifactRows.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();

        dad = users.save(AppUser.of("recovered-dad@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        chief = agents.save(agent("dad", "비서"));
        worker = agents.save(agent("worker", "조사원"));
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        received.clear();
        unsubscribe = hub.subscribe(conversation.id(), received::add);
    }

    @AfterEach
    void tearDown() {
        unsubscribe.run();
    }

    @Test
    @DisplayName("성공으로 끝난 대화 turn 은 사용량과 답과 session 을 적고 done 을 낸다")
    void completedChatTurnRecordsUsageAnswerSessionAndPublishesDone() {
        messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "질문", Instant.now()));
        AgentExecution row = chatTurn(conversation, chief);
        executionEvents.save(ExecutionEvent.builder()
                .executionId(row.id())
                .sequence(1)
                .eventType(ExecutionEventType.RUN_STARTED)
                .occurredAt(Instant.now())
                .build());

        boolean written = recorder.settle(row.id(), result("completed", "끝난 답"));

        assertThat(written).as("RUNNING 줄을 적었다").isTrue();
        AgentExecution saved = executions.findById(row.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(saved.inputTokens()).isEqualTo(1_000L);
        assertThat(saved.outputTokens()).isEqualTo(500L);
        assertThat(saved.totalTokens()).isEqualTo(1_500L);
        assertThat(saved.estimatedCostMicros()).as("가격표가 아는 모델이라 금액을 적는다").isNotNull();
        assertThat(saved.finishedAt()).isNotNull();

        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(history)
                .extracting(ChatMessage::role, ChatMessage::content, ChatMessage::executionId)
                .containsExactly(tuple(MessageRole.USER, "질문", null), tuple(MessageRole.ASSISTANT, "끝난 답", row.id()));
        assertThat(history.getLast().replacesMessageId())
                .as("질문 뒤의 첫 답은 다른 답을 대신하지 않는다")
                .isNull();
        assertThat(conversations.findById(conversation.id()).orElseThrow().hermesSessionId())
                .isEqualTo("sess-1");
        assertThat(eventsOf(row))
                .as("끝 사건은 끊기기 전 마지막 순번 다음이다")
                .extracting(ExecutionEvent::sequence, ExecutionEvent::eventType)
                .containsExactly(tuple(1, ExecutionEventType.RUN_STARTED), tuple(2, ExecutionEventType.RUN_COMPLETED));
        assertThat(received)
                .extracting(ChatEvent::type, ChatEvent::conversationId, ChatEvent::messageId, ChatEvent::executionId)
                .containsExactly(
                        tuple("done", conversation.publicId(), history.getLast().id(), row.id()));
    }

    @Test
    @DisplayName("같은 실행을 한 번 더 적으려 하면 거짓이고 메시지와 사건과 끝난 시각이 그대로다")
    void settlingTwiceWritesOnlyOnce() {
        AgentExecution row = chatTurn(conversation, chief);
        recorder.settle(row.id(), result("completed", "끝난 답"));
        Instant finishedAt = executions.findById(row.id()).orElseThrow().finishedAt();
        received.clear();

        boolean again = recorder.settle(row.id(), result("completed", "다른 답"));

        assertThat(again).as("이미 끝난 줄").isFalse();
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(ChatMessage::content)
                .containsExactly("끝난 답");
        assertThat(eventsOf(row))
                .extracting(ExecutionEvent::eventType)
                .containsExactly(ExecutionEventType.RUN_COMPLETED);
        assertThat(executions.findById(row.id()).orElseThrow().finishedAt()).isEqualTo(finishedAt);
        assertThat(received).as("두 번째에는 알리지 않는다").isEmpty();
    }

    @Test
    @DisplayName("실패로 끝난 대화 turn 은 받은 사용량을 남기고 메시지 없이 error 를 낸다")
    void failedChatTurnKeepsUsageWithoutMessageAndPublishesError() {
        AgentExecution row = chatTurn(conversation, chief);

        boolean written = recorder.settle(row.id(), result("failed", null));

        assertThat(written).isTrue();
        AgentExecution saved = executions.findById(row.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("FAILED");
        assertThat(saved.totalTokens()).isEqualTo(1_500L);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(eventsOf(row))
                .extracting(ExecutionEvent::eventType, ExecutionEvent::detail)
                .containsExactly(tuple(ExecutionEventType.RUN_FAILED, "FAILED"));
        assertThat(received)
                .extracting(ChatEvent::type, ChatEvent::code)
                .containsExactly(tuple("error", "HERMES_RUN_FAILED"));
    }

    @Test
    @DisplayName("취소로 끝난 대화 turn 은 멈춘 자리까지의 답을 남기고 stopped 를 낸다")
    void cancelledChatTurnKeepsPartialAnswerAndPublishesStopped() {
        AgentExecution row = chatTurn(conversation, chief);

        boolean written = recorder.settle(row.id(), result("cancelled", "일부 답"));

        assertThat(written).isTrue();
        assertThat(executions.findById(row.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.CANCELLED);
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(history)
                .extracting(ChatMessage::role, ChatMessage::content, ChatMessage::executionId)
                .containsExactly(tuple(MessageRole.ASSISTANT, "일부 답", row.id()));
        assertThat(received)
                .extracting(ChatEvent::type, ChatEvent::messageId, ChatEvent::executionId)
                .containsExactly(tuple("stopped", history.getFirst().id(), row.id()));
    }

    @Test
    @DisplayName("취소로 끝났는데 받은 답이 없으면 메시지를 만들지 않는다")
    void cancelledChatTurnWithoutAnswerSavesNoMessage() {
        AgentExecution row = chatTurn(conversation, chief);

        boolean written = recorder.settle(row.id(), result("cancelled", ""));

        assertThat(written).isTrue();
        assertThat(executions.findById(row.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(received).extracting(ChatEvent::type, ChatEvent::messageId).containsExactly(tuple("stopped", null));
    }

    @Test
    @DisplayName("실행이 시작한 뒤 대화 폴더에 생긴 HTML 을 새 답에 묶는다")
    void bindsHtmlWrittenAfterExecutionStartToNewAnswer() throws IOException {
        AgentExecution row = chatTurn(conversation, chief);
        Path folder = Path.of(artifactProperties.root()).toAbsolutePath().resolve(String.valueOf(conversation.id()));
        // 메모리 데이터베이스가 다시 떠 대화 번호가 겹치면 앞선 검사가 남긴 폴더가 있다. 비우고 쓴다.
        deleteTree(folder);
        Path file = folder.resolve("a/index.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<!doctype html><title>초안</title><p>초안</p>");
        // 커널의 파일 시각이 JVM 시계보다 늦게 갈 수 있어 수정 시각을 못 박는다.
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()));

        recorder.settle(row.id(), result("completed", "만들었어요"));

        ChatMessage answer =
                messages.findByConversationIdOrderByIdAsc(conversation.id()).getLast();
        assertThat(artifactRows.findByMessageId(answer.id()))
                .extracting(ChatArtifact::conversationId, ChatArtifact::path)
                .containsExactly(tuple(conversation.id(), "a/index.html"));
    }

    @Test
    @DisplayName("마지막 메시지가 답인 대화의 turn 은 그 답을 다시 생성한 것으로 적는다")
    void chatTurnAfterAssistantMessageIsSavedAsRegeneratedAnswer() {
        messages.save(ChatMessage.fromUser(conversation.id(), dad.id(), "질문", Instant.now()));
        ChatMessage previous = messages.save(ChatMessage.fromAssistant(conversation.id(), "앞 답", null, Instant.now()));
        AgentExecution row = chatTurn(conversation, chief);

        recorder.settle(row.id(), result("completed", "새 답"));

        ChatMessage answer =
                messages.findByConversationIdOrderByIdAsc(conversation.id()).getLast();
        assertThat(answer.content()).isEqualTo("새 답");
        assertThat(answer.executionId()).isEqualTo(row.id());
        assertThat(answer.replacesMessageId()).as("앞 답을 대신한다").isEqualTo(previous.id());
    }

    @Test
    @DisplayName("성공으로 끝난 위임 실행은 답을 실행 줄에 적고 끝났음을 알린다")
    void completedDelegationRecordsOutputAndPublishesFinished() {
        AgentExecution row = delegated();

        boolean written = recorder.settle(row.id(), result("completed", "조사 결과"));

        assertThat(written).isTrue();
        AgentExecution saved = executions.findById(row.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(saved.outputText()).isEqualTo("조사 결과");
        assertThat(saved.totalTokens()).isEqualTo(1_500L);
        assertThat(applicationEvents.stream(DelegationFinished.class))
                .containsExactly(new DelegationFinished(conversation.id(), row.id()));
        assertThat(executions.findUndeliveredResults(conversation.id()))
                .as("부모 대화에 전할 결과")
                .extracting(AgentExecution::id)
                .containsExactly(row.id());
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .as("위임 실행은 대화에 답을 쓰지 않는다")
                .isEmpty();
        assertThat(eventsOf(row))
                .extracting(ExecutionEvent::eventType)
                .containsExactly(ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    @DisplayName("위임 답이 상한과 같으면 그대로 적고 넘으면 자른 뒤 잘렸다는 줄로 끝낸다")
    void delegationOutputIsClippedOnlyBeyondLimit() {
        String atLimit = "가".repeat(OUTPUT_MAX_CHARS);
        AgentExecution exact = delegated();
        AgentExecution longer = delegated();

        recorder.settle(exact.id(), result("completed", atLimit));
        recorder.settle(longer.id(), result("completed", atLimit + "나"));

        assertThat(executions.findById(exact.id()).orElseThrow().outputText())
                .as("상한과 같은 길이")
                .isEqualTo(atLimit);
        assertThat(executions.findById(longer.id()).orElseThrow().outputText())
                .as("상한을 한 글자 넘는 길이")
                .isEqualTo(atLimit + "\n\n[답이 " + OUTPUT_MAX_CHARS + "자를 넘어 뒷부분을 잘랐다]");
    }

    @Test
    @DisplayName("흐름 turn 의 루트 줄은 성공으로 끝났어도 ORPHANED 로 적고 사용량을 남긴다")
    void flowRootIsRecordedAsOrphanedWithUsageEvenWhenCompleted() {
        Agent flowed = agent("flowed", "흐름");
        flowed.assignFlow(ResearchAndBuildFlow.NAME);
        flowed = agents.save(flowed);
        Conversation flowConversation =
                conversations.save(Conversation.startedBy(dad.id(), "흐름 대화", flowed.id(), Instant.now()));
        AgentExecution row = chatTurn(flowConversation, flowed);
        assertThat(recorder.kindOf(row)).isEqualTo(RecoveredRunKind.FLOW);
        List<ChatEvent> flowEvents = new CopyOnWriteArrayList<>();
        Runnable stopListening = hub.subscribe(flowConversation.id(), flowEvents::add);

        boolean written;
        try {
            written = recorder.settle(row.id(), result("completed", "합치지 못한 답"));
        } finally {
            stopListening.run();
        }

        assertThat(written).isTrue();
        AgentExecution saved = executions.findById(row.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("ORPHANED");
        assertThat(saved.totalTokens()).isEqualTo(1_500L);
        assertThat(messages.findByConversationIdOrderByIdAsc(flowConversation.id()))
                .isEmpty();
        assertThat(eventsOf(row))
                .as("흐름 루트의 끝 사건")
                .extracting(ExecutionEvent::eventType, ExecutionEvent::detail)
                .containsExactly(tuple(ExecutionEventType.RUN_FAILED, "ORPHANED"));
        assertThat(flowEvents)
                .as("화면이 답을 만드는 중을 내리도록 낸 사건")
                .extracting(ChatEvent::type, ChatEvent::code)
                .containsExactly(tuple("error", "HERMES_RUN_FAILED"));
    }

    @Test
    @DisplayName("흐름 turn 의 루트 줄이 취소나 실패로 끝나면 메시지 없이 stopped 나 error 를 낸다")
    void flowRootPublishesStoppedOrErrorWithoutMessage() {
        Agent flowed = agent("flowed", "흐름");
        flowed.assignFlow(ResearchAndBuildFlow.NAME);
        flowed = agents.save(flowed);
        Conversation cancelledConversation =
                conversations.save(Conversation.startedBy(dad.id(), "취소", flowed.id(), Instant.now()));
        Conversation failedConversation =
                conversations.save(Conversation.startedBy(dad.id(), "실패", flowed.id(), Instant.now()));
        AgentExecution cancelled = chatTurn(cancelledConversation, flowed);
        AgentExecution failed = chatTurn(failedConversation, flowed);
        List<ChatEvent> cancelledEvents = new CopyOnWriteArrayList<>();
        List<ChatEvent> failedEvents = new CopyOnWriteArrayList<>();
        Runnable stopCancelled = hub.subscribe(cancelledConversation.id(), cancelledEvents::add);
        Runnable stopFailed = hub.subscribe(failedConversation.id(), failedEvents::add);

        try {
            recorder.settle(cancelled.id(), result("cancelled", "일부 답"));
            recorder.settle(failed.id(), result("failed", null));
        } finally {
            stopCancelled.run();
            stopFailed.run();
        }

        assertThat(executions.findById(cancelled.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(cancelledEvents)
                .extracting(ChatEvent::type, ChatEvent::messageId, ChatEvent::executionId)
                .containsExactly(tuple("stopped", null, cancelled.id()));
        assertThat(messages.findByConversationIdOrderByIdAsc(cancelledConversation.id()))
                .as("흐름 루트의 답은 대화에 쓰지 않는다")
                .isEmpty();
        assertThat(executions.findById(failed.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failedEvents)
                .extracting(ChatEvent::type, ChatEvent::code)
                .containsExactly(tuple("error", "HERMES_RUN_FAILED"));
    }

    @Test
    @DisplayName("흐름 turn 의 자식 줄은 끝난 상태대로 적고 끝 사건을 남긴다")
    void flowChildIsRecordedAsItEndedWithEndEvent() {
        Agent flowed = agent("flowed", "흐름");
        flowed.assignFlow(ResearchAndBuildFlow.NAME);
        flowed = agents.save(flowed);
        Conversation flowConversation =
                conversations.save(Conversation.startedBy(dad.id(), "흐름 대화", flowed.id(), Instant.now()));
        AgentExecution root = chatTurn(flowConversation, flowed);
        AgentExecution child = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(flowConversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.treeRootId())
                .profileName("worker")
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .build());
        assertThat(recorder.kindOf(child)).isEqualTo(RecoveredRunKind.FLOW);

        boolean written = recorder.settle(child.id(), result("cancelled", "일부 답"));

        assertThat(written).isTrue();
        assertThat(executions.findById(child.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(eventsOf(child))
                .extracting(ExecutionEvent::eventType)
                .containsExactly(ExecutionEventType.RUN_CANCELLED);
    }

    @Test
    @DisplayName("대화가 없는 그 밖의 실행은 실행 줄과 끝 사건만 남긴다")
    void auxiliaryRunRecordsRowAndEndEventOnly() {
        AgentExecution completed = auxiliary();
        AgentExecution failed = auxiliary();
        assertThat(recorder.kindOf(completed)).isEqualTo(RecoveredRunKind.AUXILIARY);

        recorder.settle(completed.id(), result("completed", "쓰지 않는 답"));
        recorder.settle(failed.id(), result("failed", null));

        AgentExecution saved = executions.findById(completed.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(saved.totalTokens()).isEqualTo(1_500L);
        assertThat(eventsOf(completed))
                .extracting(ExecutionEvent::eventType, ExecutionEvent::detail)
                .containsExactly(tuple(ExecutionEventType.RUN_COMPLETED, null));
        assertThat(executions.findById(failed.id()).orElseThrow().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(eventsOf(failed))
                .extracting(ExecutionEvent::eventType, ExecutionEvent::detail)
                .containsExactly(tuple(ExecutionEventType.RUN_FAILED, "FAILED"));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(received).as("대화에 알리지 않는다").isEmpty();
    }

    @Test
    @DisplayName("결과 없이 실패로 적으면 준 오류 코드가 남고 두 번째 호출은 거짓이다")
    void failWithoutRecordsGivenCodeOnce() {
        AgentExecution row = chatTurn(conversation, chief);

        boolean first = recorder.failWithout(row.id(), "REMOTE_RUN_LOST");
        boolean second = recorder.failWithout(row.id(), "ORPHANED");

        assertThat(first).isTrue();
        assertThat(second).as("이미 끝난 줄").isFalse();
        AgentExecution saved = executions.findById(row.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("REMOTE_RUN_LOST");
        assertThat(eventsOf(row))
                .as("끝 사건은 한 번만, 처음 준 오류 코드로 남는다")
                .extracting(ExecutionEvent::sequence, ExecutionEvent::eventType, ExecutionEvent::detail)
                .containsExactly(tuple(1, ExecutionEventType.RUN_FAILED, "REMOTE_RUN_LOST"));
        assertThat(received)
                .extracting(ChatEvent::type, ChatEvent::code)
                .containsExactly(tuple("error", "HERMES_RUN_FAILED"));
    }

    @Test
    @DisplayName("이미 SUCCEEDED 인 줄은 적지 않고 그대로 둔다")
    void alreadySucceededRowIsLeftUntouched() {
        AgentExecution row = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .profileName("dad")
                .hermesRunId("run-done")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .timing(Instant.parse("2026-09-30T00:00:00Z"), Instant.parse("2026-09-30T00:01:00Z"))
                .build());

        boolean written = recorder.settle(row.id(), result("failed", null));

        assertThat(written).isFalse();
        AgentExecution saved = executions.findById(row.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(saved.errorCode()).isNull();
        assertThat(saved.finishedAt()).isEqualTo(Instant.parse("2026-09-30T00:01:00Z"));
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(eventsOf(row)).isEmpty();
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("에이전트 행이 없는 실행은 결과가 성공이어도 ORPHANED 로 적는다")
    void rowWithoutAgentIsRecordedAsOrphaned() {
        AgentExecution row = chatTurn(conversation, chief);
        agents.deleteById(chief.id());

        boolean written = recorder.settle(row.id(), result("completed", "끝난 답"));

        assertThat(written).isTrue();
        AgentExecution saved = executions.findById(row.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(saved.errorCode()).isEqualTo("ORPHANED");
        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id())).isEmpty();
        assertThat(eventsOf(row))
                .extracting(ExecutionEvent::eventType, ExecutionEvent::detail)
                .containsExactly(tuple(ExecutionEventType.RUN_FAILED, "ORPHANED"));
    }

    /** 이전 프로세스가 돌리던 대화 turn 의 루트 줄이다. 실행은 1분 전에 시작했다. */
    private AgentExecution chatTurn(Conversation owner, Agent agent) {
        return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(owner.id())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofMinutes(1)))
                .build());
    }

    /** Memory 제안이나 추천 질문처럼 대화 없이 돌던 실행이다. */
    private AgentExecution auxiliary() {
        return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .agentId(chief.id())
                .profileName(chief.hermesProfile())
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .build());
    }

    /** 끝난 대화 turn 아래에서 다른 에이전트에게 맡겨 아직 도는 실행이다. */
    private AgentExecution delegated() {
        AgentExecution root = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(worker.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.treeRootId())
                .delegationKey(UUID.randomUUID().toString())
                .profileName("worker")
                .hermesRunId("run-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .build());
    }

    private List<ExecutionEvent> eventsOf(AgentExecution row) {
        return executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(row.id()));
    }

    private Agent agent(String code, String name) {
        return Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                dad.id(),
                Instant.now());
    }

    private static HermesRunResult result(String status, String output) {
        return new HermesRunResult("run-1", "sess-1", status, output, null, null, null, USAGE, PRICED_RUNTIME);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException ex) throws IOException {
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
