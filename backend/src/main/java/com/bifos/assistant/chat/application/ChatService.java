package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.DeliveryState;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화 메시지 하나를 Hermes 실행으로 바꾸고 비용을 기록한다.
 *
 * <p>라우팅은 여기에서만 정한다. 대화의 에이전트가 profile을 고르고, 대화가 시작된 뒤 요청 본문은
 * 그 profile을 바꾸지 못한다.
 *
 * <p>쓸 모델과 effort 는 대화가 고른 값이다. 고르지 않았으면 모델을 빼고 보내 profile 의 기본값으로
 * 돈다. 그 provider 의 계정이 전부 막혀도 다른 모델로 넘기지 않고 {@code PROVIDER_BLOCKED} 로 실패한다.
 * 한 provider 안에서 계정을 돌려 쓰는 것은 Hermes 가 이미 하므로 여기서 하지 않는다.
 *
 * <p>자동 turn 은 알림 줄 저장, 전했다는 표시, 자동 turn 수 증가, 전달 묶음과 첫 시도 저장을 한 트랜잭션에 적는다. 그
 * 시도는 부모 turn 의 실행 줄을 잇고 그 turn 이 끝난 방식으로 닫힌다(ADR-075).
 */
@Service
@RequiredArgsConstructor
public class ChatService {
    /** 대화 목록 한 쪽의 상한이다. 웹이 더 크게 요청해도 이만큼만 읽는다. */
    public static final int MAX_CONVERSATION_PAGE = 100;

    /** 다시 전달할 때 남기는 알림 줄이다. 결과마다 알림 줄을 다시 남기지 않고 이 한 줄만 남긴다(ADR-075). */
    static final String RETRY_NOTICE = "맡긴 일의 결과를 다시 전해요";

    /** 읽은 대기 행이 저장 전에 취소돼 다시 읽는 횟수의 상한이다. */
    private static final int PENDING_READ_ATTEMPTS = 3;

    private final ConversationSessions sessions;
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final AttachmentService attachments;
    private final TurnCancellation turns;
    private final Clock clock;
    private final ChatPendingMessageRepository pendingMessages;
    private final ChatTurnRunner chatTurnRunner;
    private final ChatTurnRouting chatTurnRouting;
    private final ChatTurnStopper chatTurnStopper;
    private final ChatDeliveryTurns chatDeliveryTurns;
    private final ChatConversationQueries chatConversationQueries;
    private final ChatConversationManagement chatConversationManagement;

    public ChatTurn send(CurrentUser user, Long conversationId, String text, String agentCode) {
        return send(user, conversationId, text, agentCode, List.of());
    }

    /**
     * 메시지 하나를 보낸다. 첨부 번호가 있으면 그 메시지에 묶고 사진이 놓인 자리를 Hermes 입력에 알린다.
     *
     * <p>첨부가 하나라도 거절되면 메시지를 저장하지 않고 대화도 새로 만들지 않는다.
     */
    public ChatTurn send(
            CurrentUser user, Long conversationId, String text, String agentCode, List<Long> attachmentIds) {
        Routed routed = chatTurnRouting.route(user, conversationId, text, agentCode, attachmentIds);
        if (routed.flow() != null) {
            return chatTurnRunner.runFlow(user, routed, text, new TurnIntent.Fresh(), event -> {}, false, null);
        }
        return chatTurnRunner.runTurn(user, routed, text, new TurnIntent.Fresh(), event -> {}, false, null);
    }

    public void stream(
            CurrentUser user, Long conversationId, String text, String agentCode, Consumer<ChatEvent> onEvent) {
        stream(user, conversationId, text, agentCode, List.of(), onEvent);
    }

