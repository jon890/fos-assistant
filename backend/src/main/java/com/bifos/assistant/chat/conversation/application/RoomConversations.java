package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.agent.application.PrimaryAgents;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기본 비서와 늘 이어지는 대화방을 찾고 만든다(docs/features/agent-team/spec.md 의 REQ8). 규칙은 {@code docs/features/agent-team.md}
 * 의 「대화방」 이 갖는다.
 *
 * <p>대화방은 사용자마다 지우지 않은 것 가운데 {@code id} 가 가장 큰 것 하나를 이어 쓴다. 점검 대화({@link CheckConversations})와
 * 같은 규칙이고 에이전트가 아니라 사용자로만 찾는다.
 */
@Component
@RequiredArgsConstructor
public class RoomConversations {

    /** 대화방의 고정 제목이다. 화면은 제목 대신 에이전트 이름표를 그린다. 비어 있지 않아 첫 메시지가 제목을 채우지 않는다. */
    static final String TITLE = "대화방";

    private final ConversationRepository conversations;
    private final PrimaryAgents primaryAgents;
    private final Clock clock;

    /**
     * 찾기와 만들기를 사용자마다 차례로 하게 하는 잠금이다. 두 창이 함께 열어도 대화방이 둘 생기지 않게 한다.
     *
     * <p>메모리에 있어 서버 한 대를 전제로 한다. 겹쳐 둘이 생겨도 {@code id} 가 큰 것을 쓰므로 하나로 보인다.
     */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();


    /** 그 사용자의 대화방. 지운 것은 없는 것과 같다. */
    public Optional<Conversation> find(Long userId) {
        return conversations.findFirstByUserIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(userId, ConversationPurpose.ROOM);
    }


    /**
     * 요청자의 대화방을 찾고, 없으면 기본 비서로 만든다. 지난 대화를 옮겨 오지 않아 처음 열면 빈 방이다(spec DECISION24).
     *
     * @throws ApiException {@code AGENT_NOT_FOUND}. 대화방이 없고 기본 비서도 없다
     */
    public Conversation findOrCreate(CurrentUser user) {
        ReentrantLock lock = locks.computeIfAbsent(user.id(), key -> new ReentrantLock());
        lock.lock();
        try {
            Optional<Conversation> existing = find(user.id());
            if (existing.isPresent()) {
                return existing.get();
            }
            Agent primary = primaryAgents.of(user)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "this user has no primary assistant"));
            return conversations.save(Conversation.startedForRoom(user.id(), TITLE, primary.id(), clock.instant()));
        } finally {
            lock.unlock();
        }
    }
}
