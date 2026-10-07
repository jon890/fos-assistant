package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
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
     * 대화에서 쓸 모델과 effort 를 바꾼다. 그 뒤의 실행이 이 값을 쓴다.
     *
     * <p>고른 모델이 Hermes 목록에 있는지는 보지 않는다. 그룹이 숨긴 모델은 {@code MODEL_HIDDEN} 으로 거절한다. effort
     * {@code none} 은 그 에이전트의 목록에서 끄기 지원이 확인된 모델에서만 받고, 아니면 {@code VALIDATION_FAILED} 다. 대화 목록의 순서는 주고받은 시각으로 정하므로
     * {@code updatedAt} 을 건드리지 않는다.
     *
     * @param choice 요청에서 {@link ModelChoice#of} 로 검증해 만든 선택
     */
    Conversation chooseModel(CurrentUser user, Long conversationId, ModelChoice choice) {
        Conversation conversation = access.requireOwn(user, conversationId);
        modelTiers.requireVisible(user, choice);
        // none 일 때만 에이전트를 얻는다. 꺼진 에이전트나 에이전트가 없는 대화의 다른 effort 저장은 그대로 둔다.
        if (ModelChoice.EFFORT_NONE.equals(choice.reasoningEffort())) {
            Agent agent = agents.requireStartable(
                    user, agents.requireById(conversation.agentId()).code());
            modelTiers.requireEffortAllowed(user, agent, choice);
        }
        if (conversations.chooseModelIfActive(
                        conversationId,
                        user.id(),
                        choice.provider(),
                        choice.model(),
                        choice.reasoningEffort(),
                        ModelSelectionMode.CUSTOM)
                == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        return access.requireOwn(user, conversationId);
    }

    /** 대화가 고른 단계를 저장한다. DEFAULT 는 에이전트 기본 모델만 쓰도록 사용자·그룹 기본값도 건너뛴다. */
    Conversation chooseModelTier(CurrentUser user, Long conversationId, ModelSelectionMode mode, ModelTier tier) {
        Conversation conversation = access.requireOwn(user, conversationId);
        if (mode == null
                || mode == ModelSelectionMode.CUSTOM
                || (mode == ModelSelectionMode.TIER && tier == null)
                || (mode == ModelSelectionMode.DEFAULT && tier != null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid model tier selection");
        }
        if (mode == ModelSelectionMode.TIER) {
            Agent agent = agents.requireStartable(
                    user, agents.requireById(conversation.agentId()).code());
            modelTiers.resolveTier(user, tier, agent);
        }
        if (conversations.chooseModelTierIfActive(conversationId, user.id(), mode, tier) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        return access.requireOwn(user, conversationId);
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
     * <p>사진을 먼저 올리거나 첫 메시지 전에 모델을 고르려면 대화가 먼저 있어야 한다. 모델은 흐름이 붙은
     * 에이전트에서도 고르므로 에이전트를 쓸 수 있는지만 본다. 사진을 받지 않는 에이전트는 보낼 때 거절한다.
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