    public void stream(
            CurrentUser user,
            Long conversationId,
            String text,
            String agentCode,
            List<Long> attachmentIds,
            Consumer<ChatEvent> onEvent) {
        Routed routed = chatTurnRouting.route(user, conversationId, text, agentCode, attachmentIds);
        // 잠금을 닫으면 닫기 리스너가 맡긴 일의 결과로 자동 turn 을 연다. 이 turn 의 done 이나 stopped 를 보낸 뒤에
        // 닫아야 클라이언트가 이 turn 의 끝을 자동 turn 의 시작보다 먼저 받는다.
        TurnHandle handle = chatTurnRouting.openTurn(user, routed);
        try {
            if (routed.flow() != null) {
                chatTurnRunner.runFlow(user, routed, text, new TurnIntent.Fresh(), onEvent, true, handle);
                return;
            }
            ChatTurn turn = chatTurnRunner.runTurn(user, routed, text, new TurnIntent.Fresh(), onEvent, true, handle);
            onEvent.accept(
                    turn.cancelled()
                            ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                            : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
        } finally {
            turns.close(handle);
        }
    }

    /**
     * 아직 전하지 않은 끝난 위임 결과와 승인한 동작의 결과를 모아 사용자의 질문 없이 turn 하나를 돌린다(ADR-040,
     * ADR-050).
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 잠금을 잡기 전에 읽은 목록은 다른 자동 turn 이 이미 전했을 수
     * 있어 여기서 다시 읽는다. 비었으면 아무것도 남기지 않고 돌아간다.
     *
     * <p>알림 줄은 위임 결과에 한 줄, 그 밖의 결과마다 한 줄이다. 알림 줄 저장, 결과마다 전했다는 표시, 자동 turn 수
     * 증가, 전달 묶음과 항목과 첫 시도 저장은 한 트랜잭션이다(ADR-075). 그 뒤 Hermes 가 실패해도 같은
     * 결과로 다시 깨우지 않는다. 같은 실패를 되풀이하지 않기 위해서다. 실패는 시도에 남기고 예외로 올라간다.
     *
     * @param owner 대화 주인. 요청이 없으므로 부르는 쪽이 사용자 행으로 만든다
     * @param onEvent 알림 줄, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     */
    public void runDelegationResults(
            CurrentUser owner, Long conversationId, TurnHandle handle, Consumer<ChatEvent> onEvent) {
        chatDeliveryTurns.runDelegationResults(owner, conversationId, handle, onEvent);
    }

    /**
     * 저장된 결과만 다시 읽어 전달 묶음 하나를 부모에 다시 넘긴다(ADR-075). 자식 실행과 커넥터 호출은 다시 하지 않는다.
     *
     * <p>대화, 묶음, 상태, 에이전트를 잠금 전에 본다. 잠금 전에 본 상태는 빠른 거절일 뿐이다. 잠금을 연 뒤 항목의 결과를
     * 다시 읽고, 묶음을 조건부 update 로 {@code DELIVERING} 으로 바꾸는 것과 알림 줄과 새 시도를 한 트랜잭션에 적는다.
     * 활성 시도를 하나로 지키는 것은 그 update 다.
     *
     * <p>사람이 요청한 turn 이라 자동 turn 수를 0 으로 돌린다. 사건은 요청한 창에만 간다.
     *
     * @param onEvent 알림 줄, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code DELIVERY_NOT_FOUND}, {@code DELIVERY_NOT_RETRYABLE},
     *     {@code AGENT_NOT_FOUND}, {@code AGENT_DISABLED}, {@code CONVERSATION_BUSY}, {@code USER_BUSY}. 이 예외들은 묶음을
     *     바꾸지 않는다
     */
    public void retryDelivery(CurrentUser user, Long conversationId, Long deliveryId, Consumer<ChatEvent> onEvent) {
        chatDeliveryTurns.retryDelivery(user, conversationId, deliveryId, onEvent);
    }

    /**
     * 그 대화의 알림 줄 번호마다 그 줄이 마지막 시도의 마지막 알림 줄인 묶음의 번호와 상태다. 이력 API 가 쓴다.
     *
     * <p>주인 확인은 부르는 쪽이 마친 번호로 부른다.
     */
    public Map<Long, DeliveryState> deliveryStates(Long conversationId) {
        return chatDeliveryTurns.deliveryStates(conversationId);
    }

    /**
     * 예약 작업의 지시로 사용자의 질문 없이 turn 하나를 돌린다(ADR-076).
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 사람이 보낸 turn 과 같은 경로를 탄다. 그래서 커넥터 도구 판정과
     * 승인 줄, 승인 요청 알림이 사람이 보낸 turn 과 같다. 알림 줄과 지시를 사용자 메시지로 남기고 자동 turn 수를 0 으로
     * 돌리는 것은 한 트랜잭션이다. 실패는 예외로 올라간다.
     *
     * @param owner 대화 주인. 요청이 없으므로 부르는 쪽이 사용자 행으로 만든다
     * @param notice 지시 앞에 대화에 남기는 알림 줄의 글
     * @param instruction 사용자 메시지로 남기고 Hermes 에 보내는 작업의 지시
     * @param onEvent 알림 줄, 사용자 메시지, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     * @throws ApiException 대화의 에이전트에 흐름이 붙었으면 {@code TASK_AGENT_NOT_SUPPORTED}
     */
    public ChatTurn runScheduledTurn(
            CurrentUser owner,
            Long conversationId,
            TurnHandle handle,
            String notice,
            String instruction,
            Consumer<ChatEvent> onEvent) {
        Routed routed = chatTurnRouting.route(owner, conversationId, instruction, null, List.of());
        if (routed.flow() != null) {
            // 흐름은 지시를 흐름 안에서 저장해 알림 줄과 한 트랜잭션으로 묶을 수 없다. 작업을 만들 때 이미 거른다.
            throw new ApiException(ErrorCode.TASK_AGENT_NOT_SUPPORTED, "an agent with a flow cannot run a task");
        }
        ChatTurn turn = chatTurnRunner.runTurn(
                owner, routed, instruction, new TurnIntent.Scheduled(notice), onEvent, true, handle);
        onEvent.accept(
                turn.cancelled()
                        ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                        : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
        return turn;
    }

    /**
     * 먼저 살펴보기 turn 하나를 돌린다(ADR-080). 살펴보기만의 일은 {@code check} 가 맡는다.
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 입력은 한 번만 만들어 라우팅과 turn 에 같은 값을 넘긴다.
     * 흐름이 붙은 대화면 {@code PROACTIVE_CHECK_UNAVAILABLE} 로 거절한다. session 을 바꿀 차례면 turn 을 열기 전에 바꾼다.
     *
     * <p>질문 대신 시작 알림 줄을 남기고, 답 조각은 흘리지 않는다. 성공한 답은 {@code check} 가 바꾼 글로 남기고, 멈추면
     * 그때까지의 답 대신 멈춤 알림 줄만 남긴다. Memory 제안, 추천 질문 갱신, 자동 turn 수, 제목은 건드리지 않는다. 실패는
     * 예외로 올라가고 실패 알림 줄은 부르는 쪽이 남긴다.
     *
     * @param owner 대화 주인
     * @param onEvent 알림 줄, {@code started}, 도구와 하위 에이전트 사건, {@code done} 이나 {@code stopped} 를 받는다
     */
    public void runProactiveCheck(
            CurrentUser owner, Long conversationId, TurnHandle handle, CheckTurn check, Consumer<ChatEvent> onEvent) {
        String input = check.input();
        Routed routed = chatTurnRouting.route(owner, conversationId, input, null, List.of());
        if (routed.flow() != null) {
            throw new ApiException(
                    ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, "an agent with a flow does not run a proactive check");
        }
        if (check.renewSession()) {
            sessions.renew(routed.conversation());
        }
        ChatTurn turn = chatTurnRunner.runTurn(
                owner, routed, input, new TurnIntent.ProactiveCheck(check), onEvent, true, handle);
        onEvent.accept(
                turn.cancelled()
                        ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                        : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
    }

    /**
     * 쌓인 대기 메시지를 합쳐 사용자 메시지 하나로 turn 을 돌린다(ADR-048).
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 잠금을 잡기 전에 읽은 대기 줄은 그 사이 취소되거나 멈췄을 수
     * 있어 여기서 다시 읽는다. 비었거나 멈춘 행이 있으면 아무것도 남기지 않고 돌아간다.
     *
     * <p>대기 행 삭제와 사용자 메시지 저장은 한 트랜잭션이다. 읽은 뒤 저장 전에 취소된 행이 있으면 되돌리고 다시
     * 읽어 합친다. 사용자 메시지를 저장하기 전의 실패는 예외로 올라가고 대기 행은 그대로 남는다.
     *
     * @param owner 대화 주인. 요청이 없으므로 부르는 쪽이 사용자 행으로 만든다
     * @param onEvent {@code user}, {@code pending}, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     */
    public void runPendingMessages(
            CurrentUser owner, Long conversationId, TurnHandle handle, Consumer<ChatEvent> onEvent) {
        for (int attempt = 1; ; attempt++) {
            List<ChatPendingMessage> rows = pendingMessages.findByConversationIdOrderByIdAsc(conversationId);
            if (rows.isEmpty() || rows.stream().anyMatch(ChatPendingMessage::held)) {
                return;
            }
            String text = ChatPendingMessage.merged(rows);
            Routed routed = chatTurnRouting.route(owner, conversationId, text, null, List.of());
            if (routed.flow() != null) {
                // 흐름은 질문을 흐름 안에서 저장해 대기 행 삭제와 한 트랜잭션으로 묶을 수 없다. 더할 때 이미 거르므로
                // 그 뒤에 흐름이 붙은 경우뿐이다.
                throw new ApiException(ErrorCode.CONVERSATION_BUSY, "this conversation does not take queued messages");
            }
            List<Long> ids = rows.stream().map(ChatPendingMessage::id).toList();
            try {
                ChatTurn turn =
                        chatTurnRunner.runTurn(owner, routed, text, new TurnIntent.Fresh(ids), onEvent, true, handle);
                onEvent.accept(
                        turn.cancelled()
                                ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                                : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
                return;
            } catch (PendingQueueChangedException ex) {
                if (attempt >= PENDING_READ_ATTEMPTS) {
                    throw new ApiException(
                            ErrorCode.CONVERSATION_BUSY, "the queued messages kept changing while sending");
                }
            }
        }
    }

    /** 예약 작업 발화가 미리 만들었다가 쓰지 않은 대화만 부른다. 메시지가 있으면 지우지 않는다. */
    @Transactional
    public boolean discardEmptyTaskConversation(Long conversationId) {
        return chatConversationManagement.discardEmptyTaskConversation(conversationId);
    }

    /** 마지막 답을 새 실행으로 다시 만든다. */
    public void regenerate(CurrentUser user, Long conversationId, Consumer<ChatEvent> onEvent) {
        Instant requestReceivedAt = clock.instant();
        Conversation conversation = access.requireOwn(user, conversationId);
        TurnHandle handle = turns.open(user.id(), conversation.id());
        try {
            List<ChatMessage> active = chatTurnRouting.activeMessages(conversation.id());
            if (active.isEmpty()) {
                throw new ApiException(ErrorCode.MESSAGE_NOT_LATEST, "there is no message to regenerate");
            }
            ChatMessage last = active.getLast();
            ChatMessage previousAnswer = last.role() == MessageRole.ASSISTANT ? last : null;
            ChatMessage question =
                    previousAnswer == null ? last : ChatTurnRouting.previousQuestion(active, previousAnswer);
            if (question == null || question.role() != MessageRole.USER) {
                throw new ApiException(ErrorCode.MESSAGE_NOT_LATEST, "the latest message is not a question");
            }
            List<ChatAttachment> attached = attachments.allOf(conversation.id()).stream()
                    .filter(attachment -> question.id().equals(attachment.messageId()) && attachment.isVisible())
                    .toList();
            Routed routed =
                    chatTurnRouting.routeExisting(user, conversation, attached, question.content(), requestReceivedAt);
            TurnIntent intent = new TurnIntent.Regenerate(previousAnswer, question);
            if (routed.flow() != null) {
                chatTurnRunner.runFlow(user, routed, question.content(), intent, onEvent, true, handle);
                return;
            }
            ChatTurn turn = chatTurnRunner.runTurn(user, routed, question.content(), intent, onEvent, true, handle);
            onEvent.accept(
                    turn.cancelled()
                            ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                            : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
        } finally {
            turns.close(handle);
        }
    }

    public void stop(CurrentUser user, Long executionId) {
        chatTurnStopper.stop(user, executionId);
    }

    /**
     * 이 실행들 중 자식을 가진 것을 낸다.
     *
     * <p>대화 이력이 「이 답이 어떻게 만들어졌는지 보기」 를 어느 답에 붙일지 정하는 데 쓴다. 실행마다
     * 세지 않고 한 번에 읽는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작하므로 목록이 비면
     * 부르지 않는다.
     */
    public Set<Long> executionIdsHavingChildren(List<ChatMessage> history) {
        return chatConversationQueries.executionIdsHavingChildren(history);
    }

    /** 답 메시지의 실행 상태를 한 번에 읽는다. */
    public Map<Long, ExecutionStatus> statuses(List<ChatMessage> history) {
        return chatConversationQueries.statuses(history);
    }

    /**
     * 이 답들 중 막혀서 넘어간 것에 넘어간 곳의 provider 와 모델을 붙인다.
     *
     * <p>사건을 실행마다 세지 않고 한 번에 읽는다. 빈 {@code in} 절은 데이터베이스마다 다르게
     * 동작하므로 목록이 비면 부르지 않는다.
     *
     * <p>넘어간 곳의 모델은 내부 값이라 {@code ADMIN} 역할이 아니면 빈 묶음을 돌려준다(ADR-063).
     */
    public Map<Long, String> switchedLabels(CurrentUser viewer, List<ChatMessage> history) {
        return chatConversationQueries.switchedLabels(viewer, history);
    }

    /** 답마다 도구와 하위 에이전트 사건을 한 번에 읽어 작업 과정 요약을 만든다. */
    public Map<Long, ActivitySummary> activitySummaries(List<ChatMessage> history) {
        return chatConversationQueries.activitySummaries(history);
    }

    /**
     * 이 대화의 첨부를 메시지 번호로 나눈다. 아직 메시지에 묶이지 않은 것은 뺀다.
     *
     * <p>메시지마다 묻지 않고 한 번에 읽는다. 지워진 첨부도 담아 지난 대화에 자리를 남긴다. 부르는
     * 순서에 기대지 않도록 여기서도 대화 주인을 확인한다.
     */
    public Map<Long, List<ChatAttachment>> attachmentsByMessage(CurrentUser user, Long conversationId) {
        return chatConversationQueries.attachmentsByMessage(user, conversationId);
    }

    /**
     * 답 메시지마다 그 turn 이 만든 결과물을 한 번에 읽는다. 사용자 메시지는 결과물이 없어 묻지 않는다.
     *
     * <p>{@link ChatConversationQueries#history} 로 주인을 확인한 메시지 목록을 받는다.
     */
    public Map<Long, List<ChatArtifact>> artifactsByMessage(List<ChatMessage> history) {
        return chatConversationQueries.artifactsByMessage(history);
    }

    /** 주인을 확인하고 메시지를 읽는다. 점검 대화면 그 대화의 열지 않은 보고를 연 것으로 적는다. */
    public List<ChatMessage> history(CurrentUser user, Long conversationId) {
        return chatConversationQueries.history(user, conversationId);
    }

    /**
     * 대화에 지금 도는 turn 을 알려 준다.
     *
     * <p>주인 확인을 표시보다 먼저 한다. 남의 대화에 도는 turn 이 있는지 새지 않게 하려는 것이다.
     * 도는지는 실행 줄의 상태가 아니라 메모리 표시로 본다. 흐름은 루트 줄이 끝난 뒤에도 자식이 돈다.
     */
    @Transactional(readOnly = true)
    public RunningTurn running(CurrentUser user, Long conversationId) {
        return chatConversationQueries.running(user, conversationId);
    }

    /**
     * 사용자의 대화를 최근에 바뀐 것부터 한 쪽 읽는다.
     *
     * @param cursor 앞 쪽이 돌려준 {@code nextCursor}. 처음이면 null
     * @param limit 한 쪽의 최대 개수. {@link #MAX_CONVERSATION_PAGE} 를 넘으면 그 값으로 줄인다
     */
    public ConversationPage conversationsOf(CurrentUser user, String cursor, int limit) {
        return chatConversationQueries.conversationsOf(user, cursor, limit);
    }

    /** 사용자의 대화 한 줄을 읽는다. 없거나 남의 것이면 같은 응답으로 숨긴다. */
    public Conversation conversationOf(CurrentUser user, UUID publicId) {
        return chatConversationQueries.conversationOf(user, publicId);
    }

    @Transactional
    public Conversation rename(CurrentUser user, Long conversationId, String title) {
        return chatConversationManagement.rename(user, conversationId, title);
    }

    /**
     * 대화에서 쓸 모델과 effort 를 바꾼다. 그 뒤의 실행이 이 값을 쓴다.
     *
     * <p>고른 모델이 Hermes 목록에 있는지는 보지 않는다. 그룹이 숨긴 모델은 {@code MODEL_HIDDEN} 으로 거절한다. effort
     * {@code none} 은 그 에이전트의 목록에서 끄기 지원이 확인된 모델에서만 받고, 아니면 {@code VALIDATION_FAILED} 다. 대화 목록의 순서는 주고받은 시각으로 정하므로
     * {@code updatedAt} 을 건드리지 않는다.
     *
     * @param choice 요청에서 {@link ModelChoice#of} 로 검증해 만든 선택
     */
    @Transactional
    public Conversation chooseModel(CurrentUser user, Long conversationId, ModelChoice choice) {
        return chatConversationManagement.chooseModel(user, conversationId, choice);
    }

    /** 대화가 고른 단계를 저장한다. DEFAULT 는 에이전트 기본 모델만 쓰도록 사용자·그룹 기본값도 건너뛴다. */
    @Transactional
    public Conversation chooseModelTier(
            CurrentUser user, Long conversationId, ModelSelectionMode mode, ModelTier tier) {
        return chatConversationManagement.chooseModelTier(user, conversationId, mode, tier);
    }

    /**
     * 예약 작업 발화가 그 대화를 작업의 단계로 고르게 한다. 고른 모델과 effort 는 지운다.
     *
     * <p>주인과 대화는 부르는 쪽이 이미 정했다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 바꾼다.
     */
    public void chooseTierForTask(Long conversationId, ModelTier tier) {
        chatConversationManagement.chooseTierForTask(conversationId, tier);
    }

    /**
     * 예약 작업이 「보고할 것 없음」 으로 끝낸 대화를 목록에서만 뺀다. 조회와 보내기는 그대로 된다.
     *
     * <p>주인과 대화는 부르는 쪽이 이미 정했다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 바꾼다. MySQL {@code DATETIME(6)} 은
     * 남는 자리를 반올림하므로 다른 시각 칸처럼 마이크로초로 자른다.
     */
    public void hideTaskConversation(Long conversationId, Instant now) {
        chatConversationManagement.hideTaskConversation(conversationId, now.truncatedTo(ChronoUnit.MICROS));
    }

    @Transactional
    public void delete(CurrentUser user, Long conversationId) {
        chatConversationManagement.delete(user, conversationId);
    }

    /**
     * 메시지 없이 제목이 빈 대화를 만든다.
     *
     * <p>사진을 먼저 올리거나 첫 메시지 전에 모델을 고르려면 대화가 먼저 있어야 한다. 모델은 흐름이 붙은
     * 에이전트에서도 고르므로 에이전트를 쓸 수 있는지만 본다. 사진을 받지 않는 에이전트는 보낼 때 거절한다.
     * 제목은 첫 메시지가 정한다.
     */
    public Conversation startEmpty(CurrentUser user, String agentCode) {
        return chatConversationManagement.startEmpty(user, agentCode);
    }

    /**
     * 예약 작업이 결과를 남길 빈 대화를 만든다(ADR-078). 제목은 작업 이름이고 메시지는 첫 turn 이 남긴다.
     *
     * <p>주인과 에이전트를 쓸 수 있는지는 부르는 쪽이 이미 확인했다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 저장한다.
     */
    public Conversation startForTask(Long ownerUserId, Long agentId, String title, Long taskId) {
        return chatConversationManagement.startForTask(ownerUserId, agentId, title, taskId);
    }
}
