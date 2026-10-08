package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 대화와 에이전트를 고르고 turn 의 질문을 저장한다. */
@Component
@Slf4j
@RequiredArgsConstructor
class ChatTurnRouting {
    private static final int TITLE_LIMIT = 60;

    private final ConversationRepository conversations;
    private final ConversationWriter conversationWriter;
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final FlowRegistry flows;
    private final AttachmentService attachments;
    private final TurnCancellation turns;
    private final TransactionTemplate transactions;
    private final SkillCommandCatalog skillCommands;
    private final Clock clock;
    private final ChatPendingMessageRepository pendingMessages;
    private final UserExecutionLimiter limiter;
    private final ChatConversationQueries chatConversationQueries;

    /**
     * 대화와 에이전트를 정하고 어느 경로로 갈지 고른다.
     *
     * <p>에이전트에 {@code flow} 가 적혀 있으면 그 흐름으로 간다. 비어 있으면 지금처럼 Hermes 를 한
     * 번 부른다. 모르는 이름은 기동할 때 이미 걸러졌다.
     *
     * <p>첨부 판정을 메시지를 저장하기 전에 모두 끝낸다. 첨부는 대화에 올리므로 첨부가 있으면 대화
     * 번호도 있어야 하고, 그것을 대화를 만들기 전에 본다. 거절은 모두 {@code VALIDATION_FAILED} 다.
     *
     * <p>에이전트를 새 대화를 저장하기 전에 정한다. 새 대화는 {@code agentCode} 의 에이전트, 이어 쓰는 대화는
     * 그 대화의 에이전트다. 스킬 커맨드의 이름이 그 에이전트의 켜진 스킬이 아니면 대화를 만들기 전에
     * {@code SKILL_COMMAND_UNKNOWN} 으로 거절한다. 거절한 커맨드는 대화도 메시지도 실행도 남기지 않는다.
     *
     * <p>새 대화를 저장하기 전에 사용자 자리가 남았는지 본다(ADR-069). 없으면 {@code USER_BUSY} 로 거절하고 아무것도
     * 저장하지 않는다. 그대로 저장하면 거절된 요청마다 그 글을 제목으로 한 빈 대화가 목록에 남는다.
     */
    Routed route(CurrentUser user, Long conversationId, String text, String agentCode, List<Long> attachmentIds) {
        Instant requestReceivedAt = clock.instant();
        boolean withAttachments = attachmentIds != null && !attachmentIds.isEmpty();
        if (withAttachments && conversationId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "attachments need an existing conversation");
        }
        Conversation existing = conversationId == null ? null : access.requireOwn(user, conversationId);
        Agent agent =
                existing == null ? agents.requireStartable(user, agentCode) : agents.requireById(existing.agentId());
        // 지운 에이전트의 대화는 읽기만 된다. 꺼진 것보다 먼저 봐야 없는 에이전트로 알린다.
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        // 흐름은 사진 자리를 덧붙이는 경로를 거치지 않는다. 오류 없이 사진을 버리지 않게 거절한다.
        if (withAttachments && !agent.acceptsAttachments()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this agent does not accept attachments");
        }
        requireAttachmentOwner(user, agent, withAttachments);
        Flow flow = flows.find(agent.flow());
        SkillCommand command = commandOf(agent, flow, text);
        if (existing == null && !limiter.hasTurnRoom(user.id())) {
            throw new ApiException(ErrorCode.USER_BUSY, "this user has reached the concurrent execution limit");
        }
        Conversation conversation = existing != null
                ? existing
                : conversations.save(Conversation.startedBy(user.id(), titleFrom(text), agent.id(), clock.instant()));
        List<ChatAttachment> attached = attachments.requireAttachable(conversation.id(), attachmentIds);
        return new Routed(conversation, agent, flow, attached, command, requestReceivedAt, existing == null);
    }

    /**
     * 그 대화의 turn 잠금과 사용자 자리를 얻는다.
     *
     * <p>{@link #route} 가 새 대화를 저장하기 전에 자리를 보았지만, 그 사이 다른 요청이 자리를 채우면 여기서
     * {@code USER_BUSY} 가 난다. 그때 방금 만든 빈 대화를 지우고 다시 던진다. 지우다 실패하면 경고 로그만 남기고 원래
     * 예외를 던진다.
     */
    TurnHandle openTurn(CurrentUser user, Routed routed) {
        try {
            return turns.open(user.id(), routed.conversation().id());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.USER_BUSY && routed.created()) {
                deleteCreatedConversation(routed.conversation().id());
            }
            throw ex;
        }
    }

    void deleteCreatedConversation(Long conversationId) {
        try {
            conversations.deleteById(conversationId);
        } catch (RuntimeException ex) {
            log.warn("사용자 실행 한도로 거절한 요청의 빈 대화를 지우지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /**
     * 메시지가 스킬 커맨드이면 그 이름이 에이전트의 켜진 스킬인지 확인해 낸다. 근거는 ADR-035 에 있다.
     *
     * <p>커맨드 모양이 아니거나 흐름이 붙은 에이전트이면 {@code null} 이다. 흐름에는 글을 그대로 보낸다.
     * 켜진 스킬 목록을 읽다 Hermes 가 실패하면 그 예외가 그대로 올라간다. 이름을 확인하지 못한 커맨드를
     * 보내지 않는다. 그 에이전트를 쓸 수 있는지는 부르기 전에 이미 판정했으므로 목록을 읽을 때 다시 보지 않는다.
     */
    SkillCommand commandOf(Agent agent, Flow flow, String text) {
        if (flow != null) {
            return null;
        }
        SkillCommand command = SkillCommand.parse(text).orElse(null);
        if (command == null) {
            return null;
        }
        if (!skillCommands.enabledNames(agent).contains(command.name())) {
            throw new ApiException(ErrorCode.SKILL_COMMAND_UNKNOWN, "this agent has no enabled skill with that name");
        }
        return command;
    }

    /**
     * 빈 대화를 먼저 만든 경우 첫 메시지로 제목을 채운다.
     *
     * <p>{@link #route} 에서 채우지 않는다. 그 뒤 첨부 묶기가 실패해 메시지가 되돌려져도 제목만 남기
     * 때문이다. 첨부를 받는 경로는 메시지 저장과 같은 트랜잭션에서 부른다.
     */
    void fillBlankTitle(Conversation conversation, String text) {
        if (conversation.title().isBlank()) {
            String title = titleFrom(text);
            conversationWriter.fillTitleIfBlank(conversation.id(), title);
            conversation.titleIfBlank(title);
        }
    }

    /** 다시 생성할 대화의 에이전트를 정한다. 저장된 질문이 스킬 커맨드이면 보낼 때와 같게 판별한다. */
    Routed routeExisting(
            CurrentUser user,
            Conversation conversation,
            List<ChatAttachment> attached,
            String question,
            Instant requestReceivedAt) {
        Agent agent = agents.requireById(conversation.agentId());
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        requireAttachmentOwner(user, agent, !attached.isEmpty());
        Flow flow = flows.find(agent.flow());
        return new Routed(
                conversation, agent, flow, attached, commandOf(agent, flow, question), requestReceivedAt, false);
    }

    static void requireAttachmentOwner(CurrentUser user, Agent agent, boolean withAttachments) {
        if (!withAttachments) {
            return;
        }
        boolean privateAgent = agent.visibility() == AgentVisibility.PRIVATE;
        boolean sameOwner = Objects.equals(agent.ownerUserId(), user.id());
        if (!privateAgent || !sameOwner) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this agent does not accept attachments");
        }
    }

    /**
     * 이 turn 의 질문을 남기고, 사람이 보낸 질문이면 그 메시지 번호를 돌려준다.
     *
     * @return 새 질문이면 방금 저장한 메시지, 다시 생성이면 이미 있는 질문의 번호. 사람 없이 열린 turn 이면 null 이다
     */
    Long saveQuestion(
            CurrentUser user,
            Conversation conversation,
            String text,
            List<Long> attachmentIds,
            TurnIntent intent,
            Consumer<ChatEvent> onEvent) {
        if (intent instanceof TurnIntent.ProactiveCheck proactive) {
            if (!proactive.check().notifyStart()) {
                return null;
            }
            // 질문 대신 시작 알림 줄 하나를 남긴다. 제목, 자동 turn 수, 대기 행은 건드리지 않는다.
            ChatMessage notice = transactions.execute(status -> messages.save(
                    ChatMessage.fromSystem(conversation.id(), proactive.check().startNotice(), clock.instant())));
            onEvent.accept(ChatEvent.system(conversation.publicId(), notice.id(), notice.content()));
            return null;
        }
        // 다시 생성은 질문을 이미 저장했다. 자동 turn 의 알림 줄은 이 turn 을 열기 전에 저장했다.
        if (intent instanceof TurnIntent.Scheduled scheduled) {
            // 알림 줄, 지시, 자동 turn 수 초기화가 함께 남거나 함께 빠진다. 사람이 질문한 것과 같게 자동 turn 수를 새로 센다.
            List<ChatMessage> saved = transactions.execute(status -> {
                Instant savedAt = clock.instant();
                ChatMessage line =
                        messages.save(ChatMessage.fromSystem(conversation.id(), scheduled.notice(), savedAt));
                fillBlankTitle(conversation, text);
                ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text, savedAt));
                conversationWriter.resetAutoTurns(conversation.id());
                return List.of(line, question);
            });
            ChatMessage line = saved.get(0);
            ChatMessage question = saved.get(1);
            onEvent.accept(ChatEvent.system(conversation.publicId(), line.id(), line.content()));
            onEvent.accept(ChatEvent.user(conversation.publicId(), question.id(), text));
            return null;
        }
        if (intent instanceof TurnIntent.Regenerate regenerate) {
            return regenerate.question() == null ? null : regenerate.question().id();
        }
        if (!(intent instanceof TurnIntent.Fresh fresh)) {
            return null;
        }
        List<Long> pendingIds = fresh.pendingIds();
        ChatMessage question = transactions.execute(status -> {
            fillBlankTitle(conversation, text);
            ChatMessage saved =
                    messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text, clock.instant()));
            attachments.attach(saved.id(), conversation.id(), attachmentIds);
            // 사람이 질문했으니 사용자의 질문 없이 연 turn 의 수를 새로 센다.
            conversationWriter.resetAutoTurns(conversation.id());
            // 사람이 질문했으니 「보고할 것 없음」 으로 숨긴 대화를 다시 목록에 보인다.
            conversationWriter.showInList(conversation.id());
            // 대기 행을 지우는 것과 그 글을 사용자 메시지로 남기는 것은 함께 남거나 함께 빠진다.
            // 지운 수가 읽은 수와 다르면 읽은 뒤 취소된 행이 있다. 취소한 글을 보내지 않게 되돌린다.
            if (!pendingIds.isEmpty() && pendingMessages.deleteAllByIdIn(pendingIds) != pendingIds.size()) {
                throw new PendingQueueChangedException();
            }
            return saved;
        });
        if (!pendingIds.isEmpty()) {
            onEvent.accept(ChatEvent.user(conversation.publicId(), question.id(), text));
            onEvent.accept(ChatEvent.pending(conversation.publicId()));
        } else {
            // 대기 행에서 꺼낸 질문은 앞 turn 이 끝난 뒤에 저장된다. 그 사이 만든 보고를 사용자가 본 것으로 적지 않는다.
            chatConversationQueries.markCheckReportsRead(user, conversation);
        }
        return question.id();
    }

    List<ChatMessage> activeMessages(Long conversationId) {
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        Set<Long> replaced = history.stream()
                .map(ChatMessage::replacesMessageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return history.stream()
                .filter(message -> !replaced.contains(message.id()))
                .toList();
    }

    /**
     * 답 앞의 질문을 뒤로 거슬러 찾는다.
     *
     * <p>답 바로 앞이 알림 줄이면 사용자 질문 없이 연 자동 turn 의 답이라 다시 만들 질문이 없다. 그때는 null 이다.
     */
    static ChatMessage previousQuestion(List<ChatMessage> active, ChatMessage answer) {
        int answerIndex = active.indexOf(answer);
        if (answerIndex > 0 && active.get(answerIndex - 1).role() == MessageRole.SYSTEM) {
            return null;
        }
        for (int index = answerIndex - 1; index >= 0; index--) {
            ChatMessage candidate = active.get(index);
            if (candidate.role() == MessageRole.USER) {
                return candidate;
            }
        }
        return null;
    }

    static String titleFrom(String text) {
        String single = text.strip().replaceAll("\\s+", " ");
        return single.length() <= TITLE_LIMIT ? single : single.substring(0, TITLE_LIMIT);
    }
}
