package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.domain.RunSession;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 대화 turn 에 보낼 Hermes session 을 정한다.
 *
 * <p>새 대화는 첫 turn 을 보내기 전에 Control Plane 이 {@code fos-<uuid>} 를 만들어 보낼 session 과 루트
 * session 에 함께 적는다. Hermes 는 모르는 session id 를 받으면 그 id 로 session 을 만든다. 그래서 제출하기
 * 전에 실행 줄에 루트 session 을 적을 수 있고, MCP {@code agent_*} 호출이 들고 오는 서명한 루트 session 으로
 * 도는 실행을 찾을 수 있다. 근거는 ADR-031 에 있다.
 *
 * <p>흐름의 하위 실행은 {@code RunSession.fresh()} 로 자기 session 을 정하고, Memory 제안은 session 을 적지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ConversationSessions {

    private final ConversationRepository conversations;
    private final ConversationWriter conversationWriter;

    /**
     * 이 turn 에 보낼 session 을 돌려준다. 대화에 없으면 새로 정해 저장한다.
     *
     * <p>이미 있으면 그대로 쓴다. 루트 칸이 빈 옛 대화도 Hermes 가 정한 값을 그대로 쓰고 루트를 채우지 않는다.
     * 같은 새 대화에 두 turn 이 함께 와서 다른 쪽이 먼저 정했으면 저장된 값을 다시 읽어 그것을 쓴다.
     * 대화의 {@code updatedAt} 은 바꾸지 않는다.
     */
    public RunSession ensure(Conversation conversation) {
        if (conversation.hermesSessionId() != null) {
            return sessionOf(conversation);
        }
        String created = RunSession.newSessionId();
        if (conversationWriter.assignSessionIfAbsent(conversation.id(), created) == 1) {
            conversation.assignNewSession(created);
            return sessionOf(conversation);
        }
        Conversation stored = conversations
                .findById(conversation.id())
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "conversation not found"));
        conversation.adoptSessions(stored.hermesSessionId(), stored.hermesRootSessionId());
        return sessionOf(conversation);
    }

    private static RunSession sessionOf(Conversation conversation) {
        return RunSession.ofConversation(conversation.hermesSessionId(), conversation.hermesRootSessionId());
    }
}
