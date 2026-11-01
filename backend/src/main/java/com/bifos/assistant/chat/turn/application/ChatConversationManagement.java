package com.bifos.assistant.chat.turn.application;

import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.application.ConversationWriter;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.conversation.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 대화의 제목과 모델 선택, 생성과 삭제를 맡는다. */
@Component
@RequiredArgsConstructor
class ChatConversationManagement {
    private final ConversationRepository conversations;
    private final ConversationWriter conversationWriter;
    private final ConversationAccess access;
    private final AgentService agents;
    private final ModelTierService modelTiers;
    private final Clock clock;
    private final ChatPendingMessageRepository pendingMessages;
    private final DecisionFeedbackRecorder feedback;


    /** 예약 작업 발화가 미리 만들었다가 쓰지 않은 대화만 부른다. 메시지가 있으면 지우지 않는다. */
    boolean discardEmptyTaskConversation(Long conversationId) {
        return conversations.discardEmptyTaskConversation(conversationId) == 1;
    }


    Conversation rename(CurrentUser user, Long conversationId, String title) {
        String normalized = Conversation.normalizedTitle(title);
        access.requireOwn(user, conversationId);
        if (conversationWriter.renameIfActive(conversationId, user.id(), normalized, clock.instant()) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        return access.requireOwn(user, conversationId);
    }


    /**
     * 대화가 고른 칸을 저장하고 그 칸을 요청자의 기본 칸으로도 남긴다. 칸을 고르는 모드만 받는다.
     *
     * <p>ChatGPT 가 고른 thinking 시간을 다음 대화에 남기듯, 고른 칸이 다음 새 대화의 처음 위치가 된다
     * (ADR-20261101 / model-setting 의 「기본 칸」). 부르는 쪽({@code ChatService})의 트랜잭션 안에서 함께 바꾼다.
     */
    Conversation chooseModelTier(CurrentUser user, Long conversationId, ModelSelectionMode mode, ModelTier tier) {
        Conversation conversation = access.requireOwn(user, conversationId);
        if (mode != ModelSelectionMode.TIER || tier == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid model tier selection");
        }
        Agent agent = agents.requireStartable(
            user, agents.requireById(conversation.agentId()).code());
        modelTiers.resolveTier(user, tier, agent);
        if (conversations.chooseModelTierIfActive(conversationId, user.id(), mode, tier) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        modelTiers.saveUserDefault(user, tier);
        return access.requireOwn(user, conversationId);
    }


    /** 예약 작업 발화가 그 대화를 작업의 단계로 고르게 한다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 바꾼다. */
    void chooseTierForTask(Long conversationId, ModelTier tier) {
        conversationWriter.chooseTierForTask(conversationId, tier);
    }


    /** 예약 작업이 「보고할 것 없음」 으로 끝낸 대화를 목록에서만 뺀다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 바꾼다. */
    void hideTaskConversation(Long conversationId, Instant now) {
        conversationWriter.hideFromList(conversationId, now);
    }


    void delete(CurrentUser user, Long conversationId) {
        access.requireOwn(user, conversationId);
        if (conversationWriter.deleteIfActive(conversationId, user.id(), clock.instant()) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        // 지운 대화에는 더 보낼 수 없다. 남기면 기동 확인이 보낼 수 없는 행을 계속 만난다.
        pendingMessages.deleteAllOf(conversationId);
        // 판단 피드백은 사용자의 기록이다. 대화를 지우면 그 대화에서 나온 제안의 사건도 함께 지운다.
        feedback.forgetConversation(user.id(), conversationId);
    }


    /**
     * 메시지 없이 제목이 빈 대화를 만든다.
     *
     * <p>사진을 먼저 올리거나 첫 메시지 전에 모델을 고르려면 대화가 먼저 있어야 한다. 에이전트를 쓸 수 있는지
     * 확인하며 사진을 받지 않는 에이전트는 보낼 때 거절한다.
     * 제목은 첫 메시지가 정한다.
     */
    Conversation startEmpty(CurrentUser user, String agentCode) {
        Agent agent = agents.requireStartable(user, agentCode);
        return conversations.save(Conversation.startedBy(user.id(), "", agent.id(), clock.instant()));
    }


    /**
     * 예약 작업이 결과를 남길 빈 대화를 만든다(ADR-078). 제목은 작업 이름이고 메시지는 첫 turn 이 남긴다.
     *
     * <p>주인과 에이전트를 쓸 수 있는지는 부르는 쪽이 이미 확인했다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 저장한다.
     */
    Conversation startForTask(Long ownerUserId, Long agentId, String title, Long taskId) {
        return conversations.save(Conversation.startedForTask(ownerUserId, title, agentId, taskId, clock.instant()));
    }
}
